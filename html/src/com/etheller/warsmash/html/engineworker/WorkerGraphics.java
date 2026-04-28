package com.etheller.warsmash.html.engineworker;

import com.badlogic.gdx.AbstractGraphics;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.GL32;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.glutils.HdpiMode;

/**
 * Minimal {@link com.badlogic.gdx.Graphics} for the worker port. SpriteBatch
 * only consumes {@code getWidth}/{@code getHeight} at construction; the rest
 * is conservative defaults so libGDX internals that read these fields never
 * NPE. Display-mode / cursor / monitor APIs return null no-ops — the engine
 * doesn't use them on web today.
 *
 * <p>Most of the surface here is stubs on purpose: the spike's job is to
 * find out which methods libGDX classes actually reach. Anything more
 * elaborate would prejudge the answer.
 */
final class WorkerGraphics extends AbstractGraphics {
	// Logical (CSS-pixel) dimensions are what SpriteBatch's default projection
	// uses, what InputProcessor.touchDown coords are reported in, and what the
	// engine sees as "the screen size". Back-buffer dims are the GPU surface
	// in physical pixels — bigger on HiDPI displays. Keeping these separate
	// is the standard libGDX HiDPI shape.
	private int logicalWidth;
	private int logicalHeight;
	private int backBufferWidth;
	private int backBufferHeight;
	private GL20 gl20;
	private GL30 gl30;
	private GLVersion glVersion;

	private long frameId;
	private long lastFrameNanos;
	private float deltaTime;
	private int framesPerSecond;
	private int framesThisSecond;
	private long fpsAccumNanos;

	WorkerGraphics(final int logicalWidth, final int logicalHeight,
			final int backBufferWidth, final int backBufferHeight) {
		this.logicalWidth = logicalWidth;
		this.logicalHeight = logicalHeight;
		this.backBufferWidth = backBufferWidth;
		this.backBufferHeight = backBufferHeight;
	}

	/** Update both dimensions when the canvas is resized from the main thread. */
	void onResize(final int logicalWidth, final int logicalHeight,
			final int backBufferWidth, final int backBufferHeight) {
		this.logicalWidth = logicalWidth;
		this.logicalHeight = logicalHeight;
		this.backBufferWidth = backBufferWidth;
		this.backBufferHeight = backBufferHeight;
	}

	void setGLVersion(final GLVersion version) {
		this.glVersion = version;
	}

	/**
	 * Called from the worker render loop once per frame so {@link #getDeltaTime()}
	 * and {@link #getFrameId()} report sensible values to anything that reads
	 * them (libGDX's {@code Animation}, {@code FPSLogger}, etc.).
	 */
	void onFrame(final double timestampMs) {
		this.frameId++;
		final long now = (long) (timestampMs * 1_000_000L);
		if (this.lastFrameNanos != 0) {
			final long deltaNanos = now - this.lastFrameNanos;
			this.deltaTime = deltaNanos / 1_000_000_000f;
			this.fpsAccumNanos += deltaNanos;
			this.framesThisSecond++;
			if (this.fpsAccumNanos >= 1_000_000_000L) {
				this.framesPerSecond = this.framesThisSecond;
				this.framesThisSecond = 0;
				this.fpsAccumNanos = 0;
			}
		}
		this.lastFrameNanos = now;
	}

	@Override public boolean isGL30Available() { return this.gl30 != null; }
	@Override public boolean isGL31Available() { return false; }
	@Override public boolean isGL32Available() { return false; }
	@Override public GL20 getGL20() { return this.gl20; }
	@Override public GL30 getGL30() { return this.gl30; }
	@Override public GL31 getGL31() { return null; }
	@Override public GL32 getGL32() { return null; }
	@Override public void setGL20(final GL20 gl20) { this.gl20 = gl20; }
	@Override public void setGL30(final GL30 gl30) { this.gl30 = gl30; }
	@Override public void setGL31(final GL31 gl31) { /* unsupported */ }
	@Override public void setGL32(final GL32 gl32) { /* unsupported */ }

	@Override public int getWidth() { return this.logicalWidth; }
	@Override public int getHeight() { return this.logicalHeight; }
	@Override public int getBackBufferWidth() { return this.backBufferWidth; }
	@Override public int getBackBufferHeight() { return this.backBufferHeight; }
	@Override public int getSafeInsetLeft() { return 0; }
	@Override public int getSafeInsetTop() { return 0; }
	@Override public int getSafeInsetBottom() { return 0; }
	@Override public int getSafeInsetRight() { return 0; }

	@Override public long getFrameId() { return this.frameId; }
	@Override public float getDeltaTime() { return this.deltaTime; }
	@Override public int getFramesPerSecond() { return this.framesPerSecond; }

	@Override public GraphicsType getType() { return GraphicsType.WebGL; }
	@Override public GLVersion getGLVersion() { return this.glVersion; }
	@Override public float getPpiX() { return 96f; }
	@Override public float getPpiY() { return 96f; }
	@Override public float getPpcX() { return 96f / 2.54f; }
	@Override public float getPpcY() { return 96f / 2.54f; }

	@Override public boolean supportsDisplayModeChange() { return false; }
	@Override public Monitor getPrimaryMonitor() { return null; }
	@Override public Monitor getMonitor() { return null; }
	@Override public Monitor[] getMonitors() { return new Monitor[0]; }
	@Override public DisplayMode[] getDisplayModes() { return new DisplayMode[0]; }
	@Override public DisplayMode[] getDisplayModes(final Monitor monitor) { return new DisplayMode[0]; }
	@Override public DisplayMode getDisplayMode() { return null; }
	@Override public DisplayMode getDisplayMode(final Monitor monitor) { return null; }
	@Override public boolean setFullscreenMode(final DisplayMode displayMode) { return false; }
	@Override public boolean setWindowedMode(final int width, final int height) { return false; }
	@Override public void setTitle(final String title) { /* no-op */ }
	@Override public void setUndecorated(final boolean undecorated) { /* no-op */ }
	@Override public void setResizable(final boolean resizable) { /* no-op */ }
	@Override public void setVSync(final boolean vsync) { /* no-op */ }
	@Override public void setForegroundFPS(final int fps) { /* no-op */ }
	@Override public BufferFormat getBufferFormat() { return new BufferFormat(8, 8, 8, 8, 16, 0, 0, false); }
	@Override public boolean supportsExtension(final String extension) { return false; }
	@Override public void setContinuousRendering(final boolean isContinuous) { /* no-op */ }
	@Override public boolean isContinuousRendering() { return true; }
	@Override public void requestRendering() { /* no-op — always continuous */ }
	@Override public boolean isFullscreen() { return false; }

	@Override public Cursor newCursor(final Pixmap pixmap, final int xHotspot, final int yHotspot) { return null; }
	@Override public void setCursor(final Cursor cursor) { /* no-op */ }
	@Override public void setSystemCursor(final SystemCursor systemCursor) { /* no-op */ }

	// HdpiMode is referenced by some downstream callers; provide a no-op pair
	// to keep type metadata reachable without prejudicing scaling behaviour.
	public HdpiMode getHdpiMode() { return HdpiMode.Logical; }
	public void setHdpiMode(final HdpiMode mode) { /* no-op */ }
}
