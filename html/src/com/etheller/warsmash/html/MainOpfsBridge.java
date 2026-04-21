package com.etheller.warsmash.html;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Main-thread JSO bridge to OPFS {@code /extracted}. All reads are async —
 * we deliberately do <b>not</b> use TeaVM {@code @Async} here because the
 * libGDX/TeaVM render loop calls into Java from {@code requestAnimationFrame}
 * callbacks, which is not a TeaVM threading context and therefore can't
 * suspend at an @Async boundary. Callers pass a {@link BytesCallback} instead.
 */
public final class MainOpfsBridge {
	private MainOpfsBridge() {
	}

	public interface BytesCallback {
		void onBytes(byte[] bytesOrNull);
	}

	public interface PathsCallback {
		void onPaths(String[] paths);
	}

	/** Recursively list OPFS /extracted. Empty array if /extracted is missing. */
	public static void listExtracted(final PathsCallback cb) {
		listExtractedImpl(
				joined -> cb.onPaths((joined == null || joined.isEmpty()) ? new String[0] : joined.split("\n")),
				err -> cb.onPaths(new String[0]));
	}

	@JSBody(params = { "ok", "err" },
			script = "window.w3MainListExtractedAsync().then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void listExtractedImpl(StringCallback ok, StringCallback err);

	/** Read an extracted file by relative path. Calls back with null on miss/error. */
	public static void readExtracted(final String relPath, final BytesCallback cb) {
		readExtractedImpl(relPath,
				arr -> cb.onBytes(arr == null ? null : toByteArray(arr)),
				err -> cb.onBytes(null));
	}

	private static byte[] toByteArray(final Int8Array arr) {
		final int len = arr.getLength();
		final byte[] out = new byte[len];
		for (int i = 0; i < len; i++) {
			out[i] = arr.get(i);
		}
		return out;
	}

	@JSBody(params = { "path", "ok", "err" },
			script = "window.w3MainReadExtractedAsync(path).then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void readExtractedImpl(String path, Int8ArrayCallback ok, StringCallback err);

	@org.teavm.jso.JSFunctor
	interface Int8ArrayCallback extends JSObject {
		void accept(Int8Array value);
	}

	@org.teavm.jso.JSFunctor
	interface StringCallback extends JSObject {
		void accept(String value);
	}
}
