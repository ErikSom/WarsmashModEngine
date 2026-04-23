package com.etheller.warsmash.viewer5.handlers.blp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.etheller.warsmash.util.ImageUtils;
import com.etheller.warsmash.viewer5.ModelViewer;
import com.etheller.warsmash.viewer5.PathSolver;
import com.etheller.warsmash.viewer5.RawOpenGLTextureResource;
import com.etheller.warsmash.viewer5.handlers.ResourceHandler;

public class DdsTexture extends RawOpenGLTextureResource {

	public DdsTexture(final ModelViewer viewer, final ResourceHandler handler, final String extension,
			final PathSolver pathSolver, final String fetchUrl) {
		super(viewer, extension, pathSolver, fetchUrl, handler);
	}

	@Override
	protected void lateLoad() {
	}

	@Override
	protected void load(final Object src, final Object options) {
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
