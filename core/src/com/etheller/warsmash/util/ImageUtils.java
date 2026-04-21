package com.etheller.warsmash.util;

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

	public interface TextureDecoder {
		/** Decode raw image bytes (BLP / TGA / DDS) into a libGDX Texture. */
		Texture decode(byte[] bytes, boolean sRGBFix);

		/** Look up an asset (with BLP → TGA → DDS fallback) and decode. */
		Texture getAnyExtensionTexture(DataSource dataSource, String path);
	}

	public static Texture getAnyExtensionTexture(final DataSource dataSource, final String path) {
		if (textureDecoder == null) {
			throw new IllegalStateException("ImageUtils.textureDecoder is unset — platform bootstrap missing");
		}
		return textureDecoder.getAnyExtensionTexture(dataSource, path);
	}

	public static Texture decode(final byte[] bytes, final boolean sRGBFix) {
		if (textureDecoder == null) {
			throw new IllegalStateException("ImageUtils.textureDecoder is unset — platform bootstrap missing");
		}
		return textureDecoder.decode(bytes, sRGBFix);
	}

	private ImageUtils() {
	}
}
