package com.etheller.warsmash.networking;

import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerUnitOrderListener;

/**
 * Opaque handle returned by {@link NetworkPlatform#startNetworkGameClient} that
 * lets {@code MenuUI} finish wiring a networked-game hand-off without having
 * to reference {@code WarsmashClient} / {@code WarsmashClientWriter} directly
 * — those classes pull in {@code java.net.InetAddress} / UDP machinery that
 * isn't available under TeaVM.
 *
 * <p>Desktop's {@code NetworkPlatform} implementation returns a real handle
 * wrapping a live {@code WarsmashClient}. The web stub never returns one
 * because {@code beginGameInformation.hostInetAddress} is never populated in
 * the web boot path; it exists only to keep the classpath shape valid.
 */
public interface NetworkGameClientHandle {
	/**
	 * The order listener that should be plumbed through to the newly-created
	 * {@code WarsmashGdxMapScreen} as its UI order sink.
	 */
	CPlayerUnitOrderListener getOrderListener();

	/**
	 * Start the background network thread — mirrors
	 * {@code WarsmashClient.startThread()}. Must be called after the map
	 * screen has been attached so the simulation exists to receive orders.
	 */
	void startThread();
}
