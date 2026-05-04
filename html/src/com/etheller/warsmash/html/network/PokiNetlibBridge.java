package com.etheller.warsmash.html.network;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * TeaVM bridge to {@code @poki/netlib}, talking to the main-thread netlib
 * Network instance via {@code postMessage} from the engine Web Worker. The
 * actual {@code Network} singleton lives on the main thread (loaded by
 * {@code poki-bridge.js} via {@code <script>} tag); the engine runs in a
 * separate Worker (engine-worker.js) where {@code self} is the worker's
 * global, so direct {@code self.pokiBridge*} calls from the worker would
 * fail. This bridge wraps everything in {@code self.postMessage} and an
 * {@code onmessage} handler.
 *
 * <p>Wire protocol (worker ⇄ main thread):
 * <pre>
 *   worker → main:
 *     {kind:'mp-rtc-send-to', peerId, channel, bytes: Int8Array}
 *     {kind:'mp-rtc-broadcast', channel, bytes: Int8Array}
 *     {kind:'mp-close'}
 *
 *   main → worker:
 *     {kind:'mp-ready', selfId}
 *     {kind:'mp-lobby', code}
 *     {kind:'mp-left'}
 *     {kind:'mp-peer-connected', peerId}
 *     {kind:'mp-peer-disconnected', peerId, reason}
 *     {kind:'mp-leader', leaderId}
 *     {kind:'mp-rtc-msg', peerId, channel, bytes: Uint8Array, isString, stringValue}
 *     {kind:'mp-error', errKind, message}
 *     {kind:'mp-state', selfId, currentLobby, currentLeader, peerIds:string[]}
 * </pre>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Call {@link #init} once with a UUID game ID and a {@link Listener}
 *       implementation. Installs the worker's {@code onmessage} handler.
 *       Returns true unconditionally — the main thread owns the netlib
 *       Network and reports readiness via {@code mp-ready}.</li>
 *   <li>Lobby host/join/leave is driven from the main thread DOM overlay,
 *       not from here. The worker is told about lobby events via the
 *       {@code mp-lobby}/{@code mp-left} kinds.</li>
 *   <li>Send bytes with {@link #sendBytesTo} (point-to-point) or
 *       {@link #broadcastBytes}. They postMessage to main, which forwards
 *       to {@code pokiBridge*} on the main-thread Network.</li>
 *   <li>Receive bytes via {@link Listener#onMessage}.</li>
 * </ol>
 *
 * <p>State accessors ({@link #selfId}, {@link #currentLobby}, etc.) read
 * from a small cache populated by the main thread's {@code mp-state} /
 * {@code mp-ready} / {@code mp-lobby} / {@code mp-peer-*} updates. They're
 * synchronous because the engine (specifically
 * {@code WebGameClientStarter.start}) needs to compare the host peer id
 * against {@code selfId} during a single render-frame call.
 */
public final class PokiNetlibBridge {

	/**
	 * Channel name for ordered-but-unreliable (UDP-like) traffic. Matches the
	 * {@code DefaultDataChannels.unreliable} entry in netlib —
	 * {@code ordered: true, maxRetransmits: 0}. This is the channel our
	 * lockstep gameplay traffic should ride; the engine's own
	 * {@link net.warsmash.networking.udp.OrderedUdpCommuncation} layer
	 * already handles reorder/replay across drops.
	 */
	public static final String CHANNEL_UNRELIABLE = "unreliable";

	/**
	 * Channel name for ordered+reliable (TCP-like) traffic. Used by the
	 * main-thread coordinator for control messages (ASSIGN_SESSION,
	 * START_GAME). Engine traffic stays on unreliable.
	 */
	public static final String CHANNEL_RELIABLE = "reliable";

	private static Listener listener;

	private PokiNetlibBridge() {
	}

	/** Listener for all netlib lifecycle + traffic events. Methods may run
	 *  off the main TeaVM frame (they originate from JS event-loop callbacks);
	 *  implementations that touch engine state should marshal back via
	 *  {@code Gdx.app.postRunnable(...)}. */
	public interface Listener {
		/** Signaling server connected; netlib has assigned us {@code selfId}. */
		void onReady(String selfId);
		/** We have entered a lobby (either by creating or joining). */
		void onLobbyJoined(String code);
		/** We have left the current lobby. */
		void onLobbyLeft();
		/** A peer's WebRTC datachannels have come up. {@code peerId} is the netlib peer id. */
		void onPeerConnected(String peerId);
		/** A peer has dropped (RTC connection lost or server says they left). */
		void onPeerDisconnected(String peerId, String reason);
		/** Lobby leadership changed (the leader is the de-facto host for our purposes). */
		void onLeaderChanged(String leaderId);
		/**
		 * Incoming binary message. {@code bytes} is a fresh array — safe to
		 * retain. {@code channel} is one of {@link #CHANNEL_UNRELIABLE} or
		 * {@link #CHANNEL_RELIABLE}.
		 */
		void onMessage(String peerId, String channel, byte[] bytes);
		/** Surfaced async errors (signaling, RTC, lobby ops). Diagnostic only. */
		void onError(String kind, String message);
	}

	/**
	 * Install the worker's {@code onmessage} handler, dispatching mp-* kinds
	 * to {@code listener}. {@code gameId} is unused here — the netlib
	 * Network already exists on the main thread with its own game id; we
	 * keep the parameter so the existing {@code WebRtcOrderedTransport
	 * .ensureInitialized(gameId)} call site doesn't need to change.
	 *
	 * <p>Returns true. (The main thread might still not have the netlib
	 * library — diagnosed via the {@code mp-error} channel rather than
	 * a sync return value, since worker-init isn't synchronous with
	 * netlib-load on the main thread.)
	 */
	public static boolean init(final String gameId, final Listener l) {
		listener = l;
		installMessageHandler(new IncomingMsgHandler() {
			@Override
			public void handle(final JSString kind, final IncomingMsgPayload payload) {
				dispatchIncoming(kind == null ? "" : kind.stringValue(), payload);
			}
		});
		// Tell the main thread we're ready to receive — main responds by
		// replaying current state (mp-state + mp-ready + mp-lobby + per-peer
		// mp-peer-connected) so we hydrate our caches even if those events
		// fired BEFORE addEventListener installed above. Without this, the
		// worker's selfId cache stays empty when netlib became ready before
		// the engine-worker booted — see WebMultiplayerCoordinator
		// .doStartAsHost which depends on selfId being populated.
		jsPostHandlerReady();
		return true;
	}

	@JSBody(params = {}, script = "self.postMessage({kind:'mp-handler-ready'});")
	private static native void jsPostHandlerReady();

	private static void dispatchIncoming(final String kind, final IncomingMsgPayload p) {
		if (listener == null) return;
		switch (kind) {
		case "mp-ready":
			cachedSelfId = p.getSelfIdOrEmpty();
			listener.onReady(cachedSelfId);
			break;
		case "mp-lobby":
			cachedCurrentLobby = p.getCodeOrEmpty();
			listener.onLobbyJoined(cachedCurrentLobby);
			break;
		case "mp-left":
			cachedCurrentLobby = "";
			listener.onLobbyLeft();
			break;
		case "mp-peer-connected": {
			// Update cachedPeerIds so PokiNetlibBridge.peerIds() reflects
			// the current set. Without this, the host's eager-attach loop
			// in WebMultiplayerCoordinator.doStartAsHost would iterate a
			// stale peer list and miss any joiners who connected AFTER the
			// initial mp-state replay — the joiners' subsequent engine
			// traffic would have no registered per-peer transport on the
			// host and get silently dropped.
			final String joined = p.getPeerIdOrEmpty();
			cachedPeerIds = appendIfAbsent(cachedPeerIds, joined);
			listener.onPeerConnected(joined);
			break;
		}
		case "mp-peer-disconnected": {
			final String left = p.getPeerIdOrEmpty();
			cachedPeerIds = removeFromArray(cachedPeerIds, left);
			listener.onPeerDisconnected(left, p.getReasonOrEmpty());
			break;
		}
		case "mp-leader":
			cachedCurrentLeader = p.getLeaderIdOrEmpty();
			listener.onLeaderChanged(cachedCurrentLeader);
			break;
		case "mp-rtc-msg": {
			final byte[] payload;
			if (p.getIsString()) {
				final String s = p.getStringValueOrEmpty();
				payload = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			}
			else {
				payload = int8ArrayToBytes(p.getBytes());
			}
			listener.onMessage(p.getPeerIdOrEmpty(), p.getChannelOrEmpty(), payload);
			break;
		}
		case "mp-error":
			listener.onError(p.getErrKindOrEmpty(), p.getMessageOrEmpty());
			break;
		case "mp-state":
			cachedSelfId        = p.getSelfIdOrEmpty();
			cachedCurrentLobby  = p.getCurrentLobbyOrEmpty();
			cachedCurrentLeader = p.getCurrentLeaderOrEmpty();
			cachedPeerIds       = peerIdsArrayToJava(p.getPeerIds());
			break;
		default:
			// Unknown message kind — ignore. Forward-compat hook so the main
			// thread can add new kinds without breaking the worker.
			break;
		}
	}

	// ---- Lobby control: stubbed on the worker side --------------------
	// The DOM overlay drives lobby host/join/leave on the main thread. The
	// engine never needs to call these from the worker. Kept as no-ops so
	// the existing API surface (called from WebMultiplayerCoordinator) compiles.

	public static void createLobby(final OnLobbyCreated onCode, final OnError onErr) {
		// Worker-side createLobby is unreachable in the current design; if
		// someone calls it, surface the architectural mismatch loudly.
		onErr.onError("createLobby unavailable in worker — call from main thread DOM overlay");
	}

	public static void joinLobby(final String code, final OnLobbyJoined onJoined, final OnError onErr) {
		onErr.onError("joinLobby unavailable in worker — call from main thread DOM overlay");
	}

	public static void leaveLobby() {
		// no-op on worker
	}

	// ---- Outgoing traffic: postMessage to main thread -----------------

	public static void sendBytesTo(final String peerId, final String channel, final byte[] bytes) {
		jsPostSendBytesTo(peerId, channel, bytesToInt8Array(bytes));
	}

	public static void broadcastBytes(final String channel, final byte[] bytes) {
		jsPostBroadcastBytes(channel, bytesToInt8Array(bytes));
	}

	public static void close() {
		jsPostClose();
	}

	// ---- State accessors: read from cache populated by main thread ---

	private static String cachedSelfId        = "";
	private static String cachedCurrentLobby  = "";
	private static String cachedCurrentLeader = "";
	private static String[] cachedPeerIds     = new String[0];

	public static String selfId()        { return cachedSelfId; }
	public static String currentLobby()  { return cachedCurrentLobby; }
	public static String currentLeader() { return cachedCurrentLeader; }
	public static int    peerCount()     { return cachedPeerIds.length; }
	public static String[] peerIds()     { return cachedPeerIds.clone(); }

	// ---- JSFunctor callback interfaces (one per arity/signature) -------

	@JSFunctor public interface OnLobbyCreated extends JSObject { void onCode(String code); }
	@JSFunctor public interface OnLobbyJoined  extends JSObject { void onJoined(String code); }
	@JSFunctor public interface OnError        extends JSObject { void onError(String reason); }

	@JSFunctor private interface IncomingMsgHandler extends JSObject {
		void handle(JSString kind, IncomingMsgPayload payload);
	}

	/** Strongly-typed view over the {@code event.data} object that the main
	 *  thread postMessages to us. Each getter has an Or-Empty variant so the
	 *  Java side stays NPE-clean even if a field is missing for that kind. */
	private interface IncomingMsgPayload extends JSObject {
		@org.teavm.jso.JSProperty("selfId")        JSString getSelfId();
		@org.teavm.jso.JSProperty("code")          JSString getCode();
		@org.teavm.jso.JSProperty("peerId")        JSString getPeerId();
		@org.teavm.jso.JSProperty("reason")        JSString getReason();
		@org.teavm.jso.JSProperty("leaderId")      JSString getLeaderId();
		@org.teavm.jso.JSProperty("channel")       JSString getChannel();
		@org.teavm.jso.JSProperty("bytes")         Int8Array getBytes();
		@org.teavm.jso.JSProperty("isString")      boolean getIsString();
		@org.teavm.jso.JSProperty("stringValue")   JSString getStringValue();
		@org.teavm.jso.JSProperty("errKind")       JSString getErrKind();
		@org.teavm.jso.JSProperty("message")       JSString getMessage();
		@org.teavm.jso.JSProperty("currentLobby")  JSString getCurrentLobby();
		@org.teavm.jso.JSProperty("currentLeader") JSString getCurrentLeader();
		@org.teavm.jso.JSProperty("peerIds")       JSArray<JSString> getPeerIds();

		default String getSelfIdOrEmpty()        { return jsStrOrEmpty(getSelfId()); }
		default String getCodeOrEmpty()          { return jsStrOrEmpty(getCode()); }
		default String getPeerIdOrEmpty()        { return jsStrOrEmpty(getPeerId()); }
		default String getReasonOrEmpty()        { return jsStrOrEmpty(getReason()); }
		default String getLeaderIdOrEmpty()      { return jsStrOrEmpty(getLeaderId()); }
		default String getChannelOrEmpty()       { return jsStrOrEmpty(getChannel()); }
		default String getStringValueOrEmpty()   { return jsStrOrEmpty(getStringValue()); }
		default String getErrKindOrEmpty()       { return jsStrOrEmpty(getErrKind()); }
		default String getMessageOrEmpty()       { return jsStrOrEmpty(getMessage()); }
		default String getCurrentLobbyOrEmpty()  { return jsStrOrEmpty(getCurrentLobby()); }
		default String getCurrentLeaderOrEmpty() { return jsStrOrEmpty(getCurrentLeader()); }
	}

	// ---- @JSBody: postMessage out, install onmessage handler in -------

	@JSBody(params = { "handler" }, script = ""
			+ "self.addEventListener('message', function(e) {"
			+ "  var d = e && e.data;"
			+ "  if (d && typeof d.kind === 'string' && d.kind.indexOf('mp-') === 0) {"
			+ "    handler(d.kind, d);"
			+ "  }"
			+ "});")
	private static native void installMessageHandler(IncomingMsgHandler handler);

	@JSBody(params = { "peerId", "channel", "bytes" },
			script = "self.postMessage({kind:'mp-rtc-send-to', peerId: peerId, channel: channel, bytes: bytes});")
	private static native void jsPostSendBytesTo(String peerId, String channel, Int8Array bytes);

	@JSBody(params = { "channel", "bytes" },
			script = "self.postMessage({kind:'mp-rtc-broadcast', channel: channel, bytes: bytes});")
	private static native void jsPostBroadcastBytes(String channel, Int8Array bytes);

	@JSBody(params = {}, script = "self.postMessage({kind:'mp-close'});")
	private static native void jsPostClose();

	// ---- Marshalling helpers --------------------------------------------

	private static String jsStrOrEmpty(final JSString s) {
		return (s == null) ? "" : s.stringValue();
	}

	private static byte[] int8ArrayToBytes(final Int8Array arr) {
		if (arr == null) { return new byte[0]; }
		final int n = arr.getLength();
		final byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			out[i] = arr.get(i);
		}
		return out;
	}

	private static Int8Array bytesToInt8Array(final byte[] bytes) {
		if (bytes == null) { return Int8Array.create(0); }
		final Int8Array arr = Int8Array.create(bytes.length);
		for (int i = 0; i < bytes.length; i++) {
			arr.set(i, bytes[i]);
		}
		return arr;
	}

	private static String[] appendIfAbsent(final String[] arr, final String value) {
		if (value == null || value.isEmpty()) return arr;
		for (final String existing : arr) {
			if (value.equals(existing)) return arr;
		}
		final String[] out = new String[arr.length + 1];
		System.arraycopy(arr, 0, out, 0, arr.length);
		out[arr.length] = value;
		return out;
	}

	private static String[] removeFromArray(final String[] arr, final String value) {
		if (value == null || value.isEmpty()) return arr;
		int idx = -1;
		for (int i = 0; i < arr.length; i++) {
			if (value.equals(arr[i])) { idx = i; break; }
		}
		if (idx < 0) return arr;
		final String[] out = new String[arr.length - 1];
		System.arraycopy(arr, 0, out, 0, idx);
		System.arraycopy(arr, idx + 1, out, idx, arr.length - idx - 1);
		return out;
	}

	private static String[] peerIdsArrayToJava(final JSArray<JSString> arr) {
		if (arr == null) return new String[0];
		final int n = arr.getLength();
		final String[] out = new String[n];
		for (int i = 0; i < n; i++) {
			out[i] = jsStrOrEmpty(arr.get(i));
		}
		return out;
	}
}
