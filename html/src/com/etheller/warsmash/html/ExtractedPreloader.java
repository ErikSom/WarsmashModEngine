package com.etheller.warsmash.html;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.etheller.warsmash.datasources.InMemoryDataSource;

/**
 * Preloads a set of extracted-asset paths from OPFS {@code /extracted} on the
 * main thread, async, and invokes a completion callback once an
 * {@link InMemoryDataSource} containing those bytes is ready.
 *
 * <p>Calls are chained one at a time — OPFS can be opened in parallel but
 * serializing keeps memory pressure predictable and the progress log readable.
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
		final Map<String, byte[]> loaded = new LinkedHashMap<>();
		preloadOne(paths, 0, loaded, progress, done, 0);
	}

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
			if (progress != null) {
				try {
					progress.onFile(index + 1, paths.size(), path, bytes);
				}
				catch (final Throwable t) {
					// Swallow — progress callbacks mustn't break the pipeline.
				}
			}
			preloadOne(paths, index + 1, loaded, progress, done, newMissing);
		});
	}
}
