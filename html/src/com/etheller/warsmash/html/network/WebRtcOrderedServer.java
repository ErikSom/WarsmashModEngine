package com.etheller.warsmash.html.network;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.etheller.warsmash.networking.MessageSender;

import net.warsmash.networking.udp.OrderedUdpClientListener;
import net.warsmash.networking.udp.OrderedUdpCommuncation;
import net.warsmash.networking.udp.OrderedUdpServerListener;

/**
 * Web-side analog of {@link net.warsmash.networking.udp.OrderedUdpServer}:
 * the host-end of a Poki Netlib lobby, holding one
 * {@link WebRtcOrderedClient} per joined peer and routing inbound bytes
 * through the engine's existing {@link WarsmashServerParser} (which expects
 * {@code parse(SocketAddress, ByteBuffer)} and produces
 * {@link com.etheller.warsmash.networking.WarsmashServer} dispatches).
 *
 * <p>How it stitches together with the rest of the network plumbing:
 * <ol>
 *   <li>Created by the host with a {@link WarsmashServerParser} (which is
 *       wired back to a {@link com.etheller.warsmash.networking.WarsmashServer}).
 *       The constructor registers a {@link WebRtcOrderedTransport.PeerLifecycleListener}
 *       so connection events automatically materialise per-peer transports.</li>
 *   <li>When a peer connects, this class creates a
 *       {@link WebRtcOrderedClient} for that peer. The client's per-peer
 *       {@link OrderedUdpClientListener} is a tiny adapter that re-frames
 *       {@code parse(ByteBuffer)} calls into the server's
 *       {@code parse(SocketAddress, ByteBuffer)} using a
 *       {@link PeerIdSocketAddress}.</li>
 *   <li>{@link #send} (called via {@link com.etheller.warsmash.networking.WarsmashServerWriter})
 *       looks up the right per-peer transport by destination address and
 *       hands off the buffer.</li>
 * </ol>
 *
 * <p>Threading: all callbacks run on the netlib JS event loop (main thread
 * on the web). Engine-state mutations downstream of these callbacks must
 * marshal back to the libGDX render thread — the engine already does this
 * inside {@code WarsmashServer} dispatches via {@code Gdx.app.postRunnable}.
 */
public final class WebRtcOrderedServer implements MessageSender {

	private final WarsmashServerParserShim parser;
	// Map widened from WebRtcOrderedClient to its parent OrderedUdpCommuncation
	// so the host's own loopback entry (a paired in-memory comm, NOT a
	// WebRtcOrderedClient) can sit alongside remote-peer entries. The send()
	// path doesn't care about the concrete type — just that .send(buf) goes
	// somewhere sensible.
	private final Map<String, OrderedUdpCommuncation> peerToTransport = new HashMap<>();

	public WebRtcOrderedServer(final OrderedUdpServerListener parser) {
		this.parser = new WarsmashServerParserShim(parser);
		WebRtcOrderedTransport.get().addLifecycleListener(new WebRtcOrderedTransport.PeerLifecycleListener() {
			@Override
			public void onPeerConnected(final String peerId) {
				openClientFor(peerId);
			}

			@Override
			public void onPeerDisconnected(final String peerId, final String reason) {
				closeClientFor(peerId);
			}
		});
	}

	/** Force-create a peer transport for {@code peerId}. Idempotent. */
	public synchronized void openClientFor(final String peerId) {
		if (this.peerToTransport.containsKey(peerId)) {
			return;
		}
		final PeerIdSocketAddress addr = new PeerIdSocketAddress(peerId);
		final OrderedUdpClientListener perPeerListener = new OrderedUdpClientListener() {
			@Override
			public void parse(final ByteBuffer buffer) {
				WebRtcOrderedServer.this.parser.parse(addr, buffer);
			}

			@Override
			public void cantReplay(final int seqNo) {
				WebRtcOrderedServer.this.parser.cantReplay(addr, seqNo);
			}
		};
		final WebRtcOrderedClient client = WebRtcOrderedTransport.get().openClient(peerId, perPeerListener);
		this.peerToTransport.put(peerId, client);
	}

	/**
	 * Attach a pre-built loopback transport for the host's own self-traffic.
	 * Must be called BEFORE the host kicks off its own WarsmashClient
	 * joinGame, since the server needs the entry in place to receive the
	 * host's "I joined" packet.
	 *
	 * @param peerId the host's own netlib peer id (matches the
	 *               PeerIdSocketAddress the loopback was constructed with)
	 * @param loopbackServerSide
	 *               the {@code serverSideForHost} field from a
	 *               {@link LoopbackOrderedTransport.Pair}
	 */
	public synchronized void attachLoopbackEntry(final String peerId, final OrderedUdpCommuncation loopbackServerSide) {
		this.peerToTransport.put(peerId, loopbackServerSide);
	}

	public synchronized void closeClientFor(final String peerId) {
		this.peerToTransport.remove(peerId);
		WebRtcOrderedTransport.get().closeClient(peerId);
	}

	/** Iterate connected peer ids — useful for the host to bootstrap "all-known-addresses" sets. */
	public synchronized java.util.Set<String> getKnownPeerIds() {
		return new java.util.HashSet<>(this.peerToTransport.keySet());
	}

	// ---- MessageSender -----------------------------------------------

	@Override
	public synchronized void send(final Object destination, final ByteBuffer buffer) throws IOException {
		final String peerId;
		if (destination instanceof PeerIdSocketAddress) {
			peerId = ((PeerIdSocketAddress) destination).getPeerId();
		}
		else {
			// Defensive: WarsmashServerWriter only ever sends to addresses
			// it received from a previous parse() call, which always come
			// from openClientFor — so the cast above should always succeed.
			// If it doesn't, something handed us a foreign address; drop.
			System.err.println("WebRtcOrderedServer.send: ignoring non-PeerIdSocketAddress destination: " + destination);
			return;
		}
		final OrderedUdpCommuncation transport = this.peerToTransport.get(peerId);
		if (transport == null) {
			// Peer disconnected between the engine deciding to send and us
			// actually sending. Silent drop is correct — UDP semantics.
			return;
		}
		transport.send(buffer);
	}

	// ---- Parser shim -------------------------------------------------

	/** Adapter that lets us call the OrderedUdpServerListener from inside a
	 *  per-peer OrderedUdpClientListener wrapper. */
	private static final class WarsmashServerParserShim {
		private final OrderedUdpServerListener delegate;

		WarsmashServerParserShim(final OrderedUdpServerListener delegate) {
			this.delegate = delegate;
		}

		void parse(final Object addr, final ByteBuffer buf) {
			this.delegate.parse(addr, buf);
		}

		void cantReplay(final Object addr, final int seqNo) {
			this.delegate.cantReplay(addr, seqNo);
		}
	}
}
