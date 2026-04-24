package com.etheller.warsmash.html;

import org.teavm.jso.JSBody;

/**
 * Feature flags and pool sizes for the web-port preload pipeline. Values can be
 * overridden at boot time via URL query params — no rebuild needed when tuning:
 *
 * <pre>
 *   ?preloadOpfs=N        override opfsConcurrency (integer, clamped to [1,64])
 *   ?preloadDecode=M      override decodeConcurrency (integer, clamped to [1,32])
 *   ?tier1=0|1            enable/disable the parallel pump in ExtractedPreloader
 * </pre>
 *
 * <p>The defaults are tuned for Chrome/Firefox with a typical 4-8-core desktop.
 * Canvas {@code getImageData} is still main-thread, so pushing decodeConcurrency
 * above {@code navigator.hardwareConcurrency / 2} tends not to help.
 */
final class PreloadTuning {
	private static boolean initialized;

	static int opfsConcurrency = 12;
	static int decodeConcurrency = 6;
	static boolean tier1Parallel = true;
	/**
	 * When true, after preload completes the web boot launches
	 * {@code WarsmashGdxMenuScreen} (the real MenuUI flow) instead of the
	 * minimal {@code WebMapViewScreen} direct-map harness. Opt-in while the
	 * menu path is still being stabilised.
	 */
	static boolean menuMode = false;

	private PreloadTuning() {
	}

	/**
	 * Apply URL query-param overrides if present. Idempotent — safe to call from
	 * multiple places. No-op if {@code window.location.search} is unavailable
	 * (e.g. in the worker context where this shouldn't be reached anyway).
	 */
	static synchronized void ensureInitialized() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			final String search = readQuery();
			if ((search == null) || search.isEmpty()) {
				return;
			}
			opfsConcurrency = clamp(intParam(search, "preloadOpfs", opfsConcurrency), 1, 64);
			decodeConcurrency = clamp(intParam(search, "preloadDecode", decodeConcurrency), 1, 32);
			tier1Parallel = boolParam(search, "tier1", tier1Parallel);
			menuMode = boolParam(search, "menu", menuMode);
		}
		catch (final Throwable t) {
			// Defaults are fine if the query string can't be parsed.
		}
	}

	@JSBody(params = {}, script = "return (typeof window !== 'undefined' && window.location) ? window.location.search : '';")
	private static native String readQuery();

	private static int intParam(final String search, final String key, final int fallback) {
		final String raw = rawParam(search, key);
		if (raw == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(raw);
		}
		catch (final NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean boolParam(final String search, final String key, final boolean fallback) {
		final String raw = rawParam(search, key);
		if (raw == null) {
			return fallback;
		}
		return "1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw);
	}

	private static String rawParam(final String search, final String key) {
		// Strip leading '?' if present and walk &-separated pairs.
		final String body = (search.startsWith("?")) ? search.substring(1) : search;
		if (body.isEmpty()) {
			return null;
		}
		final String[] parts = body.split("&");
		for (final String part : parts) {
			final int eq = part.indexOf('=');
			if (eq < 0) {
				if (part.equals(key)) {
					return "";
				}
				continue;
			}
			if (part.substring(0, eq).equals(key)) {
				return part.substring(eq + 1);
			}
		}
		return null;
	}

	private static int clamp(final int value, final int lo, final int hi) {
		return value < lo ? lo : (value > hi ? hi : value);
	}
}
