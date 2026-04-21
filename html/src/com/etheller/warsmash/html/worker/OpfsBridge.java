package com.etheller.warsmash.html.worker;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Sync-looking Java wrappers around async JS OPFS helpers defined in
 * worker-boot.js. TeaVM {@code @Async} rewrites the calling method into
 * a state machine that awaits the JS promise, so call sites can treat
 * these as if they were synchronous.
 */
public final class OpfsBridge {
	private OpfsBridge() {
	}

	@Async
	public static native byte[] readFull(String path);

	private static void readFull(final String path, final AsyncCallback<byte[]> cb) {
		readFullImpl(path, arr -> cb.complete(toByteArray(arr)), err -> cb.error(new RuntimeException(err)));
	}

	@Async
	public static native byte[] readRange(String path, int offset, int length);

	private static void readRange(final String path, final int offset, final int length,
			final AsyncCallback<byte[]> cb) {
		readRangeImpl(path, offset, length, arr -> cb.complete(toByteArray(arr)),
				err -> cb.error(new RuntimeException(err)));
	}

	public static String[] listUnder(final String prefix) {
		final String joined = listUnderImpl(prefix);
		if (joined == null || joined.isEmpty()) {
			return new String[0];
		}
		return joined.split("\n");
	}

	public static long fileSize(final String path) {
		return (long) fileSizeImpl(path);
	}

	public static String[] findMpqFiles() {
		final String joined = findMpqFilesImpl();
		if (joined == null || joined.isEmpty()) {
			return new String[0];
		}
		return joined.split("\n");
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
			script = "self.w3ReadFullAsync(path).then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void readFullImpl(String path, Int8ArrayCallback ok, StringCallback err);

	@JSBody(params = { "path", "offset", "length", "ok", "err" },
			script = "self.w3ReadRangeAsync(path, offset, length).then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void readRangeImpl(String path, int offset, int length,
			Int8ArrayCallback ok, StringCallback err);

	@JSBody(params = { "prefix" }, script = "return self.w3ListPathsWithPrefix(prefix);")
	private static native String listUnderImpl(String prefix);

	@JSBody(params = { "path" }, script = "return self.w3FileSize(path);")
	private static native double fileSizeImpl(String path);

	@JSBody(script = "return self.w3FindMpqFiles();")
	private static native String findMpqFilesImpl();

	// ---------------- MPQ sync-read handle management ----------------

	@Async
	public static native void openMpqHandle(String path);

	private static void openMpqHandle(final String path, final AsyncCallback<Void> cb) {
		openMpqHandleImpl(path, () -> cb.complete(null),
				err -> cb.error(new RuntimeException(err)));
	}

	@JSBody(params = { "path", "ok", "err" },
			script = "self.w3OpenMpqHandleAsync(path).then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void openMpqHandleImpl(String path, VoidCallback ok, StringCallback err);

	@JSBody(params = { "path" }, script = "self.w3CloseMpqHandle(path);")
	public static native void closeMpqHandle(String path);

	@JSBody(params = { "path" }, script = "return self.w3MpqSize(path);")
	public static native double mpqSize(String path);

	/**
	 * Synchronous read from a pre-opened MPQ sync access handle. Writes into
	 * {@code buf.buffer} at {@code bufOffset}. Returns bytes read.
	 */
	@JSBody(params = { "path", "offset", "length", "buf", "bufOffset" },
			script = "return self.w3ReadMpq(path, offset, length, buf, bufOffset);")
	public static native int readMpq(String path, double offset, int length, Int8Array buf, int bufOffset);

	// ---------------- Extracted-output writer ----------------

	@Async
	public static native void writeExtracted(String relPath, byte[] bytes);

	private static void writeExtracted(final String relPath, final byte[] bytes, final AsyncCallback<Void> cb) {
		final Int8Array arr = Int8Array.create(bytes.length);
		for (int i = 0; i < bytes.length; i++) {
			arr.set(i, bytes[i]);
		}
		writeExtractedImpl(relPath, arr, () -> cb.complete(null),
				err -> cb.error(new RuntimeException(err)));
	}

	@JSBody(params = { "path", "bytes", "ok", "err" },
			script = "self.w3WriteExtractedAsync(path, bytes).then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void writeExtractedImpl(String path, Int8Array bytes,
			VoidCallback ok, StringCallback err);

	@Async
	public static native void clearExtracted();

	private static void clearExtracted(final AsyncCallback<Void> cb) {
		clearExtractedImpl(() -> cb.complete(null), err -> cb.error(new RuntimeException(err)));
	}

	@JSBody(params = { "ok", "err" },
			script = "self.w3ClearExtracted().then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void clearExtractedImpl(VoidCallback ok, StringCallback err);

	/**
	 * Read an extracted file's bytes (under OPFS /extracted). Returns null if
	 * the file does not exist. Mirrors {@link #readFull(String)} so we reuse
	 * the proven String-param async pattern — empirically, {@code @Async}
	 * natives with no other parameters can trigger runtime errors.
	 */
	@Async
	public static native byte[] readExtracted(String relPath);

	private static void readExtracted(final String relPath, final AsyncCallback<byte[]> cb) {
		readExtractedImpl(relPath,
				arr -> cb.complete(arr == null ? null : toByteArray(arr)),
				err -> cb.complete(null));
	}

	@JSBody(params = { "path", "ok", "err" },
			script = "self.w3ReadExtractedAsync(path).then(ok)"
					+ ".catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void readExtractedImpl(String path, Int8ArrayCallback ok, StringCallback err);

	@org.teavm.jso.JSFunctor
	interface VoidCallback extends JSObject {
		void accept();
	}

	@org.teavm.jso.JSFunctor
	interface Int8ArrayCallback extends JSObject {
		void accept(Int8Array value);
	}

	@org.teavm.jso.JSFunctor
	interface StringCallback extends JSObject {
		void accept(String value);
	}

}
