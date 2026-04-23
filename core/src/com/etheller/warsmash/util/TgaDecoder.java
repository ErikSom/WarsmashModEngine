package com.etheller.warsmash.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.apache.commons.compress.utils.IOUtils;

/**
 * Minimal TGA decoder for the Warcraft III pathing/texture cases we need in
 * the browser port. Supports uncompressed 24-bit and 32-bit true-color images.
 */
public final class TgaDecoder {
	private static final int HEADER_SIZE = 18;

	private TgaDecoder() {
	}

	public static boolean isTga(final byte[] bytes) {
		return (bytes != null) && (bytes.length >= HEADER_SIZE)
				&& (bytes[1] == 0)
				&& (bytes[2] == 2)
				&& ((bytes[16] == 24) || (bytes[16] == 32));
	}

	public static RgbaImage decode(final InputStream stream) throws IOException {
		return decode(IOUtils.toByteArray(stream));
	}

	public static RgbaImage decode(final byte[] bytes) {
		if (!isTga(bytes)) {
			return null;
		}
		final int idLength = bytes[0] & 0xFF;
		final int width = readUnsignedShort(bytes, 12);
		final int height = readUnsignedShort(bytes, 14);
		final int pixelDepth = bytes[16] & 0xFF;
		final boolean alpha = pixelDepth == 32;
		if (((bytes[17] & 0x0F) != (alpha ? 8 : 0)) || (width <= 0) || (height <= 0)) {
			return null;
		}
		final int bytesPerPixel = alpha ? 4 : 3;
		final int dataOffset = HEADER_SIZE + idLength;
		if (bytes.length < (dataOffset + (width * height * bytesPerPixel))) {
			return null;
		}
		final ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
		final int origin = (bytes[17] >> 4) & 0x3;
		for (int srcY = 0; srcY < height; srcY++) {
			for (int srcX = 0; srcX < width; srcX++) {
				final int sourcePixel = ((srcY * width) + srcX) * bytesPerPixel;
				int destX = srcX;
				int destY = height - 1 - srcY;
				switch (origin) {
				case 0:
					break;
				case 1:
					destX = width - 1 - srcX;
					break;
				case 2:
					destX = width - 1 - srcX;
					destY = srcY;
					break;
				default:
					return null;
				}
				final int destPixel = ((destY * width) + destX) * 4;
				rgba.put(destPixel, bytes[dataOffset + sourcePixel + 2]);
				rgba.put(destPixel + 1, bytes[dataOffset + sourcePixel + 1]);
				rgba.put(destPixel + 2, bytes[dataOffset + sourcePixel]);
				rgba.put(destPixel + 3, alpha ? bytes[dataOffset + sourcePixel + 3] : (byte) 0xFF);
			}
		}
		return new RgbaImage(width, height, rgba);
	}

	private static int readUnsignedShort(final byte[] bytes, final int offset) {
		return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
	}
}
