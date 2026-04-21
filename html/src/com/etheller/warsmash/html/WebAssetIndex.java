package com.etheller.warsmash.html;

import org.teavm.jso.JSBody;

/**
 * Minimal read-only view of the OPFS-backed Warcraft III asset index.
 * The uploader (index.html) writes a JSON list of {p, s} entries to
 * localStorage under w3AssetsIndex. We read the raw JSON from Java and
 * surface a few summary fields.
 */
public final class WebAssetIndex {
	private WebAssetIndex() {
	}

	@JSBody(script = "return window.localStorage.getItem('w3AssetsIndex') || '';")
	public static native String rawJson();

	@JSBody(script = "try { return (JSON.parse(window.localStorage.getItem('w3AssetsIndex') || '[]')).length; }"
			+ " catch (e) { return -1; }")
	public static native int count();

	@JSBody(script = "try {"
			+ "  var a = JSON.parse(window.localStorage.getItem('w3AssetsIndex') || '[]');"
			+ "  var t = 0; for (var i = 0; i < a.length; i++) t += (a[i].s|0);"
			+ "  return t;"
			+ "} catch (e) { return -1; }")
	public static native double totalBytes();

	@JSBody(params = { "idx" }, script = "try {"
			+ "  var a = JSON.parse(window.localStorage.getItem('w3AssetsIndex') || '[]');"
			+ "  return (idx >= 0 && idx < a.length) ? a[idx].p : '';"
			+ "} catch (e) { return ''; }")
	public static native String pathAt(int idx);

	@JSBody(script = "return (window.__workerLog || []).length;")
	public static native int workerLogLength();

	@JSBody(params = { "idx" }, script = "var a = window.__workerLog || [];"
			+ " return (idx >= 0 && idx < a.length) ? a[idx] : '';")
	public static native String workerLogAt(int idx);
}
