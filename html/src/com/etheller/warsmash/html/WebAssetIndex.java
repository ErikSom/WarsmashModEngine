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

	// Every script guards against `window` being undefined: the engine code
	// also runs in a Web Worker (engine-in-worker port) where `window` is
	// undefined and a bare reference would throw ReferenceError before any
	// try/catch on the JSON.parse can fire. localStorage / __workerLog are
	// main-thread-only state, so the worker-context branch returns sentinels
	// equivalent to "not present".
	@JSBody(script = "try { return (typeof window !== 'undefined' && window.localStorage)"
			+ "  ? (window.localStorage.getItem('w3AssetsIndex') || '') : ''; } catch (e) { return ''; }")
	public static native String rawJson();

	@JSBody(script = "try {"
			+ "  if (typeof window === 'undefined' || !window.localStorage) return -1;"
			+ "  return (JSON.parse(window.localStorage.getItem('w3AssetsIndex') || '[]')).length;"
			+ "} catch (e) { return -1; }")
	public static native int count();

	@JSBody(script = "try {"
			+ "  if (typeof window === 'undefined' || !window.localStorage) return -1;"
			+ "  var a = JSON.parse(window.localStorage.getItem('w3AssetsIndex') || '[]');"
			+ "  var t = 0; for (var i = 0; i < a.length; i++) t += (a[i].s|0);"
			+ "  return t;"
			+ "} catch (e) { return -1; }")
	public static native double totalBytes();

	@JSBody(params = { "idx" }, script = "try {"
			+ "  if (typeof window === 'undefined' || !window.localStorage) return '';"
			+ "  var a = JSON.parse(window.localStorage.getItem('w3AssetsIndex') || '[]');"
			+ "  return (idx >= 0 && idx < a.length) ? a[idx].p : '';"
			+ "} catch (e) { return ''; }")
	public static native String pathAt(int idx);

	@JSBody(script = "return (typeof window !== 'undefined' && window.__workerLog) ? window.__workerLog.length : 0;")
	public static native int workerLogLength();

	@JSBody(params = { "idx" },
			script = "var a = (typeof window !== 'undefined' && window.__workerLog) ? window.__workerLog : [];"
					+ " return (idx >= 0 && idx < a.length) ? a[idx] : '';")
	public static native String workerLogAt(int idx);
}
