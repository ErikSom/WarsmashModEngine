package com.etheller.warsmash.util;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.etheller.warsmash.datasources.DataSource;

/**
 * Platform-agnostic texture-loading facade. Delegates both {@code byte[]}
 * decoding and {@code DataSource}-keyed lookups to a pluggable
 * {@link TextureDecoder} set by the platform bootstrap.
 *
 * <p><b>No {@code java.awt.*} references in this class.</b> The desktop
 * build registers {@link AwtImageUtils#DECODER}; the web build registers
 * its own (AWT-free) decoder built on top of {@link Blp1Decoder}.
 */
public final class ImageUtils {
	public static final String DEFAULT_ICON_PATH = "ReplaceableTextures\\CommandButtons\\BTNTemp.blp";

	/** Must be set by the platform bootstrap before first texture load. */
	public static TextureDecoder textureDecoder;

	public static final class DecodedImage {
		private final boolean needsSRGBFix;
		private final RgbaImage imageData;
		private final RgbaImage rgbCorrectImageData;

		public DecodedImage(final boolean needsSRGBFix, final RgbaImage imageData,
				final RgbaImage rgbCorrectImageData) {
			this.needsSRGBFix = needsSRGBFix;
			this.imageData = imageData;
			this.rgbCorrectImageData = rgbCorrectImageData == null ? imageData : rgbCorrectImageData;
		}

		public boolean isNeedsSRGBFix() {
			return this.needsSRGBFix;
		}

		public RgbaImage getImageData() {
			return this.imageData;
		}

		public RgbaImage getRGBCorrectImageData() {
			return this.rgbCorrectImageData;
		}
	}

	public interface TextureDecoder {
		/** Decode raw image bytes (BLP / TGA / DDS) into a libGDX Texture. */
		Texture decode(byte[] bytes, boolean sRGBFix);

		/** Decode raw image bytes into a libGDX Pixmap (for callers like
		 *  {@code RawOpenGLTextureResource} that upload to GL themselves). */
		Pixmap decodeToPixmap(byte[] bytes);

		/** Look up an asset (with BLP → TGA → DDS fallback) and decode. */
		Texture getAnyExtensionTexture(DataSource dataSource, String path);

		/** Look up an asset as raw RGBA pixels plus its color-space hint. */
		DecodedImage getAnyExtensionImageData(DataSource dataSource, String path);
	}

	public static Texture getAnyExtensionTexture(final DataSource dataSource, final String path) {
		if (textureDecoder == null) {
			throw new IllegalStateException("ImageUtils.textureDecoder is unset — platform bootstrap missing");
		}
		return textureDecoder.getAnyExtensionTexture(dataSource, path);
	}

	public static DecodedImage getAnyExtensionImageData(final DataSource dataSource, final String path) {
		if (textureDecoder == null) {
			throw new IllegalStateException("ImageUtils.textureDecoder is unset — platform bootstrap missing");
		}
		return textureDecoder.getAnyExtensionImageData(dataSource, path);
	}

	public static Texture decode(final byte[] bytes, final boolean sRGBFix) {
		if (textureDecoder == null) {
			throw new IllegalStateException("ImageUtils.textureDecoder is unset — platform bootstrap missing");
		}
		return textureDecoder.decode(bytes, sRGBFix);
	}

	public static Pixmap decodeToPixmap(final byte[] bytes) {
		if (textureDecoder == null) {
			throw new IllegalStateException("ImageUtils.textureDecoder is unset — platform bootstrap missing");
		}
		return textureDecoder.decodeToPixmap(bytes);
	}

	private ImageUtils() {
	}
}
