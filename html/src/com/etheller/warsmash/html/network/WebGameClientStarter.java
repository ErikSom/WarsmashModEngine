package com.etheller.warsmash.html.network;

import java.nio.charset.StandardCharsets;

import com.badlogic.gdx.utils.IntIntMap;
import com.etheller.warsmash.networking.NetworkGameClientHandle;
import com.etheller.warsmash.networking.NetworkPlatform;
import com.etheller.warsmash.networking.WarsmashClient;
import com.etheller.warsmash.networking.WarsmashClientParser;
import com.etheller.warsmash.networking.WarsmashClientSendingOrderListener;
import com.etheller.warsmash.networking.WarsmashClientWriter;
import com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer;

import net.warsmash.networking.udp.OrderedUdpCommuncation;

/**
 * Web-side {@link NetworkPlatform.GameClientStarter} — joiner half of the
 * P2P plumbing. Mirrors the desktop installer in {@code DesktopLauncher}
 * but constructs a {@link WarsmashClient} over a
 * {@link WebRtcOrderedClient} instead of an {@code OrderedUdpClient}.
 *
 * <p><b>How the {@code byte[] hostAddress} arg is reinterpreted on web.</b>
 * On desktop {@code hostAddress} is a 4- or 16-byte IPv4/IPv6 address handed
 * to {@code InetAddress.getByAddress(...)}. On web the field has no IP
 * meaning — instead the lobby UI populates it with the netlib peer id of the
 * host, encoded as UTF-8 bytes. {@code hostUdpPort} is unused on web (the
 * single WebRTC datachannel obviates ports). Both engine fields stay typed
 * as bytes/int so {@code MenuUI} doesn't need a web-vs-desktop split.
 */
public final class WebGameClientStarter implements NetworkPlatform.GameClientStarter {

	/** Convenience: pack a peer id into the engine's {@code hostInetAddress} byte[]. */
	public static byte[] encodePeerId(final String peerId) {
		return peerId.getBytes(StandardCharsets.UTF_8);
	}

	/** Inverse of {@link #encodePeerId}. */
	public static String decodePeerId(final byte[] hostAddressBytes) {
		return new String(hostAddressBytes, StandardCharsets.UTF_8);
	}

	@Override
	public NetworkGameClientHandle start(final byte[] hostAddress, final int hostUdpPort, final War3MapViewer viewer,
			final long sessionToken, final IntIntMap serverSlotToMapSlot) {
		final String hostPeerId = decodePeerId(hostAddress);

		// Break the WarsmashClient ↔ parser ↔ transport cycle as documented
		// on WarsmashClient's transport-injection constructor: build the
		// parser empty, register it as the per-peer listener so the transport
		// can route incoming bytes, then late-bind the client.
		final WarsmashClientParser parser = new WarsmashClientParser();

		// Two transport variants depending on whether this is the host's
		// own self-connection or a remote join:
		//
		// 1. Host case: hostPeerId == our own netlib id. WebRTC doesn't
		//    deliver a peer's send to itself, so we route through the
		//    in-memory loopback that WebMultiplayerCoordinator pre-installs
		//    immediately before triggering MenuUI's start path. The
		//    coordinator clears the slot once we've consumed it so a later
		//    re-host doesn't reuse a stale loopback.
		// 2. Remote-joiner case: standard WebRTC datachannel via netlib.
		// Two transport variants depending on whether this is the host's
		// own self-connection or a remote join. The coordinator stages a
		// PendingHostStart immediately before triggering MenuUI on the host
		// path; we consume it here and use its loopback transport. The
		// "consume" semantic ensures a later re-host can't accidentally
		// reuse a stale loopback.
		final OrderedUdpCommuncation transport;
		final WarsmashClientParser usedParser;
		final boolean isHostSelf = hostPeerId.equals(WebRtcOrderedTransport.get().getSelfId());
		if (isHostSelf) {
			final WebMultiplayerCoordinator.PendingHostStart pending = WebMultiplayerCoordinator.consumePendingHostStart();
			if (pending == null) {
				throw new IllegalStateException("Host self-start triggered but no PendingHostStart was registered on the coordinator");
			}
			transport = pending.loopback.clientSideForHost;
			usedParser = pending.clientParser;
		}
		else {
			transport = WebRtcOrderedTransport.get().openClient(hostPeerId, parser);
			usedParser = parser;
		}

		final WarsmashClient client = new WarsmashClient(transport, usedParser, viewer, sessionToken, serverSlotToMapSlot);
		usedParser.setListener(client);

		final WarsmashClientWriter writer = client.getWriter();
		writer.joinGame();
		writer.send();

		final WarsmashClientSendingOrderListener orderListener = new WarsmashClientSendingOrderListener(writer);
		return new NetworkGameClientHandle() {
			@Override
			public com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerUnitOrderListener getOrderListener() {
				return orderListener;
			}

			@Override
			public void startThread() {
				// No-op for WebRTC — incoming bytes are pushed by netlib
				// callbacks, not pulled by a blocking receive loop.
				// WarsmashClient.startThread() also instanceof-guards this,
				// so calling it would still be safe; we elide for clarity.
			}
		};
	}
}
