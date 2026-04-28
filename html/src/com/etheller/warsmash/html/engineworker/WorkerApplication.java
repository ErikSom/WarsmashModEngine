package com.etheller.warsmash.html.engineworker;

import java.util.ArrayDeque;
import java.util.Deque;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;

/**
 * Minimal {@link Application} for the worker port. SpriteBatch construction
 * touches {@code Gdx.app} indirectly through {@code ShaderProgram}'s
 * compile-error logging path, so this needs to be wired before constructing
 * any libGDX rendering objects. Most surface here is conservative stubs —
 * the spike will reveal which methods libGDX classes actually reach.
 *
 * <p>{@link #postRunnable(Runnable)} queues for the worker's render loop;
 * "main loop thread" in libGDX terms is the worker thread itself.
 */
final class WorkerApplication implements Application {
	private final Graphics graphics;
	private final Deque<Runnable> pendingRunnables = new ArrayDeque<>();
	private final Array<LifecycleListener> lifecycleListeners = new Array<>();
	private final java.util.Map<String, Preferences> preferencesByName = new java.util.HashMap<>();
	private ApplicationListener listener;
	private ApplicationLogger logger = new WorkerApplicationLogger();
	private int logLevel = LOG_INFO;

	WorkerApplication(final Graphics graphics) {
		this.graphics = graphics;
	}

	void setApplicationListener(final ApplicationListener listener) {
		this.listener = listener;
	}

	/** Drain queued runnables — call once per frame from the worker render loop. */
	void runPending() {
		while (true) {
			final Runnable r;
			synchronized (this.pendingRunnables) {
				r = this.pendingRunnables.pollFirst();
			}
			if (r == null) {
				break;
			}
			try {
				r.run();
			}
			catch (final Throwable t) {
				error("WorkerApplication", "postRunnable threw", t);
			}
		}
	}

	/**
	 * Duck-types {@code WebApplication.getConfig()} so the cast-and-call in
	 * gdx-teavm's emulated {@code Pixmap(FileHandle)} resolves at runtime.
	 * TeaVM compiles {@code ((WebApplication)Gdx.app).getConfig()} into a
	 * direct {@code $getConfig} method call without an instanceof check, so
	 * matching the method shape is enough — the emu code stores the result in
	 * a local variable and never reads it. We deliberately do NOT extend
	 * {@code WebApplication}: its constructor calls {@code WebWindow.get()}
	 * (window-only) and registers DOM listeners, both of which fail in worker.
	 */
	public WebApplicationConfiguration getConfig() {
		return null;
	}

	@Override public ApplicationListener getApplicationListener() { return this.listener; }
	@Override public Graphics getGraphics() { return this.graphics; }
	@Override public Audio getAudio() { return null; }
	@Override public Input getInput() { return null; }
	@Override public Files getFiles() { return null; }
	@Override public Net getNet() { return null; }

	@Override
	public void log(final String tag, final String message) {
		if (this.logLevel >= LOG_INFO) this.logger.log(tag, message);
	}

	@Override
	public void log(final String tag, final String message, final Throwable exception) {
		if (this.logLevel >= LOG_INFO) this.logger.log(tag, message, exception);
	}

	@Override
	public void error(final String tag, final String message) {
		if (this.logLevel >= LOG_ERROR) this.logger.error(tag, message);
	}

	@Override
	public void error(final String tag, final String message, final Throwable exception) {
		if (this.logLevel >= LOG_ERROR) this.logger.error(tag, message, exception);
	}

	@Override
	public void debug(final String tag, final String message) {
		if (this.logLevel >= LOG_DEBUG) this.logger.debug(tag, message);
	}

	@Override
	public void debug(final String tag, final String message, final Throwable exception) {
		if (this.logLevel >= LOG_DEBUG) this.logger.debug(tag, message, exception);
	}

	@Override public void setLogLevel(final int logLevel) { this.logLevel = logLevel; }
	@Override public int getLogLevel() { return this.logLevel; }
	@Override public void setApplicationLogger(final ApplicationLogger applicationLogger) { this.logger = applicationLogger; }
	@Override public ApplicationLogger getApplicationLogger() { return this.logger; }

	@Override public ApplicationType getType() { return ApplicationType.WebGL; }
	@Override public int getVersion() { return 0; }
	@Override public long getJavaHeap() { return 0; }
	@Override public long getNativeHeap() { return 0; }
	@Override
	public Preferences getPreferences(final String name) {
		Preferences p = this.preferencesByName.get(name);
		if (p == null) {
			p = new WorkerPreferences();
			this.preferencesByName.put(name, p);
		}
		return p;
	}
	@Override public Clipboard getClipboard() { return null; }

	@Override
	public void postRunnable(final Runnable runnable) {
		synchronized (this.pendingRunnables) {
			this.pendingRunnables.addLast(runnable);
		}
	}

	@Override public void exit() { /* no-op — page lifecycle owns shutdown */ }

	@Override
	public void addLifecycleListener(final LifecycleListener listener) {
		synchronized (this.lifecycleListeners) {
			this.lifecycleListeners.add(listener);
		}
	}

	@Override
	public void removeLifecycleListener(final LifecycleListener listener) {
		synchronized (this.lifecycleListeners) {
			this.lifecycleListeners.removeValue(listener, true);
		}
	}
}
