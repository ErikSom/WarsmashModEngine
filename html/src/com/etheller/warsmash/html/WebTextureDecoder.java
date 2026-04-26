package com.etheller.warsmash.html;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.util.Blp1Decoder;
import com.etheller.warsmash.util.ImageUtils.DecodedImage;
import com.etheller.warsmash.util.RgbaImage;
import com.etheller.warsmash.util.TgaDecoder;
import com.etheller.warsmash.util.ImageUtils.TextureDecoder;

/**
 * Web-side {@link TextureDecoder}. Handles palette BLP and TGA directly. JPEG
 * BLP is deliberately treated as unsupported here instead of delegating to
 * {@code new Pixmap(byte[], ...)} because that constructor is not reliable in
 * the TeaVM/web runtime and can fail inside generated JavaScript.
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
		if (Blp1Decoder.isBlp1(bytes)) {
			if (Blp1Decoder.isJpeg(bytes)) {
				final RgbaImage placeholder = Blp1Decoder.createPlaceholderMip0RgbaImage(bytes);
				return placeholder == null ? null : placeholder.toPixmap();
			}
			return Blp1Decoder.decodePaletteMip0(bytes);
		}
		if (TgaDecoder.isTga(bytes)) {
			final RgbaImage decoded = TgaDecoder.decode(bytes);
			return decoded == null ? null : decoded.toPixmap();
		}
		return decodeGenericPixmap(bytes);
	}

	@Override
	public Texture getAnyExtensionTexture(final DataSource dataSource, final String path) {
		final DecodedImage image = getAnyExtensionImageData(dataSource, path);
		return ((image == null) || (image.getImageData() == null)) ? placeholder()
				: toTexture(image.getImageData().toPixmap());
	}

	@Override
	public DecodedImage getAnyExtensionImageDataCachedOnly(final DataSource dataSource, final String path) {
		// Cache-hit-only variant. Only returns a DecodedImage when the
		// {@link DecodedRgbaCache} already has full RGBA for this path
		// (e.g. populated by an earlier WebBlpAsyncUpgrader async upgrade).
		// Returns null otherwise — including for JPEG BLP cache miss, where
		// the standard variant would synthesise a magenta placeholder.
		// BlpTexture relies on this to fall through to its decode-on-demand
		// branch and schedule the async full-res upgrade.
		final RgbaImage cached = DecodedRgbaCache.take(path);
		if (cached != null) {
			return new DecodedImage(false, cached, cached);
		}
		// Palette BLPs decode synchronously and quickly even on the web —
		// no upgrade needed, just hand back the full mip-0 RGBA so BlpTexture
		// uploads it directly. Same logic as the regular variant for palette
		// BLPs only (deliberately skips the JPEG / generic fallback path).
		try {
			final byte[] bytes = readPath(dataSource, path);
			if ((bytes != null) && Blp1Decoder.isBlp1(bytes) && !Blp1Decoder.isJpeg(bytes)) {
				final RgbaImage palette = Blp1Decoder.decodePaletteMip0RgbaImage(bytes);
				if (palette != null) {
					return new DecodedImage(false, palette, palette);
				}
			}
		}
		catch (final IOException e) {
			// Fall through to null.
		}
		return null;
	}

	@Override
	public DecodedImage getAnyExtensionImageData(final DataSource dataSource, final String path) {
		// First check the side cache populated by ExtractedPreloader for JPEG BLPs —
		// those are decoded in the browser at preload time and stashed as raw RGBA,
		// because the {@code new Pixmap(pngBytes)} path is broken on TeaVM and would
		// otherwise yield an all-black texture.
		final RgbaImage cached = DecodedRgbaCache.take(path);
		if ((path != null) && path.toLowerCase().contains("tree")) {
			System.out.println("[rgba-lookup] " + path + " cacheHit=" + (cached != null)
					+ " cacheSize=" + DecodedRgbaCache.size());
		}
		if (cached != null) {
			return new DecodedImage(false, cached, cached);
		}
		final byte[] bytes = readAnyExtensionBytes(dataSource, path);
		if (bytes == null) {
			return null;
		}
		RgbaImage image = null;
		if (Blp1Decoder.isBlp1(bytes)) {
			image = Blp1Decoder.decodeMip0RgbaImage(bytes);
			if ((image == null) && Blp1Decoder.isJpeg(bytes)) {
				// JPEG BLP cache miss. Synchronously decode mip 0 via
				// jpeg-js — slower than the async canvas path but the only
				// option for callers like Terrain / GroundTexture / UI atlas
				// that build their texture once and don't have a re-upload
				// hook for an async upgrade. The set is small (~tens of
				// textures), so the cumulative main-thread cost is bounded
				// (~1-2 s spread across engine boot).
				final com.etheller.warsmash.util.Blp1Decoder.JpegMipData mip0 = Blp1Decoder.extractJpegMip0Data(bytes);
				if (mip0 != null) {
					final byte[] rgba = BrowserImageBridge.decodeJpegBlpMipToRgbaSync(mip0);
					if (rgba != null) {
						final ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length);
						for (int i = 0; i < rgba.length; i++) {
							buf.put(i, rgba[i]);
						}
						buf.position(0);
						image = new RgbaImage(mip0.getWidth(), mip0.getHeight(), buf);
					}
				}
				if (image == null) {
					System.err.println("JPEG BLP sync decode failed; using placeholder for: " + path);
					image = Blp1Decoder.createPlaceholderMip0RgbaImage(bytes);
				}
			}
		}
		else if (TgaDecoder.isTga(bytes)) {
			image = TgaDecoder.decode(bytes);
		}
		else {
			final Pixmap pixmap = decodeToPixmap(bytes);
			if (pixmap != null) {
				image = RgbaImage.fromPixmap(pixmap);
			}
		}
		if (image == null) {
			return null;
		}
		return new DecodedImage(false, image, image);
	}

	private static byte[] toByteArray(final ByteBuffer bb) {
		final ByteBuffer dup = bb.duplicate();
		dup.position(0);
		final byte[] out = new byte[dup.remaining()];
		dup.get(out);
		return out;
	}

	private static byte[] readAnyExtensionBytes(final DataSource dataSource, final String path) {
		try {
			if (path.toLowerCase().endsWith(".blp")) {
				// Match the original game / desktop path first: prefer the actual BLP
				// bytes, then fall back to alternate container formats only if the BLP is
				// absent. Preferring PNG first changes alpha semantics and can mask bugs in
				// the real BLP decode path.
				byte[] bytes = readPath(dataSource, path);
				if (bytes != null) {
					return bytes;
				}
				bytes = readPath(dataSource, path.substring(0, path.length() - 4) + ".tga");
				if (bytes != null) {
					return bytes;
				}
				bytes = readPath(dataSource, path.substring(0, path.length() - 4) + ".dds");
				if (bytes != null) {
					return bytes;
				}
				return readPath(dataSource, path.substring(0, path.length() - 4) + ".png");
			}
			return readPath(dataSource, path);
		}
		catch (final IOException e) {
			return null;
		}
	}

	private static byte[] readPath(final DataSource dataSource, final String path) throws IOException {
		try (InputStream stream = dataSource.getResourceAsStream(path)) {
			if (stream == null) {
				return null;
			}
			final ByteBuffer bb = dataSource.read(path);
			return (bb == null) ? null : toByteArray(bb);
		}
	}

	private static Texture toTexture(final Pixmap pm) {
		final Texture t = new Texture(pm);
		t.setFilter(TextureFilter.Linear, TextureFilter.Linear);
		return t;
	}

	private static Pixmap decodeGenericPixmap(final byte[] bytes) {
		try {
			return new Pixmap(bytes, 0, bytes.length);
		}
		catch (final Throwable t) {
			return null;
		}
	}

	private static Texture placeholder() {
		final Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
		pm.drawPixel(0, 0, 0xFF00FFFF); // magenta, fully opaque
		return toTexture(pm);
	}
}
