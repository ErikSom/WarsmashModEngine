package com.etheller.warsmash.html;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.etheller.warsmash.util.RgbaImage;

/**
 * Side-channel cache of RGBA pixels keyed by asset path, bypassing the
 * {@link com.badlogic.gdx.graphics.Pixmap} API. Needed on TeaVM because
 * {@code new Pixmap(w, h, RGBA8888)} + {@code pixmap.getPixels().put(bytes)}
 * silently zeroes the buffer — every JPEG BLP → PNG decoded asset would end up
 * as an all-black texture. We stash the decoded RGBA here at preload time and
 * {@link WebTextureDecoder} pulls it straight out as an {@link RgbaImage}.
 *
 * <p>Bytes are copied from the cached {@code byte[]} into a fresh direct
 * {@link ByteBuffer} on {@link #take} using per-index absolute {@code put} —
 * empirically verified to round-trip correctly on TeaVM, unlike the bulk
 * {@code put(byte[])} which truncates for reasons of the libgdx-teavm bridge.
 */
final class DecodedRgbaCache {
	private static final Map<String, Entry> CACHE = new HashMap<>();

	private DecodedRgbaCache() {
	}

	static void put(final String path, final int width, final int height, final byte[] rgbaPixels) {
		if ((path == null) || (rgbaPixels == null) || (width <= 0) || (height <= 0)) {
			return;
		}
		CACHE.put(normalize(path), new Entry(width, height, rgbaPixels));
	}

	static RgbaImage take(final String path) {
		if (path == null) {
			return null;
		}
		final Entry entry = CACHE.get(normalize(path));
		if (entry == null) {
			return null;
		}
		final ByteBuffer copy = ByteBuffer.allocateDirect(entry.pixels.length);
		for (int i = 0; i < entry.pixels.length; i++) {
			copy.put(i, entry.pixels[i]);
		}
		copy.position(0);
		return new RgbaImage(entry.width, entry.height, copy);
	}

	static int size() {
		return CACHE.size();
	}

	private static String normalize(final String path) {
		return path.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private static final class Entry {
		final int width;
		final int height;
		final byte[] pixels;

		Entry(final int width, final int height, final byte[] pixels) {
			this.width = width;
			this.height = height;
			this.pixels = pixels;
		}
	}
}
