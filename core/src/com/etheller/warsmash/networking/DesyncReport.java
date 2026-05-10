package com.etheller.warsmash.networking;

/**
 * Cross-platform pluggable hook for surfacing desync diagnostics to the
 * user. Engine code (specifically {@link WarsmashClient#desyncDetected})
 * calls {@link #publish}; platform installers (web boot, desktop launcher)
 * register a {@link Handler} that decides where the report goes — a DOM
 * overlay on the web, a Swing dialog or stdout on desktop, etc.
 *
 * <p>Default handler logs to {@code System.err} so the report is at least
 * preserved if no platform handler is installed.
 */
public final class DesyncReport {

	public interface Handler {
		void onDesync(int gameTurnTick, String peerHashSummary, long localStateHash, String localStateDump);
		/** Server-aggregated combined report covering all clients' dumps. */
		void onCombinedReport(int gameTurnTick, String combinedReport);
	}

	/** Platform-installable hook providing a short string describing this
	 *  client's environment (browser/userAgent on web, OS+JVM on desktop,
	 *  etc.). Embedded into each peer's state dump so the combined report
	 *  can show which platform each peer is on — useful for "desyncs only
	 *  happen between Chrome and Firefox" style triage. */
	public interface ClientInfoSupplier {
		String describe();
	}

	private static volatile ClientInfoSupplier clientInfoSupplier = () -> "";

	public static void installClientInfoSupplier(final ClientInfoSupplier supplier) {
		if (supplier != null) {
			clientInfoSupplier = supplier;
		}
	}

	public static String getClientInfo() {
		try {
			final String s = clientInfoSupplier.describe();
			return s == null ? "" : s;
		}
		catch (final Throwable t) {
			return "";
		}
	}

	private static volatile Handler handler = new Handler() {
		@Override
		public void onDesync(final int turn, final String peers, final long localHash, final String dump) {
			System.err.println("=== DESYNC at turn " + turn + " ===");
			System.err.println("Peer hashes:\n" + peers);
			System.err.println("Local hash: 0x" + Long.toHexString(localHash));
			System.err.println("Local state:\n" + dump);
		}
		@Override
		public void onCombinedReport(final int turn, final String combinedReport) {
			System.err.println("=== COMBINED DESYNC REPORT at turn " + turn + " ===");
			System.err.println(combinedReport);
		}
	};

	public static void install(final Handler h) {
		handler = h;
	}

	public static void publish(final int gameTurnTick, final String peerHashSummary,
			final long localStateHash, final String localStateDump) {
		final Handler h = handler;
		if (h != null) {
			try {
				h.onDesync(gameTurnTick, peerHashSummary, localStateHash, localStateDump);
			}
			catch (final Throwable t) {
				// Don't let the diagnostic UI itself bring down the engine —
				// fall back to stderr.
				System.err.println("DesyncReport handler threw: " + t);
				System.err.println("=== DESYNC at turn " + gameTurnTick + " ===");
				System.err.println("Peer hashes:\n" + peerHashSummary);
				System.err.println("Local state:\n" + localStateDump);
			}
		}
	}

	public static void publishCombined(final int gameTurnTick, final String combinedReport) {
		final Handler h = handler;
		if (h != null) {
			try {
				h.onCombinedReport(gameTurnTick, combinedReport);
			}
			catch (final Throwable t) {
				System.err.println("DesyncReport combined handler threw: " + t);
				System.err.println("=== COMBINED DESYNC REPORT at turn " + gameTurnTick + " ===");
				System.err.println(combinedReport);
			}
		}
	}

	private DesyncReport() {
	}
}
