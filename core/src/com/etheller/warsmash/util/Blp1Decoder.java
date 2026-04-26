package com.etheller.warsmash.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

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

	public static final class JpegMipData {
		private final int width;
		private final int height;
		private final int alphaDepth;
		private final int pictureType;
		private final byte[] jpegBytes;
		private final byte[] alphaBytes;

		public JpegMipData(final int width, final int height, final int alphaDepth, final int pictureType,
				final byte[] jpegBytes, final byte[] alphaBytes) {
			this.width = width;
			this.height = height;
			this.alphaDepth = alphaDepth;
			this.pictureType = pictureType;
			this.jpegBytes = jpegBytes;
			this.alphaBytes = alphaBytes;
		}

		public int getWidth() {
			return this.width;
		}

		public int getHeight() {
			return this.height;
		}

		public int getAlphaDepth() {
			return this.alphaDepth;
		}

		public int getPictureType() {
			return this.pictureType;
		}

		public byte[] getJpegBytes() {
			return this.jpegBytes;
		}

		public byte[] getAlphaBytes() {
			return this.alphaBytes;
		}
	}

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

	public static int getMip0Width(final byte[] bytes) {
		return (!isBlp1(bytes) || (bytes.length < HEADER_SIZE)) ? 0 : readInt(bytes, 12);
	}

	public static int getMip0Height(final byte[] bytes) {
		return (!isBlp1(bytes) || (bytes.length < HEADER_SIZE)) ? 0 : readInt(bytes, 16);
	}

	public static Pixmap decodeMip0(final byte[] bytes) {
		if (isJpeg(bytes)) {
			return decodeJpegMip0(bytes);
		}
		return decodePaletteMip0(bytes);
	}

	public static RgbaImage decodeMip0RgbaImage(final byte[] bytes) {
		if (isJpeg(bytes)) {
			return null;
		}
		return decodePaletteMip0RgbaImage(bytes);
	}

	public static RgbaImage createPlaceholderMip0RgbaImage(final byte[] bytes) {
		final int width = getMip0Width(bytes);
		final int height = getMip0Height(bytes);
		if ((width <= 0) || (height <= 0)) {
			return null;
		}
		final ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				final boolean dark = (((x / 16) + (y / 16)) & 1) == 0;
				rgba.put((byte) (dark ? 0x60 : 0xFF));
				rgba.put((byte) 0x00);
				rgba.put((byte) (dark ? 0x60 : 0xFF));
				rgba.put((byte) 0xFF);
			}
		}
		rgba.flip();
		return new RgbaImage(width, height, rgba);
	}

	public static JpegMipData extractJpegMip0Data(final byte[] bytes) {
		return extractJpegMipData(bytes, 0);
	}

	/**
	 * Extract the JPEG bytes + alpha stream for an arbitrary mip level (0 = full
	 * resolution, increasing levels = halved each time, up to 15). BLP1 stores
	 * 16 mip slots; the first one with non-zero offset+size is valid and the
	 * chain typically halts when the smaller dimension reaches 1.
	 *
	 * <p>Used by the decode-on-demand path: a small mip (e.g. 4×4) is decoded
	 * synchronously to upload an instant blurry placeholder, while the full-res
	 * mip 0 is decoded asynchronously in the background.
	 */
	public static JpegMipData extractJpegMipData(final byte[] bytes, final int mipLevel) {
		if (!isBlp1(bytes) || !isJpeg(bytes) || (bytes.length < (HEADER_SIZE + 4))
				|| (mipLevel < 0) || (mipLevel > 15)) {
			return null;
		}
		final int alphaDepth = readInt(bytes, 8);
		final int width0 = readInt(bytes, 12);
		final int height0 = readInt(bytes, 16);
		final int pictureType = readInt(bytes, 20);
		final int mipOffset = readInt(bytes, 28 + (mipLevel * 4));
		final int mipSize = readInt(bytes, 92 + (mipLevel * 4));
		final int width = Math.max(1, width0 >> mipLevel);
		final int height = Math.max(1, height0 >> mipLevel);
		final int jpegHeaderLength = readInt(bytes, HEADER_SIZE);
		final int jpegHeaderOffset = HEADER_SIZE + 4;
		if ((width <= 0) || (height <= 0) || (mipOffset <= 0) || (mipSize <= 0) || (jpegHeaderLength <= 0)
				|| ((jpegHeaderOffset + jpegHeaderLength) > bytes.length)
				|| ((mipOffset + mipSize) > bytes.length)) {
			return null;
		}
		final byte[] jpegPlusTrailingBytes = new byte[jpegHeaderLength + mipSize];
		System.arraycopy(bytes, jpegHeaderOffset, jpegPlusTrailingBytes, 0, jpegHeaderLength);
		System.arraycopy(bytes, mipOffset, jpegPlusTrailingBytes, jpegHeaderLength, mipSize);
		final int jpegEnd = findJpegEndOffset(jpegPlusTrailingBytes);
		if (jpegEnd <= 0) {
			return null;
		}
		final byte[] jpegBytes = Arrays.copyOf(jpegPlusTrailingBytes, jpegEnd);
		byte[] alphaBytes = null;
		final int trailingBytes = jpegPlusTrailingBytes.length - jpegEnd;
		final int expectedAlphaBytes = alphaBytesFor(alphaDepth, width * height);
		if ((trailingBytes > 0) && (expectedAlphaBytes > 0) && (trailingBytes >= expectedAlphaBytes)) {
			alphaBytes = Arrays.copyOfRange(jpegPlusTrailingBytes, jpegPlusTrailingBytes.length - expectedAlphaBytes,
					jpegPlusTrailingBytes.length);
		}
		return new JpegMipData(width, height, alphaDepth, pictureType, jpegBytes, alphaBytes);
	}

	/**
	 * Walk the JPEG mip chain looking for a "thumbnail" mip — small enough
	 * that the sync decode is a couple of milliseconds at most, but big
	 * enough to retain the actual visual character of the texture under GL
	 * upscale (a 4×4 stretched to 256×256 with linear filtering is almost
	 * indistinguishable from a flat color block; 32×32 still has visible
	 * features). Picks the smallest mip whose larger dimension is ≥ 32, or
	 * mip 0 if the source is smaller than that.
	 */
	public static JpegMipData extractThumbnailJpegMipData(final byte[] bytes) {
		for (int level = 15; level >= 0; level--) {
			final JpegMipData data = extractJpegMipData(bytes, level);
			if (data == null) {
				continue;
			}
			if (Math.max(data.getWidth(), data.getHeight()) >= 32) {
				return data;
			}
		}
		return extractJpegMipData(bytes, 0);
	}

	private static int findJpegEndOffset(final byte[] jpegBytes) {
		for (int i = jpegBytes.length - 2; i >= 0; i--) {
			if (((jpegBytes[i] & 0xFF) == 0xFF) && ((jpegBytes[i + 1] & 0xFF) == 0xD9)) {
				return i + 2;
			}
		}
		return -1;
	}

	/**
	 * Decode the top-level (level-0) mipmap of a palette BLP. Returns null if
	 * the input isn't a palette BLP1 or is malformed.
	 */
	public static Pixmap decodePaletteMip0(final byte[] bytes) {
		final RgbaImage rgbaImage = decodePaletteMip0RgbaImage(bytes);
		return rgbaImage == null ? null : rgbaImage.toPixmap();
	}

	public static RgbaImage decodePaletteMip0RgbaImage(final byte[] bytes) {
		return decodePaletteMipNRgbaImage(bytes, 0);
	}

	/**
	 * Decode an arbitrary-level mip from a palette BLP. Same semantics as
	 * {@link #decodePaletteMip0RgbaImage(byte[])} but indexed by mip level so
	 * the decode-on-demand path can grab a small thumbnail mip synchronously.
	 */
	public static RgbaImage decodePaletteMipNRgbaImage(final byte[] bytes, final int mipLevel) {
		if (!isBlp1(bytes) || (bytes.length < HEADER_SIZE) || (mipLevel < 0) || (mipLevel > 15)) {
			return null;
		}
		final int compression = readInt(bytes, 4);
		if (compression != 1) {
			return null; // not a palette BLP
		}
		final int alphaDepth = readInt(bytes, 8);
		final int width0 = readInt(bytes, 12);
		final int height0 = readInt(bytes, 16);
		final int pictureType = readInt(bytes, 20);
		final int mipOffset = readInt(bytes, 28 + (mipLevel * 4));
		final int mipSize = readInt(bytes, 28 + 64 + (mipLevel * 4));
		final int width = Math.max(1, width0 >> mipLevel);
		final int height = Math.max(1, height0 >> mipLevel);

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

		final ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				final int i = (y * width) + x;
				final int paletteIndex = bytes[mipOffset + i] & 0xFF;
				final int p = paletteStart + (paletteIndex * 4);
				final int b = bytes[p] & 0xFF;
				final int g = bytes[p + 1] & 0xFF;
				final int r = bytes[p + 2] & 0xFF;
				final int a = decodeAlpha(bytes, alphaStart, alphaDepth, i, pictureType);
				final int pixel = i * 4;
				rgba.put(pixel, (byte) r);
				rgba.put(pixel + 1, (byte) g);
				rgba.put(pixel + 2, (byte) b);
				rgba.put(pixel + 3, (byte) a);
			}
		}
		return new RgbaImage(width, height, rgba);
	}

	/**
	 * Smallest-but-not-too-small palette mip; same selection rule as
	 * {@link #extractThumbnailJpegMipData(byte[])}.
	 */
	public static RgbaImage decodeThumbnailPaletteMipRgbaImage(final byte[] bytes) {
		RgbaImage smallestSeen = null;
		for (int level = 15; level >= 0; level--) {
			final RgbaImage img = decodePaletteMipNRgbaImage(bytes, level);
			if (img == null) {
				continue;
			}
			if (smallestSeen == null) {
				smallestSeen = img;
			}
			if (Math.max(img.getWidth(), img.getHeight()) >= 4) {
				return img;
			}
		}
		return smallestSeen;
	}

	private static Pixmap decodeJpegMip0(final byte[] bytes) {
		final JpegMipData mipData = extractJpegMip0Data(bytes);
		if (mipData == null) {
			return null;
		}
		try {
			return new Pixmap(mipData.getJpegBytes(), 0, mipData.getJpegBytes().length);
		}
		catch (final Throwable t) {
			return null;
		}
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
