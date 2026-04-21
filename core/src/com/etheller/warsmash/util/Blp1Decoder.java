package com.etheller.warsmash.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;

/**
 * Minimal BLP1 decoder for Warcraft III textures. Decodes directly to a libGDX
 * {@link Pixmap} without touching {@code java.awt.image.BufferedImage} — the
 * purpose being to let the engine's texture pipeline work under a TeaVM web
 * build where AWT is unavailable.
 *
 * <p>Supports: palette BLPs with alpha_depth ∈ {0, 1, 4, 8}. JPEG BLPs are
 * reported via {@link #isJpeg(byte[])}; decoding them needs a separate JPEG
 * decoder (the desktop path uses ImageIO's built-in JPEG codec — for web we
 * need to either port a pure-Java JPEG decoder or hand the JPEG stream to
 * libGDX's stb_image via {@code new Pixmap(byte[], int, int)}).
 *
 * <p>Layout reference: https://wowdev.wiki/BLP1
 */
public final class Blp1Decoder {
	private static final int HEADER_SIZE = 156;
	private static final int PALETTE_SIZE = 256 * 4;

	private Blp1Decoder() {
	}

	public static boolean isBlp1(final byte[] bytes) {
		return (bytes != null) && (bytes.length >= 4)
				&& (bytes[0] == 'B') && (bytes[1] == 'L') && (bytes[2] == 'P') && (bytes[3] == '1');
	}

	public static boolean isJpeg(final byte[] bytes) {
		if (!isBlp1(bytes)) {
			return false;
		}
		return readInt(bytes, 4) == 0;
	}

	/**
	 * Decode the top-level (level-0) mipmap of a palette BLP. Returns null if
	 * the input isn't a palette BLP1 or is malformed.
	 */
	public static Pixmap decodePaletteMip0(final byte[] bytes) {
		if (!isBlp1(bytes) || (bytes.length < HEADER_SIZE)) {
			return null;
		}
		final int compression = readInt(bytes, 4);
		if (compression != 1) {
			return null; // not a palette BLP
		}
		final int alphaDepth = readInt(bytes, 8);
		final int width = readInt(bytes, 12);
		final int height = readInt(bytes, 16);
		final int pictureType = readInt(bytes, 20);
		final int mipOffset = readInt(bytes, 28);
		final int mipSize = readInt(bytes, 28 + 64);

		if ((width <= 0) || (height <= 0) || (mipOffset <= 0) || (mipSize <= 0)
				|| ((mipOffset + mipSize) > bytes.length)) {
			return null;
		}

		final int paletteStart = HEADER_SIZE;
		if (bytes.length < (paletteStart + PALETTE_SIZE)) {
			return null;
		}

		final int pixelCount = width * height;
		final int alphaStart = mipOffset + pixelCount;
		final int alphaBytes = alphaBytesFor(alphaDepth, pixelCount);
		if ((alphaStart + alphaBytes) > bytes.length) {
			return null;
		}

		final Pixmap pixmap = new Pixmap(width, height, Format.RGBA8888);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				final int i = (y * width) + x;
				final int paletteIndex = bytes[mipOffset + i] & 0xFF;
				final int p = paletteStart + (paletteIndex * 4);
				final int b = bytes[p] & 0xFF;
				final int g = bytes[p + 1] & 0xFF;
				final int r = bytes[p + 2] & 0xFF;
				final int a = decodeAlpha(bytes, alphaStart, alphaDepth, i, pictureType);
				// Pixmap.drawPixel expects 0xRRGGBBAA
				final int rgba = (r << 24) | (g << 16) | (b << 8) | a;
				pixmap.drawPixel(x, y, rgba);
			}
		}
		return pixmap;
	}

	private static int alphaBytesFor(final int alphaDepth, final int pixelCount) {
		switch (alphaDepth) {
		case 0:
			return 0;
		case 1:
			return (pixelCount + 7) / 8;
		case 4:
			return (pixelCount + 1) / 2;
		case 8:
			return pixelCount;
		default:
			return 0;
		}
	}

	private static int decodeAlpha(final byte[] bytes, final int alphaStart, final int alphaDepth,
			final int pixelIndex, final int pictureType) {
		int alpha;
		switch (alphaDepth) {
		case 0:
			return 0xFF;
		case 1: {
			final int b = bytes[alphaStart + (pixelIndex / 8)] & 0xFF;
			alpha = ((b >>> (pixelIndex & 7)) & 1) == 0 ? 0x00 : 0xFF;
			break;
		}
		case 4: {
			final int b = bytes[alphaStart + (pixelIndex / 2)] & 0xFF;
			final int nib = ((pixelIndex & 1) == 0) ? (b & 0x0F) : ((b >>> 4) & 0x0F);
			alpha = (nib * 0x11); // expand 4→8 bits
			break;
		}
		case 8:
			alpha = bytes[alphaStart + pixelIndex] & 0xFF;
			break;
		default:
			alpha = 0xFF;
		}
		// Picture type 5 inverts alpha (rare); type 3/4 use it straight.
		if (pictureType == 5) {
			alpha = 0xFF - alpha;
		}
		return alpha;
	}

	private static int readInt(final byte[] bytes, final int offset) {
		return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}
}
