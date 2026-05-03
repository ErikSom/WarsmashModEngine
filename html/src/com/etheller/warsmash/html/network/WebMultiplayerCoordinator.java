package com.etheller.warsmash.html.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

import com.badlogic.gdx.utils.IntIntMap;
import com.etheller.warsmash.WarsmashGdxMenuScreen;
import com.etheller.warsmash.WarsmashGdxMultiScreenGame;
import com.etheller.warsmash.networking.WarsmashClientParser;
import com.etheller.warsmash.networking.WarsmashServer;
import com.etheller.warsmash.networking.WarsmashServerParser;
import com.etheller.warsmash.viewer5.handlers.w3x.ui.MenuUI;

/**
 * Singleton owned by the engine boot layer that manages the user-visible
 * P2P lobby state machine (host/join, peer-list, game start). Sits between
 * {@link WebRtcOrderedTransport} (the per-peer transport map) and
 * {@code MenuUI}'s engine-side game-start path.
 *
 * <p>Talks to the DOM lobby overlay via globals (warsmash{Host,Join,Leave,Start}Lobby
 * + warsmashBindOn*) so the overlay doesn't need TeaVM-aware code. Talks
 * to other peers over the reliable WebRTC channel via a small binary
 * control protocol — the engine's lockstep traffic stays on the unreliable
 * channel where it's been all along.
 *
 * <p>Threading: callbacks fire from the netlib JS event loop. Game-start
 * mutations marshal back to the libGDX render thread via
 * {@code MenuUI.startMultiplayerGameDirect}'s internal
 * {@code Gdx.app.postRunnable}.
 */
public final class WebMultiplayerCoordinator {

	// ---- Control message protocol over the reliable channel -----------
	// Wire format (big-endian):
	//   1 byte  : message type
	//   payload : per-type fields
	//
	// MSG_ASSIGN_SESSION (host → joiner): 8 bytes long sessionToken,
	//                                     4 bytes int slot, 4 bytes int totalSlots,
	//                                     N×4 bytes int sessionTokenToSlot map (Long, Integer)
	// MSG_START_GAME     (host → joiner): 4 bytes int mapPath len + UTF-8 bytes
	private static final byte MSG_ASSIGN_SESSION = 0x01;
	private static final byte MSG_START_GAME     = 0x02;

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

	@JSFunctor public interface JsStringCb extends JSObject { void accept(String value); }
	@JSFunctor public interface JsTwoStringCb extends JSObject { void accept(String a, String b); }
	@JSFunctor public interface JsVoidCb extends JSObject { void accept(); }

	/** UI hooks the JS overlay binds at startup. All are nullable. */
	private JsStringCb onLobby;
	private JsStringCb onPeerConnected;
	private JsTwoStringCb onPeerDisconnected;
	private JsVoidCb onLobbyLeft;
	private JsTwoStringCb onError;
	private JsStringCb onStatus; // freeform progress messages

	/** The host's running WarsmashServer once a game has started. Null on joiners. */
	private WarsmashServer hostServer;
	/** The host's WebRtcOrderedServer transport. Null on joiners. */
	private WebRtcOrderedServer hostRtcServer;

	/** Joiner-side cache of the host's last ASSIGN_SESSION payload, used when
	 *  the START_GAME message arrives to populate
	 *  {@link MenuUI#startMultiplayerGameDirect}'s args. */
	private long joinerAssignedSessionToken = 0;
	private int joinerAssignedSlot = -1;
	/** Map of session-token → slot, broadcast by host so each joiner can
	 *  build the same {@code serverSlotToMapSlot} on their side without
	 *  needing a per-peer dialogue. */
	private final Map<Long, Integer> joinerSessionTokenToSlot = new HashMap<>();

	/** Cached reference so we can call MenuUI methods after the game has booted. */
	private WarsmashGdxMultiScreenGame game;

	private WebMultiplayerCoordinator() {
		WebRtcOrderedTransport.get().addLifecycleListener(new WebRtcOrderedTransport.PeerLifecycleListener() {
			@Override public void onLobbyJoined(final String code) {
				if (WebMultiplayerCoordinator.this.onLobby != null) WebMultiplayerCoordinator.this.onLobby.accept(code);
			}
			@Override public void onLobbyLeft() {
				if (WebMultiplayerCoordinator.this.onLobbyLeft != null) WebMultiplayerCoordinator.this.onLobbyLeft.accept();
			}
			@Override public void onPeerConnected(final String peerId) {
				if (WebMultiplayerCoordinator.this.onPeerConnected != null) WebMultiplayerCoordinator.this.onPeerConnected.accept(peerId);
			}
			@Override public void onPeerDisconnected(final String peerId, final String reason) {
				if (WebMultiplayerCoordinator.this.onPeerDisconnected != null) WebMultiplayerCoordinator.this.onPeerDisconnected.accept(peerId, reason == null ? "" : reason);
			}
			@Override public void onError(final String kind, final String message) {
				if (WebMultiplayerCoordinator.this.onError != null) WebMultiplayerCoordinator.this.onError.accept(kind, message == null ? "" : message);
			}
		});
		WebRtcOrderedTransport.get().addControlMessageListener(new WebRtcOrderedTransport.ControlMessageListener() {
			@Override public void onControlMessage(final String peerId, final byte[] bytes) {
				handleControlMessage(peerId, bytes);
			}
		});
		exposeGlobals();
	}

	/** Wire the engine-game reference so we can reach MenuUI later. Called
	 *  once during boot from {@code WebWarsmashGame}. */
	public void attachGame(final WarsmashGdxMultiScreenGame game) {
		this.game = game;
	}

	public void setOnLobby(final JsStringCb cb) { this.onLobby = cb; }
	public void setOnPeerConnected(final JsStringCb cb) { this.onPeerConnected = cb; }
	public void setOnPeerDisconnected(final JsTwoStringCb cb) { this.onPeerDisconnected = cb; }
	public void setOnLobbyLeft(final JsVoidCb cb) { this.onLobbyLeft = cb; }
	public void setOnError(final JsTwoStringCb cb) { this.onError = cb; }
	public void setOnStatus(final JsStringCb cb) { this.onStatus = cb; }

	private void emitStatus(final String text) {
		if (this.onStatus != null) this.onStatus.accept(text);
	}

	public void hostLobby(final JsStringCb okCode, final JsStringCb onErrReason) {
		WebRtcOrderedTransport.get().createLobby(
				code -> okCode.accept(code),
				reason -> onErrReason.accept(reason));
	}

	public void joinLobby(final String code, final JsStringCb okCode, final JsStringCb onErrReason) {
		WebRtcOrderedTransport.get().joinLobby(code,
				joinedCode -> okCode.accept(joinedCode),
				reason -> onErrReason.accept(reason));
	}

	public void leaveLobby() {
		WebRtcOrderedTransport.get().leaveLobby();
	}

	public String getSelfId() { return WebRtcOrderedTransport.get().getSelfId(); }
	public String getCurrentLobby() { return WebRtcOrderedTransport.get().getCurrentLobby(); }

	// ----------------------------------------------------------------
	// Host-side game start.
	// ----------------------------------------------------------------

	/**
	 * Build the in-browser {@link WarsmashServer}, stage the host's loopback
	 * client, broadcast control messages to all joiners, then trigger the
	 * host's MenuUI to start the map. {@code mapPath} must resolve in the
	 * engine's data source (the path is broadcast verbatim — joiners load
	 * the same path locally).
	 */
	public void startGameAsHost(final String mapPath) {
		final String selfId = getSelfId();
		if (selfId == null || selfId.isEmpty()) {
			emitStatus("startGameAsHost: not connected to signaling");
			return;
		}
		if (this.game == null) {
			emitStatus("startGameAsHost: engine not attached");
			return;
		}
		final List<String> peerIds = new ArrayList<>(Arrays.asList(PokiNetlibBridge.peerIds()));
		emitStatus("hosting with peers: " + peerIds);

		// Allocate slots: host = 0, joiners = 1..N. Generate session tokens.
		final Map<Long, Integer> sessionTokenToSlot = new HashMap<>();
		final long hostSessionToken = generateSessionToken();
		sessionTokenToSlot.put(hostSessionToken, 0);
		final Map<String, Long> peerToToken = new HashMap<>();
		final Map<String, Integer> peerToSlot = new HashMap<>();
		int nextSlot = 1;
		for (final String peerId : peerIds) {
			final long token = generateSessionToken();
			sessionTokenToSlot.put(token, nextSlot);
			peerToToken.put(peerId, token);
			peerToSlot.put(peerId, nextSlot);
			nextSlot++;
		}

		// Build server side: parser → server → transport, with the parser's
		// listener late-bound to break the cycle.
		final WarsmashServerParser serverParser = new WarsmashServerParser();
		final WebRtcOrderedServer rtcServer = new WebRtcOrderedServer(serverParser);
		final WarsmashServer server = new WarsmashServer(rtcServer, sessionTokenToSlot);
		serverParser.setListener(server);
		this.hostServer = server;
		this.hostRtcServer = rtcServer;

		// Eager-attach a per-peer transport for each currently-connected peer.
		// (The PeerLifecycleListener inside WebRtcOrderedServer only catches
		// FUTURE connect events; existing peers were connected before we
		// constructed it.)
		for (final String peerId : peerIds) {
			rtcServer.openClientFor(peerId);
		}

		// Build the host's loopback so the host's own WarsmashClient can
		// reach the local server without going through netlib (which doesn't
		// loop sends back to self).
		final WarsmashClientParser hostClientParser = new WarsmashClientParser();
		final LoopbackOrderedTransport.Pair loopback = LoopbackOrderedTransport.create(selfId, serverParser, hostClientParser);
		rtcServer.attachLoopbackEntry(selfId, loopback.serverSideForHost);

		// Stage the loopback for WebGameClientStarter to consume when MenuUI
		// triggers the host's NetworkPlatform.startNetworkGameClient.
		pendingHostStart = new PendingHostStart(loopback, hostClientParser);

		// Broadcast ASSIGN_SESSION + START_GAME to all peers over the
		// reliable channel. Each peer's ASSIGN_SESSION is point-to-point
		// (only that peer needs its own session token); START_GAME can
		// broadcast since the body is identical.
		for (final String peerId : peerIds) {
			final byte[] assignBytes = encodeAssignSession(peerToToken.get(peerId), peerToSlot.get(peerId), sessionTokenToSlot);
			WebRtcOrderedTransport.get().sendControlMessageTo(peerId, assignBytes);
		}
		final byte[] startBytes = encodeStartGame(mapPath);
		WebRtcOrderedTransport.get().broadcastControlMessage(startBytes);

		// Build the engine-side IntIntMaps. Identity mapping for now: server
		// slot N corresponds to map slot N.
		final IntIntMap serverSlotToMapSlot = new IntIntMap();
		final IntIntMap mapSlotToServerSlot = new IntIntMap();
		for (final Integer slot : sessionTokenToSlot.values()) {
			serverSlotToMapSlot.put(slot, slot);
			mapSlotToServerSlot.put(slot, slot);
		}

		// Trigger the host's MenuUI start path. WebGameClientStarter will
		// see hostInetAddress = encode(selfId) and switch to the loopback
		// we just staged.
		emitStatus("triggering host MenuUI startMultiplayerGameDirect");
		final MenuUI menuUI = currentMenuUI();
		if (menuUI == null) {
			emitStatus("startGameAsHost: MenuUI not reachable; is the menu screen active?");
			return;
		}
		menuUI.startMultiplayerGameDirect(
				mapPath,
				hostSessionToken,
				WebGameClientStarter.encodePeerId(selfId),
				0,            // unused on web
				0,            // host slot
				serverSlotToMapSlot,
				mapSlotToServerSlot);
	}

	// ----------------------------------------------------------------
	// Joiner-side: handle control messages, then trigger MenuUI.
	// ----------------------------------------------------------------

	private void handleControlMessage(final String peerId, final byte[] bytes) {
		if (bytes == null || bytes.length == 0) return;
		final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		final byte type = buf.get();
		switch (type) {
		case MSG_ASSIGN_SESSION:
			handleAssignSession(peerId, buf);
			break;
		case MSG_START_GAME:
			handleStartGame(peerId, buf);
			break;
		default:
			emitStatus("unknown control msg type 0x" + Integer.toHexString(type & 0xFF) + " from " + peerId);
			break;
		}
	}

	private void handleAssignSession(final String peerId, final ByteBuffer buf) {
		this.joinerAssignedSessionToken = buf.getLong();
		this.joinerAssignedSlot = buf.getInt();
		final int total = buf.getInt();
		this.joinerSessionTokenToSlot.clear();
		for (int i = 0; i < total; i++) {
			final long token = buf.getLong();
			final int slot = buf.getInt();
			this.joinerSessionTokenToSlot.put(token, slot);
		}
		emitStatus("ASSIGN_SESSION from " + peerId + ": myToken=" + this.joinerAssignedSessionToken
				+ " mySlot=" + this.joinerAssignedSlot + " totalSlots=" + total);
	}

	private void handleStartGame(final String hostPeerId, final ByteBuffer buf) {
		final int mapLen = buf.getInt();
		final byte[] mapBytes = new byte[mapLen];
		buf.get(mapBytes);
		final String mapPath = new String(mapBytes, StandardCharsets.UTF_8);
		emitStatus("START_GAME from host " + hostPeerId + ": mapPath=" + mapPath);

		if (this.joinerAssignedSlot < 0) {
			emitStatus("START_GAME received before ASSIGN_SESSION — dropping");
			return;
		}

		final IntIntMap serverSlotToMapSlot = new IntIntMap();
		final IntIntMap mapSlotToServerSlot = new IntIntMap();
		for (final Integer slot : this.joinerSessionTokenToSlot.values()) {
			serverSlotToMapSlot.put(slot, slot);
			mapSlotToServerSlot.put(slot, slot);
		}

		final MenuUI menuUI = currentMenuUI();
		if (menuUI == null) {
			emitStatus("START_GAME: MenuUI not reachable; is the menu screen active?");
			return;
		}
		menuUI.startMultiplayerGameDirect(
				mapPath,
				this.joinerAssignedSessionToken,
				WebGameClientStarter.encodePeerId(hostPeerId),
				0, // unused on web
				this.joinerAssignedSlot,
				serverSlotToMapSlot,
				mapSlotToServerSlot);
	}

	// ----------------------------------------------------------------
	// Helpers.
	// ----------------------------------------------------------------

	private MenuUI currentMenuUI() {
		if (this.game == null) return null;
		final com.badlogic.gdx.Screen screen = this.game.getScreen();
		if (screen instanceof WarsmashGdxMenuScreen) {
			return ((WarsmashGdxMenuScreen) screen).getMenuUI();
		}
		return null;
	}

	private static long generateSessionToken() {
		// Cheap monotonic + a bit of randomness so two starts in the same
		// millisecond don't collide. Server-side, the only requirement is
		// uniqueness within the lifetime of one WarsmashServer instance.
		return (System.currentTimeMillis() << 16) ^ (long) (Math.random() * 0xFFFF);
	}

	private static byte[] encodeAssignSession(final long sessionToken, final int slot,
			final Map<Long, Integer> sessionTokenToSlot) {
		final ByteBuffer buf = ByteBuffer.allocate(1 + 8 + 4 + 4 + sessionTokenToSlot.size() * (8 + 4))
				.order(ByteOrder.BIG_ENDIAN);
		buf.put(MSG_ASSIGN_SESSION);
		buf.putLong(sessionToken);
		buf.putInt(slot);
		buf.putInt(sessionTokenToSlot.size());
		for (final Map.Entry<Long, Integer> e : sessionTokenToSlot.entrySet()) {
			buf.putLong(e.getKey());
			buf.putInt(e.getValue());
		}
		buf.flip();
		final byte[] out = new byte[buf.remaining()];
		buf.get(out);
		return out;
	}

	private static byte[] encodeStartGame(final String mapPath) {
		final byte[] mapBytes = mapPath.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + mapBytes.length).order(ByteOrder.BIG_ENDIAN);
		buf.put(MSG_START_GAME);
		buf.putInt(mapBytes.length);
		buf.put(mapBytes);
		buf.flip();
		final byte[] out = new byte[buf.remaining()];
		buf.get(out);
		return out;
	}

	// ---- Expose globals for the DOM overlay ----------------------------

	private void exposeGlobals() {
		jsExposeOnHost(this::hostLobby);
		jsExposeOnJoin(this::joinLobby);
		jsExposeOnLeave(this::leaveLobby);
		jsExposeOnStartGame(this::startGameAsHost);
		jsExposeBindLobbyEvent(this::setOnLobby);
		jsExposeBindLobbyLeftEvent(this::setOnLobbyLeft);
		jsExposeBindPeerConnected(this::setOnPeerConnected);
		jsExposeBindPeerDisconnected(this::setOnPeerDisconnected);
		jsExposeBindErrorEvent(this::setOnError);
		jsExposeBindStatusEvent(this::setOnStatus);
	}

	@JSFunctor private interface HostFn extends JSObject { void call(JsStringCb okCode, JsStringCb onErrReason); }
	@JSFunctor private interface JoinFn extends JSObject { void call(String code, JsStringCb okCode, JsStringCb onErrReason); }
	@JSFunctor private interface LeaveFn extends JSObject { void call(); }
	@JSFunctor private interface StartGameFn extends JSObject { void call(String mapPath); }
	@JSFunctor private interface BindStrFn extends JSObject { void call(JsStringCb cb); }
	@JSFunctor private interface BindTwoStrFn extends JSObject { void call(JsTwoStringCb cb); }
	@JSFunctor private interface BindVoidFn extends JSObject { void call(JsVoidCb cb); }

	@JSBody(params = { "fn" }, script = "self.warsmashHostLobby = fn;")
	private static native void jsExposeOnHost(HostFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashJoinLobby = fn;")
	private static native void jsExposeOnJoin(JoinFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashLeaveLobby = fn;")
	private static native void jsExposeOnLeave(LeaveFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashStartGame = fn;")
	private static native void jsExposeOnStartGame(StartGameFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashBindOnLobby = fn;")
	private static native void jsExposeBindLobbyEvent(BindStrFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashBindOnLobbyLeft = fn;")
	private static native void jsExposeBindLobbyLeftEvent(BindVoidFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashBindOnPeerConnected = fn;")
	private static native void jsExposeBindPeerConnected(BindStrFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashBindOnPeerDisconnected = fn;")
	private static native void jsExposeBindPeerDisconnected(BindTwoStrFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashBindOnError = fn;")
	private static native void jsExposeBindErrorEvent(BindTwoStrFn fn);

	@JSBody(params = { "fn" }, script = "self.warsmashBindOnStatus = fn;")
	private static native void jsExposeBindStatusEvent(BindStrFn fn);
}
