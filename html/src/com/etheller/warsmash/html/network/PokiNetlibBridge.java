package com.etheller.warsmash.html.network;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * TeaVM bridge to {@code @poki/netlib} via the parcel-bundled
 * {@code poki-bridge.js} helper (sources in {@code html/web-src/poki-bridge.ts},
 * bundled together with @poki/netlib). The helper holds the {@code Network}
 * singleton and exposes a flat function API; this Java class just wires
 * {@code @JSBody} thunks plus typed callback interfaces.
 *
 * <p>Pattern intentionally mirrors {@link com.etheller.warsmash.html.BrowserImageBridge}
 * (jpeg-js wrapper) — the @JSBody bodies are one-liners that delegate to the
 * helper, so the JS-side debugger works naturally and the Java code stays readable.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Call {@link #init} once with a UUID game ID and a {@link Listener}
 *       implementation. Returns false if the netlib script tag wasn't loaded.</li>
 *   <li>Call {@link #createLobby} to host or {@link #joinLobby} to join.
 *       The current lobby code is reported via {@link Listener#onLobbyJoined}.</li>
 *   <li>Once {@link Listener#onPeerConnected} fires for the other side, send
 *       bytes with {@link #sendBytesTo} (point-to-point) or {@link #broadcastBytes}
 *       (to every connected peer).</li>
 *   <li>Receive bytes via {@link Listener#onMessage}.</li>
 *   <li>Optional: call {@link #close} to tear down. {@link #init} can be
 *       called again afterward — the helper script reuses its singleton slot.</li>
 * </ol>
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
	 * Channel name for ordered+reliable (TCP-like) traffic. Use for one-shot
	 * rare events where loss isn't acceptable (e.g. lobby control, map sync,
	 * end-of-game messages).
	 */
	public static final String CHANNEL_RELIABLE = "reliable";

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
	 * Wire the singleton and install all listeners. Returns false if the
	 * helper script never loaded (tag missing or 404), in which case the
	 * caller should fall back to single-player.
	 */
	public static boolean init(final String gameId, final Listener listener) {
		final Callbacks bag = JSCallbacks.create();
		bag.setReady(selfId -> listener.onReady(jsToJava(selfId)));
		bag.setLobby(code -> listener.onLobbyJoined(jsToJava(code)));
		bag.setLeft(() -> listener.onLobbyLeft());
		bag.setPeerConnected(peerId -> listener.onPeerConnected(jsToJava(peerId)));
		bag.setPeerDisconnected((peerId, reason) -> listener.onPeerDisconnected(jsToJava(peerId), jsToJava(reason)));
		bag.setLeader(leaderId -> listener.onLeaderChanged(jsToJava(leaderId)));
		bag.setMessage((peerId, channel, bytes, isString, stringValue) -> {
			final byte[] payload;
			if (isString) {
				// String fallback path — hand it to the listener as UTF-8 bytes
				// rather than introducing a separate string callback. Engine traffic
				// is binary; this branch is mostly for human-debug messages.
				final String s = jsToJava(stringValue);
				payload = (s == null) ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			}
			else {
				payload = int8ArrayToBytes(bytes);
			}
			listener.onMessage(jsToJava(peerId), jsToJava(channel), payload);
		});
		bag.setError((kind, message) -> listener.onError(jsToJava(kind), jsToJava(message)));
		return jsInit(gameId, bag);
	}

	public static void createLobby(final OnLobbyCreated onCode, final OnError onErr) {
		jsCreateLobby(code -> onCode.onCode(jsToJava(code)),
				reason -> onErr.onError(jsToJava(reason)));
	}

	public static void joinLobby(final String code, final OnLobbyJoined onJoined, final OnError onErr) {
		jsJoinLobby(code, joinedCode -> onJoined.onJoined(jsToJava(joinedCode)),
				reason -> onErr.onError(jsToJava(reason)));
	}

	public static void leaveLobby() { jsLeaveLobby(); }

	public static void sendBytesTo(final String peerId, final String channel, final byte[] bytes) {
		jsSendBytesTo(peerId, channel, bytesToInt8Array(bytes));
	}

	public static void broadcastBytes(final String channel, final byte[] bytes) {
		jsBroadcastBytes(channel, bytesToInt8Array(bytes));
	}

	public static String selfId()        { return jsToJava(jsSelfId()); }
	public static String currentLobby()  { return jsToJava(jsCurrentLobby()); }
	public static String currentLeader() { return jsToJava(jsCurrentLeader()); }
	public static int    peerCount()     { return jsPeerCount(); }

	public static String[] peerIds() {
		final JSArray<JSString> arr = jsPeerIds();
		final int n = arr.getLength();
		final String[] out = new String[n];
		for (int i = 0; i < n; i++) {
			out[i] = arr.get(i).stringValue();
		}
		return out;
	}

	public static void close() { jsClose(); }

	// ---- JSFunctor callback interfaces (one per arity/signature) -------

	@JSFunctor public interface OnLobbyCreated extends JSObject { void onCode(String code); }
	@JSFunctor public interface OnLobbyJoined  extends JSObject { void onJoined(String code); }
	@JSFunctor public interface OnError        extends JSObject { void onError(String reason); }

	@JSFunctor private interface JsStringCb     extends JSObject { void accept(JSString value); }
	@JSFunctor private interface JsVoidCb       extends JSObject { void accept(); }
	@JSFunctor private interface JsTwoStringCb  extends JSObject { void accept(JSString a, JSString b); }
	@JSFunctor private interface JsMessageCb    extends JSObject { void accept(JSString peerId, JSString channel, Int8Array bytes, boolean isString, JSString stringValue); }
	@JSFunctor private interface JsCreateOkCb   extends JSObject { void accept(JSString code); }
	@JSFunctor private interface JsCreateErrCb  extends JSObject { void accept(JSString reason); }

	// Container object passed to JS so it can hold the live callback set
	// without us having to thread eight named JSFunctor params through one
	// @JSBody. Concrete instance is built JS-side via JSCallbacks.create().
	private interface Callbacks extends JSObject {
		@org.teavm.jso.JSProperty("ready")            void setReady(JsStringCb cb);
		@org.teavm.jso.JSProperty("lobby")            void setLobby(JsStringCb cb);
		@org.teavm.jso.JSProperty("left")             void setLeft(JsVoidCb cb);
		@org.teavm.jso.JSProperty("peerConnected")    void setPeerConnected(JsStringCb cb);
		@org.teavm.jso.JSProperty("peerDisconnected") void setPeerDisconnected(JsTwoStringCb cb);
		@org.teavm.jso.JSProperty("leader")           void setLeader(JsStringCb cb);
		@org.teavm.jso.JSProperty("message")          void setMessage(JsMessageCb cb);
		@org.teavm.jso.JSProperty("error")            void setError(JsTwoStringCb cb);
	}

	private static final class JSCallbacks {
		@JSBody(params = {}, script = "return {};")
		static native Callbacks create();
	}

	// ---- @JSBody one-liners delegating into poki-bridge.js (parcel bundle) ----

	@JSBody(params = { "gameId", "callbacks" },
			script = "return self.pokiBridgeInit ? self.pokiBridgeInit(gameId, callbacks) : false;")
	private static native boolean jsInit(String gameId, Callbacks callbacks);

	@JSBody(params = { "okCb", "errCb" },
			script = "if (self.pokiBridgeCreateLobby) { self.pokiBridgeCreateLobby(okCb, errCb); } else { errCb('not-loaded'); }")
	private static native void jsCreateLobby(JsCreateOkCb okCb, JsCreateErrCb errCb);

	@JSBody(params = { "code", "okCb", "errCb" },
			script = "if (self.pokiBridgeJoinLobby) { self.pokiBridgeJoinLobby(code, okCb, errCb); } else { errCb('not-loaded'); }")
	private static native void jsJoinLobby(String code, JsCreateOkCb okCb, JsCreateErrCb errCb);

	@JSBody(params = {}, script = "if (self.pokiBridgeLeaveLobby) { self.pokiBridgeLeaveLobby(); }")
	private static native void jsLeaveLobby();

	@JSBody(params = { "peerId", "channel", "bytes" },
			script = "if (self.pokiBridgeSendBytesTo) { self.pokiBridgeSendBytesTo(peerId, channel, bytes); }")
	private static native void jsSendBytesTo(String peerId, String channel, Int8Array bytes);

	@JSBody(params = { "channel", "bytes" },
			script = "if (self.pokiBridgeBroadcastBytes) { self.pokiBridgeBroadcastBytes(channel, bytes); }")
	private static native void jsBroadcastBytes(String channel, Int8Array bytes);

	@JSBody(params = {}, script = "return self.pokiBridgeSelfId ? self.pokiBridgeSelfId() : '';")
	private static native JSString jsSelfId();

	@JSBody(params = {}, script = "return self.pokiBridgeCurrentLobby ? self.pokiBridgeCurrentLobby() : '';")
	private static native JSString jsCurrentLobby();

	@JSBody(params = {}, script = "return self.pokiBridgeCurrentLeader ? self.pokiBridgeCurrentLeader() : '';")
	private static native JSString jsCurrentLeader();

	@JSBody(params = {}, script = "return self.pokiBridgePeerCount ? self.pokiBridgePeerCount() : 0;")
	private static native int jsPeerCount();

	@JSBody(params = {}, script = "return self.pokiBridgePeerIds ? self.pokiBridgePeerIds() : [];")
	private static native JSArray<JSString> jsPeerIds();

	@JSBody(params = {}, script = "if (self.pokiBridgeClose) { self.pokiBridgeClose(); }")
	private static native void jsClose();

	// ---- Marshalling helpers --------------------------------------------

	private static String jsToJava(final JSString s) {
		return (s == null) ? null : s.stringValue();
	}

	private static String jsToJava(final String s) {
		// Some @JSFunctor lambdas already arrive as java.lang.String thanks to
		// TeaVM's auto-bridging. Keep both overloads so the call sites stay
		// readable regardless of which path TeaVM chose.
		return s;
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
}
