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
import com.etheller.warsmash.networking.MultiplayerLobbyConfig;
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
				mapSlotToServerSlot,
				lobbyConfigFromParams(params));
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
				mapSlotToServerSlot,
				lobbyConfigFromParams(params));
	}

	/** Build a {@link MultiplayerLobbyConfig} from the parallel slot-config
	 *  arrays in the postMessage payload. Each array is parallel: index i
	 *  describes the same slot across all six. Empty/missing arrays just
	 *  produce an empty MultiplayerLobbyConfig — the engine then falls
	 *  through to map defaults. */
	private static MultiplayerLobbyConfig lobbyConfigFromParams(final EngineStartParams params) {
		final IntIntMap types     = new IntIntMap();
		final IntIntMap races     = new IntIntMap();
		final IntIntMap colors    = new IntIntMap();
		final IntIntMap teams     = new IntIntMap();
		final IntIntMap handicaps = new IntIntMap();
		final boolean fps = "true".equals(jsStrOrEmpty(params.getFixedPlayerSettings()));
		final JSArray<JSString> idxArr = params.getSlotConfigIndexes();
		if (idxArr == null) {
			return new MultiplayerLobbyConfig(types, races, colors, teams, handicaps, fps);
		}
		final JSArray<JSString> typeArr  = params.getSlotConfigTypes();
		final JSArray<JSString> raceArr  = params.getSlotConfigRaces();
		final JSArray<JSString> colorArr = params.getSlotConfigColors();
		final JSArray<JSString> teamArr  = params.getSlotConfigTeams();
		final JSArray<JSString> handArr  = params.getSlotConfigHandicaps();
		final int n = idxArr.getLength();
		for (int i = 0; i < n; i++) {
			final int slotIdx = parseInt(idxArr.get(i));
			if (typeArr != null && i < typeArr.getLength()) {
				final String t = jsStrOrEmpty(typeArr.get(i));
				types.put(slotIdx, "closed".equals(t)
						? MultiplayerLobbyConfig.SLOT_TYPE_CLOSED
						: MultiplayerLobbyConfig.SLOT_TYPE_OPEN);
			}
			if (raceArr  != null && i < raceArr.getLength())  races.put(slotIdx, parseInt(raceArr.get(i)));
			if (colorArr != null && i < colorArr.getLength()) colors.put(slotIdx, parseInt(colorArr.get(i)));
			if (teamArr  != null && i < teamArr.getLength())  teams.put(slotIdx, parseInt(teamArr.get(i)));
			if (handArr  != null && i < handArr.getLength())  handicaps.put(slotIdx, parseInt(handArr.get(i)));
		}
		return new MultiplayerLobbyConfig(types, races, colors, teams, handicaps, fps);
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
		// Slot-config parallel arrays — all length-equal, indexed
		// by the same slot id position.
		@org.teavm.jso.JSProperty("slotConfigIndexes")   JSArray<JSString> getSlotConfigIndexes();
		@org.teavm.jso.JSProperty("slotConfigTypes")     JSArray<JSString> getSlotConfigTypes();
		@org.teavm.jso.JSProperty("slotConfigRaces")     JSArray<JSString> getSlotConfigRaces();
		@org.teavm.jso.JSProperty("slotConfigColors")    JSArray<JSString> getSlotConfigColors();
		@org.teavm.jso.JSProperty("slotConfigTeams")     JSArray<JSString> getSlotConfigTeams();
		@org.teavm.jso.JSProperty("slotConfigHandicaps") JSArray<JSString> getSlotConfigHandicaps();
		@org.teavm.jso.JSProperty("fixedPlayerSettings") JSString getFixedPlayerSettings();

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
		// Debug-only: manual desync trigger. Main thread exposes
		// window.desyncTest(); calling it postMessages the worker, which
		// mutates a single unit's hp by +1.0 — locally only, so the next
		// state-hash report after this fires will diverge from the other
		// client(s). Use to verify the desync-detection scaffolding works.
		jsAddStartListener("mp-debug-desync", new EngineStartCb() {
			@Override public void call(final EngineStartParams p) {
				if (INSTANCE != null) INSTANCE.triggerDebugDesync();
			}
		});
	}

	private void triggerDebugDesync() {
		if (this.game == null) {
			System.err.println("[debug] desyncTest: engine not attached");
			return;
		}
		final com.badlogic.gdx.Screen screen = this.game.getScreen();
		if (!(screen instanceof com.etheller.warsmash.WarsmashGdxMapScreen)) {
			System.err.println("[debug] desyncTest: not in a multiplayer map yet (screen="
					+ (screen == null ? "null" : screen.getClass().getSimpleName()) + ")");
			return;
		}
		final com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer viewer =
				((com.etheller.warsmash.WarsmashGdxMapScreen) screen).getViewer();
		if (viewer == null || viewer.simulation == null) {
			System.err.println("[debug] desyncTest: no live simulation");
			return;
		}
		// Bump the simulation's debug salt — mixed into computeStateHash
		// directly, so the very next state-hash report at the 30-turn
		// boundary will diverge from other clients regardless of what's
		// happening in the game (no need to wait for combat / RNG usage).
		// Pure non-invasive: doesn't touch JASS, doesn't fire engine
		// events, doesn't mutate any real game state — only the
		// debug-only hash salt that's always 0 in production paths.
		viewer.simulation.debugBumpDesyncSalt();
		System.err.println("[debug] desyncTest: bumped debugDesyncSalt on this client only; "
				+ "expect DESYNC line on host at next state-hash turn (every 30 turns ≈ 7s)");
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
