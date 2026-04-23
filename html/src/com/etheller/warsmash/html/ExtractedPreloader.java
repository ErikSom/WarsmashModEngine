package com.etheller.warsmash.html;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.etheller.warsmash.datasources.InMemoryDataSource;
import com.etheller.warsmash.util.Blp1Decoder;
import com.etheller.warsmash.util.Blp1Decoder.JpegMipData;

/**
 * Preloads a set of extracted-asset paths from OPFS {@code /extracted} on the
 * main thread, async, and invokes a completion callback once an
 * {@link InMemoryDataSource} containing those bytes is ready.
 *
 * <p>Historically the preloader ran one file at a time: each {@code readExtracted}
 * waited on the previous file's canvas decode before scheduling the next read.
 * That produced ~30–45 s cold-boot times for Echo Isles (~4800 files, ~20–30 %
 * JPEG BLPs). The default path is now a bounded-parallel pump: up to N OPFS
 * reads and M canvas decodes in flight simultaneously. Pool sizes come from
 * {@link PreloadTuning}.
 *
 * <p>Rollback: set {@link PreloadTuning#tier1Parallel} to false (or pass
 * {@code ?tier1=0} in the URL) to fall back to the original serial chain.
 */
public final class ExtractedPreloader {
	public interface Progress {
		/** Called on every file after it resolves (present or missing). */
		void onFile(int indexDone, int total, String path, int bytes);
	}

	public interface Done {
		void onDone(InMemoryDataSource source, int missing);
	}

	private ExtractedPreloader() {
	}

	public static void preload(final List<String> paths, final Progress progress, final Done done) {
		PreloadTuning.ensureInitialized();
		final Map<String, byte[]> loaded = new LinkedHashMap<>();
		if (paths.isEmpty()) {
			done.onDone(new InMemoryDataSource(loaded), 0);
			return;
		}
		if (PreloadTuning.tier1Parallel) {
			new PumpState(paths, loaded, progress, done).start();
		}
		else {
			preloadOne(paths, 0, loaded, progress, done, 0);
		}
	}

	// ---------------------------------------------------------------------
	// Tier 1 parallel pump
	// ---------------------------------------------------------------------

	/**
	 * Single-owner (main-thread only) state for the bounded-parallel preload pump.
	 * No locks are necessary because TeaVM is single-threaded and every callback
	 * that mutates state runs synchronously on the main thread.
	 */
	private static final class PumpState {
		private final List<String> paths;
		private final Map<String, byte[]> loaded;
		private final Progress progress;
		private final Done done;
		private final int total;
		private final int opfsConcurrency;
		private final int decodeConcurrency;
		private final Deque<DecodeWork> decodeQueue = new ArrayDeque<>();

		private int nextIndex;
		private int inFlightOpfs;
		private int inFlightDecode;
		private int completed;
		private int missing;
		private boolean doneFired;

		PumpState(final List<String> paths, final Map<String, byte[]> loaded, final Progress progress, final Done done) {
			this.paths = paths;
			this.loaded = loaded;
			this.progress = progress;
			this.done = done;
			this.total = paths.size();
			this.opfsConcurrency = Math.max(1, PreloadTuning.opfsConcurrency);
			this.decodeConcurrency = Math.max(1, PreloadTuning.decodeConcurrency);
		}

		void start() {
			pump();
		}

		private void pump() {
			while ((this.inFlightOpfs < this.opfsConcurrency) && (this.nextIndex < this.total)) {
				final int index = this.nextIndex++;
				final String path = this.paths.get(index);
				this.inFlightOpfs++;
				MainOpfsBridge.readExtracted(path, data -> onReadComplete(path, data));
			}
		}

		private void onReadComplete(final String path, final byte[] data) {
			this.inFlightOpfs--;
			if (data == null) {
				this.missing++;
			}
			else {
				this.loaded.put(path, data);
			}

			if (shouldDecode(path, data)) {
				this.decodeQueue.addLast(new DecodeWork(path, data));
				drainDecodeQueue();
			}
			else {
				finishOne(path, data);
			}

			pump();
		}

		private void drainDecodeQueue() {
			while ((this.inFlightDecode < this.decodeConcurrency) && !this.decodeQueue.isEmpty()) {
				final DecodeWork work = this.decodeQueue.pollFirst();
				this.inFlightDecode++;
				final JpegMipData mipData = Blp1Decoder.extractJpegMip0Data(work.data);
				if (mipData == null) {
					onDecodeComplete(work);
					continue;
				}
				BrowserImageBridge.decodeJpegBlpMipToRgba(mipData, (rgbaBytes, width, height) -> {
					if (rgbaBytes != null) {
						DecodedRgbaCache.put(work.path, width, height, rgbaBytes);
					}
					onDecodeComplete(work);
				});
			}
		}

		private void onDecodeComplete(final DecodeWork work) {
			this.inFlightDecode--;
			finishOne(work.path, work.data);
			drainDecodeQueue();
		}

		private void finishOne(final String path, final byte[] data) {
			this.completed++;
			if (this.progress != null) {
				try {
					this.progress.onFile(this.completed, this.total, path, (data == null) ? -1 : data.length);
				}
				catch (final Throwable t) {
					// Progress callbacks must not break the pipeline.
				}
			}
			if ((this.completed >= this.total) && !this.doneFired) {
				this.doneFired = true;
				this.done.onDone(new InMemoryDataSource(this.loaded), this.missing);
			}
		}

		private static boolean shouldDecode(final String path, final byte[] data) {
			if ((data == null) || (path == null) || !path.toLowerCase().endsWith(".blp")) {
				return false;
			}
			return Blp1Decoder.isJpeg(data);
		}
	}

	private static final class DecodeWork {
		final String path;
		final byte[] data;

		DecodeWork(final String path, final byte[] data) {
			this.path = path;
			this.data = data;
		}
	}

	// ---------------------------------------------------------------------
	// Legacy serial path — kept intact for {@code ?tier1=0} rollback
	// ---------------------------------------------------------------------

	private static void preloadOne(final List<String> paths, final int index,
			final Map<String, byte[]> loaded, final Progress progress, final Done done, final int missing) {
		if (index >= paths.size()) {
			done.onDone(new InMemoryDataSource(loaded), missing);
			return;
		}
		final String path = paths.get(index);
		MainOpfsBridge.readExtracted(path, data -> {
			final int bytes = (data == null) ? -1 : data.length;
			int newMissing = missing;
			if (data == null) {
				newMissing++;
			}
			else {
				loaded.put(path, data);
			}
			final int missingAfterThisFile = newMissing;
			maybeAddDecodedRgbaVariant(path, data, () -> {
				if (progress != null) {
					try {
						progress.onFile(index + 1, paths.size(), path, bytes);
					}
					catch (final Throwable t) {
						// Swallow — progress callbacks mustn't break the pipeline.
					}
				}
				preloadOne(paths, index + 1, loaded, progress, done, missingAfterThisFile);
			});
		});
	}

	private static void maybeAddDecodedRgbaVariant(final String path, final byte[] data, final Runnable done) {
		if ((data == null) || (path == null) || !path.toLowerCase().endsWith(".blp") || !Blp1Decoder.isJpeg(data)) {
			done.run();
			return;
		}
		final JpegMipData mipData = Blp1Decoder.extractJpegMip0Data(data);
		if (mipData == null) {
			done.run();
			return;
		}
		BrowserImageBridge.decodeJpegBlpMipToRgba(mipData, (rgbaBytes, width, height) -> {
			if (rgbaBytes != null) {
				DecodedRgbaCache.put(path, width, height, rgbaBytes);
			}
			done.run();
		});
	}
}
