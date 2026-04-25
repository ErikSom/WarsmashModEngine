package com.etheller.warsmash.viewer5.handlers.blp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.etheller.warsmash.util.ImageUtils;
import com.etheller.warsmash.util.ImageUtils.DecodedImage;
import com.etheller.warsmash.util.RgbaImage;
import com.etheller.warsmash.viewer5.ModelViewer;
import com.etheller.warsmash.viewer5.PathSolver;
import com.etheller.warsmash.viewer5.RawOpenGLTextureResource;
import com.etheller.warsmash.viewer5.handlers.ResourceHandler;

public class BlpTexture extends RawOpenGLTextureResource {

	public BlpTexture(final ModelViewer viewer, final ResourceHandler handler, final String extension,
			final PathSolver pathSolver, final String fetchUrl) {
		super(viewer, extension, pathSolver, fetchUrl, handler);
	}

	@Override
	protected void lateLoad() {
	}

	@Override
	protected void load(final Object src, final Object options) {
		try {
			// Prefer the direct RGBA path: on the TeaVM/web backend, funnelling pixel
			// bytes through a {@code new Pixmap(w,h,RGBA8888)} + {@code getPixels().put(...)}
			// round-trip silently zeroes the buffer, so every texture uploads as pure
			// black. Upload the RgbaImage bytes straight to GL when we have them.
			//
			// sRGBFix=false: WebGL2's SRGB8_ALPHA8 internal format linearises pixel
			// values at sample time, but the classic MDX shader does its colour math
			// assuming raw (non-linearised) sRGB — so linearising darkens and desaturates
			// the output vs. the original game. Using RGBA8 keeps the bytes straight
			// through and matches WC3's look.
			if ((this.fetchUrl != null) && !this.fetchUrl.isEmpty()) {
				final DecodedImage decodedImage = ImageUtils.getAnyExtensionImageData(this.viewer.dataSource,
						this.fetchUrl);
				if ((decodedImage != null) && (decodedImage.getImageData() != null)) {
					final RgbaImage rgba = decodedImage.getImageData();
					updateFromRgba(rgba.getPixels(), rgba.getWidth(), rgba.getHeight(), false);
					return;
				}
			}
			if (src == null) {
				// Upstream couldn't locate the BLP bytes (e.g. a destructable's
				// shadow field is a sentinel like "none" / "_" / blank that still
				// slipped through to a load call). Leaving the texture
				// un-updated is safer than crashing the whole map load — callers
				// that bind this downstream render with a default texture rather
				// than NPEing.
				return;
			}
			final byte[] bytes = readAll(src);
			final Pixmap pm = ImageUtils.decodeToPixmap(bytes);
			if (pm != null) {
				update(pm, true);
			}
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] readAll(final Object src) throws IOException {
		if (src instanceof ByteBuffer) {
			final ByteBuffer duplicate = ((ByteBuffer) src).duplicate();
			duplicate.position(0);
			final byte[] out = new byte[duplicate.remaining()];
			duplicate.get(out);
			return out;
		}
		final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
		final byte[] buf = new byte[8192];
		int n;
		while ((n = ((InputStream) src).read(buf)) > 0) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}
}
