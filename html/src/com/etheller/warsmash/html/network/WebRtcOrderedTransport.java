package com.etheller.warsmash.html.network;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import net.warsmash.networking.udp.OrderedUdpClientListener;

/**
 * Singleton router that owns the engine's view of the Poki Netlib singleton.
 * Holds the peer-id → {@link WebRtcOrderedClient} map and dispatches
 * incoming bytes / lifecycle events into the right client.
 *
 * <p>This is the seam between the netlib JS layer (one global Network with
 * N peers) and the engine's per-peer {@link OrderedUdpClient}-style world
 * (one transport object per peer). Higher-level engine code never touches
 * {@link PokiNetlibBridge} directly — it goes through this router so:
 * <ul>
 *   <li>the seq-no/replay layer sees every incoming byte;</li>
 *   <li>peer-connect/disconnect callbacks reach
 *       {@link com.etheller.warsmash.networking.NetworkPlatform.GameClientStarter}
 *       et al. without each caller having to install its own listener;</li>
 *   <li>the netlib singleton is initialised exactly once even if multiple
 *       game-client starts happen during a session.</li>
 * </ul>
 *
 * <p>Threading: every callback fires from the netlib JS event loop (main
 * thread on the web). Callers that touch engine state must marshal to the
 * libGDX render thread via {@code Gdx.app.postRunnable(...)} — this class
 * does NOT do that itself, because some consumers (e.g. lobby UI) want
 * the events synchronously.
 */
public final class WebRtcOrderedTransport {

	/**
	 * Listener for messages on the reliable channel — separate from
	 * per-peer engine traffic (which rides the unreliable channel and goes
	 * through the seq-no/replay layer). Used by
	 * {@link WebMultiplayerCoordinator} to exchange control messages
	 * (session-token assignment, start-game, map sync) outside the lockstep
	 * order stream.
	 */
	public interface ControlMessageListener {
		void onControlMessage(String peerId, byte[] bytes);
	}

	/** Listener for peer lifecycle + lobby control events. */
	public interface PeerLifecycleListener {
		default void onReady(final String selfId) { }
		default void onLobbyJoined(final String code) { }
		default void onLobbyLeft() { }
		/** Fires when a peer's WebRTC datachannels are usable. */
		default void onPeerConnected(final String peerId) { }
		default void onPeerDisconnected(final String peerId, final String reason) { }
		/** Lobby leadership changed — the leader is the de-facto host. */
		default void onLeaderChanged(final String leaderId) { }
		/** Diagnostic only; safe to ignore in production code paths. */
		default void onError(final String kind, final String message) { }
	}

	private static WebRtcOrderedTransport INSTANCE;

	/** Lazily initialised; null until {@link #ensureInitialized} runs. */
	public static synchronized WebRtcOrderedTransport get() {
		if (INSTANCE == null) {
			INSTANCE = new WebRtcOrderedTransport();
		}
		return INSTANCE;
	}

	private final Map<String, WebRtcOrderedClient> peerToClient = new HashMap<>();
	private final CopyOnWriteArrayList<PeerLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<ControlMessageListener> controlListeners = new CopyOnWriteArrayList<>();
	private boolean initialized = false;

	private WebRtcOrderedTransport() {
	}

	/**
	 * Initialise the underlying {@link PokiNetlibBridge} singleton. Idempotent —
	 * subsequent calls with the same gameId are no-ops; with a different
	 * gameId, they tear down and re-init (handy for tests, never for prod).
	 */
	public synchronized boolean ensureInitialized(final String gameId) {
		if (this.initialized) {
			return true;
		}
		final boolean ok = PokiNetlibBridge.init(gameId, new BridgeListener());
		this.initialized = ok;
		return ok;
	}

	public void addLifecycleListener(final PeerLifecycleListener l) { this.lifecycleListeners.add(l); }
	public void removeLifecycleListener(final PeerLifecycleListener l) { this.lifecycleListeners.remove(l); }

	public void addControlMessageListener(final ControlMessageListener l) { this.controlListeners.add(l); }
	public void removeControlMessageListener(final ControlMessageListener l) { this.controlListeners.remove(l); }

	/** Send a control message to a specific peer over the reliable channel. */
	public void sendControlMessageTo(final String peerId, final byte[] bytes) {
		PokiNetlibBridge.sendBytesTo(peerId, PokiNetlibBridge.CHANNEL_RELIABLE, bytes);
	}

	/** Broadcast a control message to all connected peers over the reliable channel. */
	public void broadcastControlMessage(final byte[] bytes) {
		PokiNetlibBridge.broadcastBytes(PokiNetlibBridge.CHANNEL_RELIABLE, bytes);
	}

	/**
	 * Get-or-create the per-peer transport for the given peer id. If a client
	 * already exists for this peer, the new {@code listener} is ignored —
	 * callers must agree on a single listener per peer (typically
	 * {@code WarsmashClientParser} for the joiner side, {@code WarsmashServerParser}
	 * for the host side).
	 */
	public synchronized WebRtcOrderedClient openClient(final String peerId, final OrderedUdpClientListener listener) {
		WebRtcOrderedClient existing = this.peerToClient.get(peerId);
		if (existing == null) {
			existing = new WebRtcOrderedClient(peerId, listener);
			this.peerToClient.put(peerId, existing);
		}
		return existing;
	}

	/** Currently-tracked client for the given peer id, or {@code null}. */
	public synchronized WebRtcOrderedClient getClient(final String peerId) {
		return this.peerToClient.get(peerId);
	}

	/**
	 * Drop the per-peer transport. Called when a peer disconnects so we don't
	 * route stale bytes into a parser that has already torn down.
	 */
	public synchronized void closeClient(final String peerId) {
		this.peerToClient.remove(peerId);
	}

	/** Convenience: lobby state via the bridge. */
	public String getSelfId()        { return PokiNetlibBridge.selfId(); }
	public String getCurrentLobby()  { return PokiNetlibBridge.currentLobby(); }
	public String getCurrentLeader() { return PokiNetlibBridge.currentLeader(); }

	/** Create a lobby. Wraps {@link PokiNetlibBridge#createLobby}. */
	public void createLobby(final PokiNetlibBridge.OnLobbyCreated onCode, final PokiNetlibBridge.OnError onErr) {
		PokiNetlibBridge.createLobby(onCode, onErr);
	}

	/** Join a lobby. Wraps {@link PokiNetlibBridge#joinLobby}. */
	public void joinLobby(final String code, final PokiNetlibBridge.OnLobbyJoined onJoined, final PokiNetlibBridge.OnError onErr) {
		PokiNetlibBridge.joinLobby(code, onJoined, onErr);
	}

	public void leaveLobby() {
		PokiNetlibBridge.leaveLobby();
	}

	// ----------------------------------------------------------------
	// Bridge → router glue.
	// ----------------------------------------------------------------

	private final class BridgeListener implements PokiNetlibBridge.Listener {
		@Override public void onReady(final String selfId) {
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onReady(selfId);
			}
		}
		@Override public void onLobbyJoined(final String code) {
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onLobbyJoined(code);
			}
		}
		@Override public void onLobbyLeft() {
			// All peer connections die with the lobby; clean the map so a
			// subsequent join doesn't see stale routing entries.
			synchronized (WebRtcOrderedTransport.this) {
				WebRtcOrderedTransport.this.peerToClient.clear();
			}
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onLobbyLeft();
			}
		}
		@Override public void onPeerConnected(final String peerId) {
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onPeerConnected(peerId);
			}
		}
		@Override public void onPeerDisconnected(final String peerId, final String reason) {
			synchronized (WebRtcOrderedTransport.this) {
				WebRtcOrderedTransport.this.peerToClient.remove(peerId);
			}
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onPeerDisconnected(peerId, reason);
			}
		}
		@Override public void onLeaderChanged(final String leaderId) {
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onLeaderChanged(leaderId);
			}
		}
		@Override public void onMessage(final String peerId, final String channel, final byte[] bytes) {
			// Reliable channel carries WebMultiplayerCoordinator control
			// messages (session-token assignment, start-game, map sync).
			// They bypass the seq-no/replay layer because TCP-like reliable
			// delivery makes that unnecessary.
			if (PokiNetlibBridge.CHANNEL_RELIABLE.equals(channel)) {
				for (final ControlMessageListener l : WebRtcOrderedTransport.this.controlListeners) {
					l.onControlMessage(peerId, bytes);
				}
				return;
			}
			// Unreliable channel: per-peer engine traffic. Route via the
			// per-peer OrderedUdpCommuncation so seq-no/replay can fix drops.
			final WebRtcOrderedClient client;
			synchronized (WebRtcOrderedTransport.this) {
				client = WebRtcOrderedTransport.this.peerToClient.get(peerId);
			}
			if (client == null) {
				// No app-level binding yet — drop. Common during the brief
				// window between datachannel-open and the engine wiring up
				// its parser; the seq-no layer's replay machinery will fill
				// the gap once routing is live.
				return;
			}
			client.onIncomingBytes(bytes);
		}
		@Override public void onError(final String kind, final String message) {
			for (final PeerLifecycleListener l : WebRtcOrderedTransport.this.lifecycleListeners) {
				l.onError(kind, message);
			}
		}
	}
}
