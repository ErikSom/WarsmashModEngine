package com.etheller.warsmash.html.network;

import org.teavm.jso.JSBody;
import org.teavm.jso.core.JSString;

import com.etheller.warsmash.networking.DesyncReport;

/**
 * Web-side bridge from {@link DesyncReport} to the main-thread DOM
 * overlay. Engine-worker → main-thread via {@code self.postMessage};
 * main thread renders a copyable diagnostic textarea.
 */
public final class WebDesyncReportBridge {

	public static final DesyncReport.Handler HANDLER = new DesyncReport.Handler() {
		@Override
		public void onDesync(final int gameTurnTick, final String peerHashSummary,
				final long localStateHash, final String localStateDump) {
			jsPostDesync(gameTurnTick, peerHashSummary == null ? "" : peerHashSummary,
					"0x" + Long.toHexString(localStateHash),
					localStateDump == null ? "" : localStateDump);
		}
		@Override
		public void onCombinedReport(final int gameTurnTick, final String combinedReport) {
			jsPostCombined(gameTurnTick, combinedReport == null ? "" : combinedReport);
		}
	};

	@JSBody(params = { "turn", "peerHashes", "localHashHex", "localDump" }, script = ""
			+ "self.postMessage({"
			+ "  kind: 'mp-desync-report',"
			+ "  turn: turn,"
			+ "  peerHashes: peerHashes,"
			+ "  localHashHex: localHashHex,"
			+ "  localDump: localDump"
			+ "});")
	private static native void jsPostDesync(int turn, String peerHashes, String localHashHex, String localDump);

	@JSBody(params = { "turn", "combinedReport" }, script = ""
			+ "self.postMessage({"
			+ "  kind: 'mp-desync-combined-report',"
			+ "  turn: turn,"
			+ "  combinedReport: combinedReport"
			+ "});")
	private static native void jsPostCombined(int turn, String combinedReport);

	/** Pluggable {@link DesyncReport.ClientInfoSupplier} implementation
	 *  that reads {@code navigator.userAgent} + {@code navigator.platform}
	 *  + {@code navigator.hardwareConcurrency} from the worker scope.
	 *  Workers have a navigator object (a subset of the main-thread one)
	 *  with these fields available. */
	public static final DesyncReport.ClientInfoSupplier CLIENT_INFO_SUPPLIER = new DesyncReport.ClientInfoSupplier() {
		@Override
		public String describe() {
			final JSString ua = jsUserAgent();
			final JSString plat = jsPlatform();
			final int cores = jsHardwareConcurrency();
			final StringBuilder sb = new StringBuilder();
			sb.append(ua == null ? "" : ua.stringValue());
			if (plat != null && !plat.stringValue().isEmpty()) {
				sb.append(" | platform=").append(plat.stringValue());
			}
			if (cores > 0) {
				sb.append(" | cores=").append(cores);
			}
			return sb.toString();
		}
	};

	@JSBody(params = {}, script = "return (typeof navigator !== 'undefined' && navigator.userAgent) ? navigator.userAgent : '';")
	private static native JSString jsUserAgent();

	@JSBody(params = {}, script = "return (typeof navigator !== 'undefined' && navigator.platform) ? navigator.platform : '';")
	private static native JSString jsPlatform();

	@JSBody(params = {}, script = "return (typeof navigator !== 'undefined' && typeof navigator.hardwareConcurrency === 'number') ? navigator.hardwareConcurrency : 0;")
	private static native int jsHardwareConcurrency();

	private WebDesyncReportBridge() {
	}
}
