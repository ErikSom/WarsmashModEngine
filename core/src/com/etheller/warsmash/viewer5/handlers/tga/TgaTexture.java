package com.etheller.warsmash.viewer5.handlers.tga;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.badlogic.gdx.graphics.Pixmap;
import com.etheller.warsmash.util.ImageUtils;
import com.etheller.warsmash.viewer5.ModelViewer;
import com.etheller.warsmash.viewer5.PathSolver;
import com.etheller.warsmash.viewer5.RawOpenGLTextureResource;
import com.etheller.warsmash.viewer5.handlers.ResourceHandler;

public class TgaTexture extends RawOpenGLTextureResource {

	public TgaTexture(final ModelViewer viewer, final ResourceHandler handler, final String extension,
			final PathSolver pathSolver, final String fetchUrl) {
		super(viewer, extension, pathSolver, fetchUrl, handler);
	}

	@Override
	protected void lateLoad() {
	}

	@Override
	protected void load(final InputStream src, final Object options) {
		try {
			final byte[] bytes = readAll(src);
			final Pixmap pm = ImageUtils.decodeToPixmap(bytes);
			if (pm != null) {
				update(pm, false);
			}
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] readAll(final InputStream src) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
		final byte[] buf = new byte[8192];
		int n;
		while ((n = src.read(buf)) > 0) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}
}
