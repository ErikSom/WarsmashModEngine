package com.etheller.warsmash.html.network;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSString;

import com.badlogic.gdx.utils.IntIntMap;
import com.etheller.warsmash.WarsmashGdxMenuScreen;
import com.etheller.warsmash.WarsmashGdxMultiScreenGame;
import com.etheller.warsmash.networking.WarsmashClientParser;
import com.etheller.warsmash.networking.WarsmashServer;
import com.etheller.warsmash.networking.WarsmashServerParser;
import com.etheller.warsmash.viewer5.handlers.w3x.ui.MenuUI;

/**
 * Engine-side (worker-thread) multiplayer game-start coordinator. The
 * matchmaking lobby and the control message protocol (ASSIGN_SESSION,
 * START_GAME) live on the main thread now (in {@code index.html}'s overlay
 * JS); we just receive "start as host" or "start as joiner" requests via
 * postMessage, with all the slot/session-token state already resolved, and
 * drive the engine into a multiplayer match.
 *
 * <p>For the host-as-host case we additionally build the in-browser
 * {@link WarsmashServer}, attach a {@link LoopbackOrderedTransport} so the
 * host's own {@link com.etheller.warsmash.networking.WarsmashClient} can
 * reach it without going through netlib (which doesn't deliver sends to
 * self), and stage a {@link PendingHostStart} for {@link WebGameClientStarter}
 * to consume during the engine's NetworkPlatform.startNetworkGameClient call.
 *
 * <p>Threading: incoming postMessage events fire from the worker's JS
 * event loop. The actual game-start mutates engine state (MenuUI), which
 * is marshalled back to the libGDX render thread inside
 * {@link MenuUI#startMultiplayerGameDirect}.
 */
public final class WebMultiplayerCoordinator {

	private static WebMultiplayerCoordinator INSTANCE;

	public static synchronized WebMultiplayerCoordinator get() {
		if (INSTANCE == null) {
			INSTANCE = new WebMultiplayerCoordinator();
		}
		return INSTANCE;
	}

	/** Hand-off from the coordinator to {@link WebGameClientStarter} for the
	 *  host's own self-loopback. Set just before calling
	 *  {@link MenuUI#startMultiplayerGameDirect}; consumed inside
	 *  {@code WebGameClientStarter.start}. */
	public static final class PendingHostStart {
		public final LoopbackOrderedTransport.Pair loopback;
		public final WarsmashClientParser clientParser;

		PendingHostStart(final LoopbackOrderedTransport.Pair loopback, final WarsmashClientParser clientParser) {
			this.loopback = loopback;
			this.clientParser = clientParser;
		}
	}

	private static volatile PendingHostStart pendingHostStart;

	public static PendingHostStart consumePendingHostStart() {
		final PendingHostStart p = pendingHostStart;
		pendingHostStart = null;
		return p;
	}

	private WarsmashGdxMultiScreenGame game;

	/** The host's running WarsmashServer once a game has started. Null on joiners. */
	@SuppressWarnings("unused") // retained so the server isn't GC'd while a game is running
	private WarsmashServer hostServer;

	private WebMultiplayerCoordinator() {
		installEngineStartHandler(new EngineStartHandler() {
			@Override
			public void onStartAsHost(final EngineStartParams params) {
				doStartAsHost(params);
			}
			@Override
			public void onStartAsJoiner(final EngineStartParams params) {
				doStartAsJoiner(params);
			}
		});
	}

	/** Wire the engine-game reference so we can reach MenuUI later. Called
	 *  once during boot from {@code WebWarsmashGame}. */
	public void attachGame(final WarsmashGdxMultiScreenGame game) {
		this.game = game;
	}

	// ----------------------------------------------------------------
	// Start handlers — invoked by JS @JSBody when main thread postMessages
	// mp-start-as-host / mp-start-as-joiner.
	// ----------------------------------------------------------------

	private void doStartAsHost(final EngineStartParams params) {
		final String selfId = PokiNetlibBridge.selfId();
		if (selfId == null || selfId.isEmpty()) {
			System.err.println("startAsHost: no selfId from netlib yet — racing the mp-ready event?");
			return;
		}
		if (this.game == null) {
			System.err.println("startAsHost: engine not attached");
			return;
		}

		// sessionTokenToSlot rebuilt from the parallel arrays the main
		// thread sent us. Used by WarsmashServer to validate per-message
		// session tokens.
		final java.util.Map<Long, Integer> sessionTokenToSlot = new java.util.HashMap<>();
		final JSArray<JSString> tokenStrs = params.getSessionTokens();
		final JSArray<JSString> slotStrs = params.getSlots();
		final int n = tokenStrs.getLength();
		for (int i = 0; i < n; i++) {
			sessionTokenToSlot.put(parseLong(tokenStrs.get(i)), parseInt(slotStrs.get(i)));
		}

		final WarsmashServerParser serverParser = new WarsmashServerParser();
		final WebRtcOrderedServer rtcServer = new WebRtcOrderedServer(serverParser);
		final WarsmashServer server = new WarsmashServer(rtcServer, sessionTokenToSlot);
		serverParser.setListener(server);
		this.hostServer = server;

		// Eager-attach a per-peer transport for each currently-connected
		// peer (peers who connected BEFORE we created rtcServer would have
		// missed its lifecycle listener).
		for (final String peerId : PokiNetlibBridge.peerIds()) {
			rtcServer.openClientFor(peerId);
		}

		// Loopback for the host's own self-traffic. The host's WarsmashClient
		// (built later by WebGameClientStarter via NetworkPlatform during
		// MenuUI.startMultiplayerGameDirect → render-frame trigger) uses
		// loopback.clientSideForHost; the server keys host-self under
		// PeerIdSocketAddress(selfId).
		final WarsmashClientParser hostClientParser = new WarsmashClientParser();
		final LoopbackOrderedTransport.Pair loopback = LoopbackOrderedTransport.create(
				selfId, serverParser, hostClientParser);
		rtcServer.attachLoopbackEntry(selfId, loopback.serverSideForHost);

		// Stage for WebGameClientStarter.start — consumed during MenuUI's
		// later NetworkPlatform.startNetworkGameClient invocation.
		pendingHostStart = new PendingHostStart(loopback, hostClientParser);

		// Build engine-side IntIntMaps. Identity slot mapping for now.
		final IntIntMap serverSlotToMapSlot = new IntIntMap();
		final IntIntMap mapSlotToServerSlot = new IntIntMap();
		for (final Integer slot : sessionTokenToSlot.values()) {
			serverSlotToMapSlot.put(slot, slot);
			mapSlotToServerSlot.put(slot, slot);
		}

		final MenuUI menuUI = currentMenuUI();
		if (menuUI == null) {
			System.err.println("startAsHost: MenuUI not reachable; is the menu screen active?");
			return;
		}
		menuUI.startMultiplayerGameDirect(
				params.getMapPathOrEmpty(),
				parseLong(params.getMySessionToken()),
				WebGameClientStarter.encodePeerId(selfId),
				0,                          // unused on web
				parseInt(params.getMySlot()),
				serverSlotToMapSlot,
				mapSlotToServerSlot);
	}

	private void doStartAsJoiner(final EngineStartParams params) {
		if (this.game == null) {
			System.err.println("startAsJoiner: engine not attached");
			return;
		}

		final IntIntMap serverSlotToMapSlot = new IntIntMap();
		final IntIntMap mapSlotToServerSlot = new IntIntMap();
		final JSArray<JSString> slotStrs = params.getSlots();
		final int n = slotStrs.getLength();
		for (int i = 0; i < n; i++) {
			final int slot = parseInt(slotStrs.get(i));
			serverSlotToMapSlot.put(slot, slot);
			mapSlotToServerSlot.put(slot, slot);
		}

		final MenuUI menuUI = currentMenuUI();
		if (menuUI == null) {
			System.err.println("startAsJoiner: MenuUI not reachable");
			return;
		}
		menuUI.startMultiplayerGameDirect(
				params.getMapPathOrEmpty(),
				parseLong(params.getMySessionToken()),
				WebGameClientStarter.encodePeerId(params.getHostPeerIdOrEmpty()),
				0,
				parseInt(params.getMySlot()),
				serverSlotToMapSlot,
				mapSlotToServerSlot);
	}

	private MenuUI currentMenuUI() {
		if (this.game == null) return null;
		final com.badlogic.gdx.Screen screen = this.game.getScreen();
		if (screen instanceof WarsmashGdxMenuScreen) {
			return ((WarsmashGdxMenuScreen) screen).getMenuUI();
		}
		return null;
	}

	// ----------------------------------------------------------------
	// JS interop: messages of kind 'mp-start-as-host' / 'mp-start-as-joiner'
	// fire onto a separate worker message handler than the netlib bridge
	// installs (browsers happily run multiple addEventListener handlers).
	// We pass JSString rather than long for sessionToken because JS numbers
	// can't represent every long; both sides agree to encode as decimal
	// strings on the wire.
	// ----------------------------------------------------------------

	@JSFunctor private interface EngineStartHandler extends JSObject {
		void onStartAsHost(EngineStartParams params);
		// Coalesce: a single @JSFunctor can have only one method, so in
		// reality we install two handlers below. Keeping this interface
		// shape for documentation.
		void onStartAsJoiner(EngineStartParams params);
	}

	@JSFunctor private interface EngineStartCb extends JSObject {
		void call(EngineStartParams params);
	}

	private interface EngineStartParams extends JSObject {
		@org.teavm.jso.JSProperty("mapPath")        JSString getMapPath();
		@org.teavm.jso.JSProperty("hostPeerId")     JSString getHostPeerId();
		@org.teavm.jso.JSProperty("mySessionToken") JSString getMySessionToken();
		@org.teavm.jso.JSProperty("mySlot")         JSString getMySlot();
		// Parallel arrays: tokens[i] ↔ slots[i].
		@org.teavm.jso.JSProperty("sessionTokens")  JSArray<JSString> getSessionTokens();
		@org.teavm.jso.JSProperty("slots")          JSArray<JSString> getSlots();

		default String getMapPathOrEmpty()    { return jsStrOrEmpty(getMapPath()); }
		default String getHostPeerIdOrEmpty() { return jsStrOrEmpty(getHostPeerId()); }
	}

	private static void installEngineStartHandler(final EngineStartHandler handler) {
		// Two listeners, one per kind, so we don't bake a kind-switch into
		// the JS body. Symmetric with how PokiNetlibBridge dispatches.
		jsAddStartListener("mp-start-as-host", new EngineStartCb() {
			@Override public void call(final EngineStartParams p) { handler.onStartAsHost(p); }
		});
		jsAddStartListener("mp-start-as-joiner", new EngineStartCb() {
			@Override public void call(final EngineStartParams p) { handler.onStartAsJoiner(p); }
		});
	}

	@JSBody(params = { "kind", "cb" }, script = ""
			+ "self.addEventListener('message', function(e) {"
			+ "  var d = e && e.data;"
			+ "  if (d && d.kind === kind) { cb(d); }"
			+ "});")
	private static native void jsAddStartListener(String kind, EngineStartCb cb);

	@SuppressWarnings("unused")
	private static EngineStartParams emptyParams() {
		return JSObjects.create().cast();
	}

	// ---- helpers ------------------------------------------------------

	private static String jsStrOrEmpty(final JSString s) {
		return (s == null) ? "" : s.stringValue();
	}

	private static long parseLong(final JSString s) {
		final String str = jsStrOrEmpty(s);
		if (str.isEmpty()) return 0L;
		try { return Long.parseLong(str); }
		catch (final NumberFormatException e) { return 0L; }
	}

	private static int parseInt(final JSString s) {
		final String str = jsStrOrEmpty(s);
		if (str.isEmpty()) return 0;
		try { return Integer.parseInt(str); }
		catch (final NumberFormatException e) { return 0; }
	}
}
