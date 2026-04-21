package com.etheller.warsmash.html.worker;

import org.teavm.jso.JSBody;

/**
 * Entry point for the extraction Web Worker. Runs in the worker's global
 * scope — no DOM, no libGDX, no Gdx.* APIs. Only JSO + anything TeaVM's
 * classlib provides. For slice 1 this just posts a handful of messages
 * back to the main thread to prove the build + channel work.
 */
public final class WorkerMain {
	private WorkerMain() {
	}

	@JSBody(params = { "msg" }, script = "self.postMessage(msg);")
	public static native void postMessage(String msg);

	public static void main(final String[] args) {
		postMessage("worker: hello from TeaVM");
		for (int i = 1; i <= 3; i++) {
			postMessage("worker: tick " + i);
		}
		postMessage("worker: done");
	}
}
