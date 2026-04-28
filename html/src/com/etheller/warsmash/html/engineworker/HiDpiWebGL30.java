package com.etheller.warsmash.html.engineworker;

import com.badlogic.gdx.graphics.GL20;
import com.github.xpenatan.gdx.teavm.backends.web.WebGL30;
import com.github.xpenatan.gdx.teavm.backends.web.gl.WebGL2RenderingContextExt;

/**
 * {@link WebGL30} wrapper that scales {@code glViewport} / {@code glScissor}
 * calls by a HiDPI factor when the default framebuffer is bound.
 *
 * <p>The engine's {@code Scene.startFrame()} and libGDX's {@code Viewport}
 * classes call {@code glViewport} with logical (CSS-pixel) dimensions —
 * the latter via {@code HdpiUtils.glViewport} which auto-scales, the former
 * directly. Without scaling, 3D scenes render into a logical-pixel-sized
 * sub-rectangle of the back buffer, half-resolution on a typical HiDPI
 * display. Scaling here at the GL level makes both code paths correct
 * without modifying engine source.
 *
 * <p>Off-screen render targets (shadow maps, render-to-texture) bind their
 * own framebuffer with explicit pixel dimensions. Scaling those would
 * break — a 1024×1024 shadow map should stay 1024×1024. We track FBO
 * binds and only scale when the default framebuffer is active.
 */
final class HiDpiWebGL30 extends WebGL30 {
	private final float scale;
	private boolean defaultFramebufferBound = true;

	HiDpiWebGL30(final WebGL2RenderingContextExt ctx, final float scale) {
		super(ctx);
		this.scale = scale;
	}

	@Override
	public void glViewport(final int x, final int y, final int width, final int height) {
		if (this.defaultFramebufferBound) {
			super.glViewport(
					Math.round(x * this.scale),
					Math.round(y * this.scale),
					Math.round(width * this.scale),
					Math.round(height * this.scale));
		}
		else {
			super.glViewport(x, y, width, height);
		}
	}

	@Override
	public void glScissor(final int x, final int y, final int width, final int height) {
		if (this.defaultFramebufferBound) {
			super.glScissor(
					Math.round(x * this.scale),
					Math.round(y * this.scale),
					Math.round(width * this.scale),
					Math.round(height * this.scale));
		}
		else {
			super.glScissor(x, y, width, height);
		}
	}

	@Override
	public void glBindFramebuffer(final int target, final int framebuffer) {
		super.glBindFramebuffer(target, framebuffer);
		if (target == GL20.GL_FRAMEBUFFER) {
			this.defaultFramebufferBound = (framebuffer == 0);
		}
	}
}
