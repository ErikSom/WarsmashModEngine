package com.etheller.warsmash.html.network;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.warsmash.networking.udp.OrderedUdpClientListener;
import net.warsmash.networking.udp.OrderedUdpCommuncation;
import net.warsmash.networking.udp.OrderedUdpServerListener;

/**
 * In-memory transport pair for the host's own self-traffic. The host runs
 * both {@link com.etheller.warsmash.networking.WarsmashServer} (the
 * lockstep authority) and {@link com.etheller.warsmash.networking.WarsmashClient}
 * (the host's own player view) in the same browser. They communicate via
 * this loopback rather than going through WebRTC: netlib doesn't deliver
 * {@code Network.send(channel, selfId, ...)} messages back to the sender,
 * and routing through the network stack just to reach yourself wastes a
 * round trip.
 *
 * <p>The pair preserves the engine's seq-no/replay layer end-to-end: each
 * side is a real {@link OrderedUdpCommuncation}, so the wire format is
 * identical to what flows over WebRTC for the other peers. Every byte the
 * host's WarsmashClient sends is stamped with a seq-no by its side, the
 * other side's parse strips the header before dispatching inner bytes to
 * the application parser. Net effect: the host's own player goes through
 * the same protocol stack as a remote joiner, just with the network hop
 * replaced by a direct method call.
 *
 * <p>Each pair is associated with a stable {@link PeerIdSocketAddress} so
 * the host's {@link com.etheller.warsmash.networking.WarsmashServer} can
 * key its per-client state on the host the same way it does for remote
 * peers (via {@code socketAddressesKnown}, the
 * {@code clientToTurnFinished} map, etc.).
 */
public final class LoopbackOrderedTransport {

	/** Two paired {@link OrderedUdpCommuncation} instances. The fields are
	 *  named from the perspective of which side OWNS each instance:
	 *  {@link #serverSideForHost} sits in {@code WebRtcOrderedServer}'s
	 *  per-peer map keyed on the host's own {@link PeerIdSocketAddress};
	 *  {@link #clientSideForHost} is the transport the host's
	 *  {@link com.etheller.warsmash.networking.WarsmashClient} uses. */
	public static final class Pair {
		public final Object hostSelfAddress;
		public final OrderedUdpCommuncation serverSideForHost;
		public final OrderedUdpCommuncation clientSideForHost;

		Pair(final Object hostSelfAddress, final OrderedUdpCommuncation serverSideForHost,
				final OrderedUdpCommuncation clientSideForHost) {
			this.hostSelfAddress = hostSelfAddress;
			this.serverSideForHost = serverSideForHost;
			this.clientSideForHost = clientSideForHost;
		}
	}

	/**
	 * Build the paired transports.
	 *
	 * @param hostSelfPeerId  The host's own netlib peer id, used as the key
	 *                        for the host's own entry in the server's
	 *                        per-peer map. Must match what netlib reports
	 *                        as {@code Network.id} on the host.
	 * @param serverParser    The host's
	 *                        {@link com.etheller.warsmash.networking.WarsmashServerParser}
	 *                        — receives stripped inner bytes from the host's
	 *                        own WarsmashClient.
	 * @param clientParser    The host's
	 *                        {@link com.etheller.warsmash.networking.WarsmashClientParser}
	 *                        — receives stripped inner bytes from the host's
	 *                        own WarsmashServer.
	 */
	public static Pair create(final String hostSelfPeerId, final OrderedUdpServerListener serverParser,
			final OrderedUdpClientListener clientParser) {
		final Object hostSelfAddress = new PeerIdSocketAddress(hostSelfPeerId);

		// Each side needs to know the OTHER side at construction time so
		// trySend can call the other's parse(). Use a one-element array as
		// a "late-bound" reference holder — same pattern Java code uses
		// elsewhere to break constructor cycles.
		final OrderedUdpCommuncation[] clientSideHolder = new OrderedUdpCommuncation[1];

		// Server-side entry: lives inside WebRtcOrderedServer's peer map.
		// Its delegate (OrderedUdpClientListener) is an adapter that re-frames
		// the per-peer parse(buf) into the server's parse(addr, buf).
		final OrderedUdpCommuncation serverSide = new OrderedUdpCommuncation(new OrderedUdpClientListener() {
			@Override
			public void parse(final ByteBuffer buffer) {
				serverParser.parse(hostSelfAddress, buffer);
			}

			@Override
			public void cantReplay(final int seqNo) {
				serverParser.cantReplay(hostSelfAddress, seqNo);
			}
		}) {
			@Override
			protected void trySend(final ByteBuffer data) {
				// Server is sending to host-self: push wire bytes (with
				// seq-no header) into the client side's seq-no layer.
				if (clientSideHolder[0] == null) return;
				final byte[] copy = new byte[data.remaining()];
				data.duplicate().get(copy);
				clientSideHolder[0].parse(ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN));
			}
		};

		// Client-side: the host's WarsmashClient's transport. Its delegate
		// is the host's WarsmashClientParser. trySend pushes wire bytes
		// into the server-side's seq-no layer.
		final OrderedUdpCommuncation clientSide = new OrderedUdpCommuncation(clientParser) {
			@Override
			protected void trySend(final ByteBuffer data) {
				final byte[] copy = new byte[data.remaining()];
				data.duplicate().get(copy);
				serverSide.parse(ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN));
			}
		};
		clientSideHolder[0] = clientSide;

		return new Pair(hostSelfAddress, serverSide, clientSide);
	}

	private LoopbackOrderedTransport() {
	}
}
