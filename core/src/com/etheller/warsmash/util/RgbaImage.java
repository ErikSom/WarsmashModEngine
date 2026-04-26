package com.etheller.warsmash.util;

import java.nio.ByteBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;

/**
 * Minimal RGBA8888 image container that is safe to use on both desktop and web.
 * Pixels are stored row-major, top-to-bottom, in RGBA byte order.
 */
public final class RgbaImage {
	private final int width;
	private final int height;
	private final ByteBuffer pixels;

	public RgbaImage(final int width, final int height, final ByteBuffer pixels) {
		this.width = width;
		this.height = height;
		final ByteBuffer source = pixels.duplicate();
		source.position(0);
		this.pixels = ByteBuffer.allocateDirect(width * height * 4);
		this.pixels.put(source);
		this.pixels.flip();
	}

	public static RgbaImage fromPixmap(final Pixmap pixmap) {
		final ByteBuffer copy = ByteBuffer.allocateDirect(pixmap.getWidth() * pixmap.getHeight() * 4);
		final ByteBuffer source = pixmap.getPixels().duplicate();
		source.position(0);
		copy.put(source);
		copy.flip();
		return new RgbaImage(pixmap.getWidth(), pixmap.getHeight(), copy);
	}

	public int getWidth() {
		return this.width;
	}

	public int getHeight() {
		return this.height;
	}

	/** Matches {@link java.awt.image.BufferedImage#getRGB(int, int)} and returns ARGB. */
	public int getRGB(final int x, final int y) {
		final int index = ((y * this.width) + x) * 4;
		final int r = this.pixels.get(index) & 0xFF;
		final int g = this.pixels.get(index + 1) & 0xFF;
		final int b = this.pixels.get(index + 2) & 0xFF;
		final int a = this.pixels.get(index + 3) & 0xFF;
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public ByteBuffer getPixels() {
		final ByteBuffer duplicate = this.pixels.duplicate();
		duplicate.position(0);
		return duplicate;
	}

	public Pixmap toPixmap() {
		final Pixmap pixmap = new Pixmap(this.width, this.height, Format.RGBA8888);
		final ByteBuffer target = pixmap.getPixels();
		final ByteBuffer source = getPixels();
		// Per-byte absolute put — verified to round-trip correctly on the
		// TeaVM/web backend, unlike the bulk put(ByteBuffer) which silently
		// zeroes the destination (every Pixmap-wrapped texture would come
		// out fully transparent / all-zero RGBA, manifesting as missing
		// command-card ability icons and similar). Same workaround
		// {@link com.etheller.warsmash.html.DecodedRgbaCache} uses.
		final int n = source.remaining();
		for (int i = 0; i < n; i++) {
			target.put(i, source.get(i));
		}
		target.position(0);
		return pixmap;
	}
}
