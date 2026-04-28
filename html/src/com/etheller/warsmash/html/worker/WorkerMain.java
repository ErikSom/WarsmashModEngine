package com.etheller.warsmash.html.worker;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Locale;

import org.teavm.jso.JSBody;

import com.etheller.warsmash.datasources.MpqDataSource;

import mpq.MPQArchive;

/**
 * Extraction Web Worker entry. On first run, opens each uploaded main-game
 * MPQ via a pre-opened OPFS sync access handle wrapped as a
 * {@code SeekableByteChannel}, feeds it to Warsmash's MPQArchive/MpqDataSource,
 * and writes every listfile entry as a flat file under OPFS {@code /extracted}.
 * A {@code .w3-ready} marker is written on completion so subsequent worker
 * spawns short-circuit instead of re-extracting 1 GB of MPQs.
 */
public final class WorkerMain {
	/**
	 * MPQs in the order Warsmash's stock warsmash.ini layers them (later files
	 * take precedence, which means they extract last and overwrite earlier).
	 */
	private static final String[] MPQ_LAYER_ORDER = {
			"war3.mpq",
			"War3Local.mpq",
			"War3x.mpq",
			"War3xLocal.mpq",
	};

	/** How often to post a progress message during extraction. */
	private static final int PROGRESS_EVERY = 500;
	private static final int MAP_PROGRESS_EVERY = 25;
	private static final String MAPS_ROOT = "Maps/";

	private WorkerMain() {
	}

	@JSBody(params = { "msg" }, script = "self.postMessage(msg);")
	public static native void postMessage(String msg);

	private static final String READY_MARKER = ".w3-ready";

	public static void main(final String[] args) {
		postMessage("worker: java main running");

		try {
			if (OpfsBridge.readExtracted(READY_MARKER) != null) {
				postMessage("worker: extraction already complete — /extracted is ready");
				// Keep /w3 around: the engine-in-worker port reads MPQs straight
				// from there via SAH-backed MpqDataSource, skipping the
				// /extracted preload entirely. Storage cost is roughly the same
				// (~1.4 GB MPQs vs ~1 GB extracted) and once Phase 4 lands we
				// can drop /extracted instead. Until then, both trees coexist.
				return;
			}
		}
		catch (final Throwable t) {
			postMessage("worker: ready-check error: " + t.getMessage());
		}

		final String[] availableMpqs = OpfsBridge.findMpqFiles();
		if (availableMpqs.length == 0) {
			postMessage("worker: no .mpq files found in OPFS.");
			return;
		}

		int totalExtracted = 0;
		for (final String wanted : MPQ_LAYER_ORDER) {
			final String path = findByName(availableMpqs, wanted);
			if (path == null) {
				postMessage("worker: WARN missing " + wanted + " — skipping");
				continue;
			}
			try {
				totalExtracted += extractMpq(path);
			}
			catch (final Throwable t) {
				postMessage("worker: " + path + " EXTRACT ERROR: " + t.getClass().getSimpleName() + ": "
						+ t.getMessage());
				t.printStackTrace();
			}
		}

		try {
			totalExtracted += copyBuiltinMaps();
		}
		catch (final Throwable t) {
			postMessage("worker: map-copy error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			t.printStackTrace();
		}

		try {
			OpfsBridge.writeExtracted(READY_MARKER,
					String.valueOf(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			postMessage("worker: extraction complete, wrote ready marker. Total files: " + totalExtracted);
		}
		catch (final Throwable t) {
			postMessage("worker: mark-ready error: " + t.getMessage());
		}

		// Intentionally leave /w3 in place — the engine-in-worker port reads
		// MPQs from there via SAH. See note above.
	}

	private static int extractMpq(final String path) throws Exception {
		postMessage("worker: extracting " + path);
		OpfsBridge.openMpqHandle(path);
		try {
			final OpfsSeekableByteChannel channel = new OpfsSeekableByteChannel(path);
			final MPQArchive archive = new MPQArchive(channel);
			final MpqDataSource source = new MpqDataSource(archive, channel);
			final Collection<String> listfile = source.getListfile();
			if (listfile == null) {
				postMessage("worker:   " + path + " has no (listfile) — skipping");
				return 0;
			}
			final int total = listfile.size();
			int done = 0;
			int errors = 0;
			int written = 0;
			for (final String entry : listfile) {
				try {
					final ByteBuffer bb = source.read(entry);
					if (bb != null) {
						final byte[] bytes = (bb.hasArray() && bb.arrayOffset() == 0 && bb.limit() == bb.capacity())
								? bb.array()
								: copyToByteArray(bb);
						OpfsBridge.writeExtracted(entry, bytes);
						written++;
					}
					else {
						errors++;
					}
				}
				catch (final Throwable t) {
					errors++;
				}
				done++;
				if (done % PROGRESS_EVERY == 0) {
					postMessage("worker:   " + path + " " + done + "/" + total
							+ " (written=" + written + ", errors=" + errors + ")");
				}
			}
			postMessage("worker:   " + path + " done: written=" + written + "/" + total
					+ " errors=" + errors);
			return written;
		}
		finally {
			OpfsBridge.closeMpqHandle(path);
		}
	}

	private static byte[] copyToByteArray(final ByteBuffer bb) {
		final ByteBuffer dup = bb.duplicate();
		dup.position(0);
		final byte[] out = new byte[dup.remaining()];
		dup.get(out);
		return out;
	}

	private static int copyBuiltinMaps() throws Exception {
		final String[] uploadedPaths = OpfsBridge.listUnder(MAPS_ROOT);
		if (uploadedPaths.length == 0) {
			postMessage("worker: no builtin maps found under " + MAPS_ROOT);
			return 0;
		}
		int copied = 0;
		int considered = 0;
		for (final String path : uploadedPaths) {
			if (!isBuiltinMap(path)) {
				continue;
			}
			considered++;
			try {
				OpfsBridge.writeExtracted(path, OpfsBridge.readFull(path));
				copied++;
			}
			catch (final Throwable t) {
				postMessage("worker:   map copy failed for " + path + ": " + t.getMessage());
			}
			if ((considered % MAP_PROGRESS_EVERY) == 0) {
				postMessage("worker:   copied maps " + copied + "/" + considered);
			}
		}
		postMessage("worker: copied builtin maps " + copied + "/" + considered);
		return copied;
	}

	private static boolean isBuiltinMap(final String path) {
		final String normalized = path.replace('\\', '/');
		final String lower = normalized.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("maps/")) {
			return false;
		}
		if (lower.startsWith("maps/download/")) {
			return false;
		}
		return lower.endsWith(".w3m") || lower.endsWith(".w3x");
	}

	private static String findByName(final String[] candidates, final String nameWanted) {
		final String target = nameWanted.toLowerCase(Locale.ROOT);
		for (final String path : candidates) {
			final int slash = path.lastIndexOf('/');
			final String name = (slash == -1) ? path : path.substring(slash + 1);
			if (name.toLowerCase(Locale.ROOT).equals(target)) {
				return path;
			}
		}
		return null;
	}
}
