package com.etheller.warsmash.util;

/**
 * Small indirection for platform-specific hooks that the engine would otherwise
 * reach via a native API (e.g. {@code java.awt.Desktop} on desktop,
 * {@code window.open} on web). Each backend installs its implementation at
 * startup — desktop from {@code DesktopLauncher}, web from
 * {@code WebWarsmashGame.create()}.
 *
 * <p>Keeping these hooks behind a pure-Java facade prevents the core engine
 * reachability graph from pulling in {@code java.awt.*} (which TeaVM can't
 * transpile) just because one button wanted to open a URL.
 */
public final class Platform {
	/** Opens a URL in whatever mechanism the current backend exposes. */
	public interface UrlOpener {
		/** @return true if the URL was successfully handed off to the OS/browser. */
		boolean openUrl(String url);
	}

	/**
	 * Default implementation: does nothing and returns false. Both the desktop
	 * and the web build install real implementations before the menu can show
	 * anything that would call this.
	 */
	public static UrlOpener urlOpener = url -> false;

	private Platform() {
	}

	/** Convenience: try to open the given URL. Returns true on success. */
	public static boolean openUrl(final String url) {
		if ((url == null) || url.isEmpty()) {
			return false;
		}
		final UrlOpener opener = urlOpener;
		if (opener == null) {
			return false;
		}
		try {
			return opener.openUrl(url);
		}
		catch (final Throwable t) {
			return false;
		}
	}
}
