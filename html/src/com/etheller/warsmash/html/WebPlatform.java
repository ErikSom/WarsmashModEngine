package com.etheller.warsmash.html;

import org.teavm.jso.JSBody;

/**
 * Web-side hooks for the {@link com.etheller.warsmash.util.Platform} facade.
 * Wired in {@link WebWarsmashGame#create()}.
 */
final class WebPlatform {
	private WebPlatform() {
	}

	/** Open a URL in a new browser tab. Returns true if the window was created. */
	static boolean openUrl(final String url) {
		try {
			return jsOpenUrl(url);
		}
		catch (final Throwable t) {
			return false;
		}
	}

	@JSBody(params = { "url" },
			script = "try { var w = window.open(url, '_blank', 'noopener,noreferrer'); return !!w; }"
					+ "catch (e) { return false; }")
	private static native boolean jsOpenUrl(String url);
}
