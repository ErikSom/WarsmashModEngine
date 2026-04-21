package com.etheller.warsmash.html.worker;

import org.teavm.jso.JSBody;

public final class WorkerMain {
	private WorkerMain() {
	}

	@JSBody(params = { "msg" }, script = "self.postMessage(msg);")
	public static native void postMessage(String msg);

	@JSBody(params = { "path", "n" }, script = "self.w3ReadHeader(path, n);")
	public static native void readHeader(String path, int n);

	public static void main(final String[] args) {
		postMessage("worker: hello from TeaVM");
		readHeader(".build.info", 32);
		readHeader("Data/data/data.000", 32);
		postMessage("worker: dispatched header reads");
	}
}
