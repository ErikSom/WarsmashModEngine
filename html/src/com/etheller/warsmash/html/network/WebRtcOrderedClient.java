package com.etheller.warsmash.html.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.warsmash.networking.udp.OrderedUdpClientListener;
import net.warsmash.networking.udp.OrderedUdpCommuncation;

/**
 * Per-peer transport adapter that plugs a Poki Netlib WebRTC datachannel in
 * underneath the engine's existing {@link OrderedUdpCommuncation} sequence/
 * replay logic. One instance corresponds to ONE remote peer (identified by
 * its netlib peer id).
 *
 * <p>The lockstep wire protocol (defined by {@code WarsmashClientWriter} /
 * {@code WarsmashClientParser} on the engine side) is unchanged — the bytes
 * we hand to {@link OrderedUdpCommuncation#send(ByteBuffer)} get prefixed
 * with the same seq-no header that desktop UDP uses, then delivered over
 * the WebRTC unreliable channel ({@code ordered: true, maxRetransmits: 0})
 * which has UDP-like loss semantics. The replay-request layer
 * recovers from drops the same way it does for native UDP. Net effect: the
 * engine's lockstep machinery doesn't care that the underlying transport is
 * WebRTC instead of a {@code DatagramChannel}.
 *
 * <p>Direction-agnostic: a joining peer creates ONE of these for the host;
 * a hosting peer creates ONE per joiner. Routing of incoming bytes is the
 * job of {@link WebRtcOrderedTransport}, which dispatches
 * {@link PokiNetlibBridge.Listener#onMessage} into {@link #parse} on the
 * matching client by peer id.
 *
 * <p><b>No background thread.</b> Native {@code OrderedUdpClient} is
 * {@link Runnable} because it owns a blocking {@code DatagramChannel.receive()}
 * loop. WebRTC is event-driven — netlib calls our message listener whenever
 * a datachannel message arrives. The receive path is therefore implicit
 * (we don't need a thread); senders just call {@link #send}.
 */
public final class WebRtcOrderedClient extends OrderedUdpCommuncation {

	/** netlib peer id of the remote endpoint this client talks to. */
	private final String peerId;
	/** WebRTC datachannel label — see {@link PokiNetlibBridge#CHANNEL_UNRELIABLE}. */
	private final String channel;

	public WebRtcOrderedClient(final String peerId, final OrderedUdpClientListener appListener) {
		super(appListener);
		this.peerId = peerId;
		this.channel = PokiNetlibBridge.CHANNEL_UNRELIABLE;
	}

	public String getPeerId() {
		return this.peerId;
	}

	/**
	 * Called by {@link WebRtcOrderedTransport} when netlib delivers a message
	 * tagged for this peer. Wraps the bytes in a {@link ByteBuffer} (BIG_ENDIAN,
	 * matching the desktop wire) and feeds them through the inherited
	 * {@link OrderedUdpCommuncation#parse(ByteBuffer)} so the seq-no/replay
	 * layer sees them before the application-level {@link OrderedUdpClientListener}.
	 */
	public void onIncomingBytes(final byte[] bytes) {
		if ((bytes == null) || (bytes.length == 0)) {
			return;
		}
		final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		parse(buf);
	}

	/**
	 * Called by {@link OrderedUdpCommuncation#send} after it stamps the seq-no
	 * header. Forwards the framed bytes to netlib's per-peer datachannel.
	 *
	 * <p>Netlib's send is fire-and-forget — drops on closed channel or unknown
	 * peer are silent. That's OK: the receiving side's
	 * {@link OrderedUdpCommuncation} will detect the gap and request replay
	 * via {@link OrderedUdpCommuncation#parse}.
	 */
	@Override
	protected void trySend(final ByteBuffer data) {
		final int len = data.remaining();
		if (len == 0) {
			return;
		}
		// Defensive copy: the underlying buffer is reused by the seq-no layer
		// across consecutive sends; netlib serialises the bytes asynchronously
		// (the RTCDataChannel may queue them). Copying here guarantees the
		// bytes we hand off can't be mutated by a subsequent send before the
		// browser has flushed them to the wire.
		final byte[] copy = new byte[len];
		final ByteBuffer dup = data.duplicate();
		dup.get(copy);
		PokiNetlibBridge.sendBytesTo(this.peerId, this.channel, copy);
	}
}
