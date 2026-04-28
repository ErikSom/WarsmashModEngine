package com.etheller.warsmash.html.engineworker;

import com.badlogic.gdx.ApplicationLogger;

/**
 * Routes libGDX log lines through {@link EngineWorkerMain#postMessage(String)}
 * so they surface in the main-thread HUD. Critical during the spike: shader
 * compile errors and other libGDX diagnostics show up here, not in any
 * console the worker doesn't directly own.
 */
final class WorkerApplicationLogger implements ApplicationLogger {
	@Override
	public void log(final String tag, final String message) {
		EngineWorkerMain.postMessage("[gdx " + tag + "] " + message);
	}

	@Override
	public void log(final String tag, final String message, final Throwable exception) {
		EngineWorkerMain.postMessage("[gdx " + tag + "] " + message + " — " + exception);
	}

	@Override
	public void error(final String tag, final String message) {
		EngineWorkerMain.postMessage("[gdx ERROR " + tag + "] " + message);
	}

	@Override
	public void error(final String tag, final String message, final Throwable exception) {
		EngineWorkerMain.postMessage("[gdx ERROR " + tag + "] " + message + " — " + exception);
	}

	@Override
	public void debug(final String tag, final String message) {
		EngineWorkerMain.postMessage("[gdx DEBUG " + tag + "] " + message);
	}

	@Override
	public void debug(final String tag, final String message, final Throwable exception) {
		EngineWorkerMain.postMessage("[gdx DEBUG " + tag + "] " + message + " — " + exception);
	}
}
