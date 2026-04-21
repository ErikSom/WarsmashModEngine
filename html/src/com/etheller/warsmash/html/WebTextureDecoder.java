package com.etheller.warsmash.html;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.util.Blp1Decoder;
import com.etheller.warsmash.util.ImageUtils.TextureDecoder;

/**
 * Web-side {@link TextureDecoder}. Handles palette BLP via
 * {@link Blp1Decoder#decodePaletteMip0(byte[])}; every other format (JPEG BLP,
 * TGA, DDS) gets a 1×1 magenta placeholder so the engine keeps running and
 * we can spot which texture paths need real decoders.
 */
public final class WebTextureDecoder implements TextureDecoder {
	@Override
	public Texture decode(final byte[] bytes, final boolean sRGBFix) {
		final Pixmap pm = decodeToPixmap(bytes);
		return (pm == null) ? placeholder() : toTexture(pm);
	}

	@Override
	public Pixmap decodeToPixmap(final byte[] bytes) {
		if (bytes == null) {
			return null;
		}
		if (Blp1Decoder.isBlp1(bytes) && !Blp1Decoder.isJpeg(bytes)) {
			return Blp1Decoder.decodePaletteMip0(bytes);
		}
		return null;
	}

	@Override
	public Texture getAnyExtensionTexture(final DataSource dataSource, final String path) {
		if (!path.toLowerCase().endsWith(".blp")) {
			return placeholder();
		}
		try (InputStream stream = dataSource.getResourceAsStream(path)) {
			if (stream == null) {
				return placeholder();
			}
			final ByteBuffer bb = dataSource.read(path);
			final byte[] bytes = (bb == null) ? null : toByteArray(bb);
			return decode(bytes, true);
		}
		catch (final IOException e) {
			return placeholder();
		}
	}

	private static byte[] toByteArray(final ByteBuffer bb) {
		final ByteBuffer dup = bb.duplicate();
		dup.position(0);
		final byte[] out = new byte[dup.remaining()];
		dup.get(out);
		return out;
	}

	private static Texture toTexture(final Pixmap pm) {
		final Texture t = new Texture(pm);
		t.setFilter(TextureFilter.Linear, TextureFilter.Linear);
		return t;
	}

	private static Texture placeholder() {
		final Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
		pm.drawPixel(0, 0, 0xFF00FFFF); // magenta, fully opaque
		return toTexture(pm);
	}
}
