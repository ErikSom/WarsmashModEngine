package com.etheller.warsmash.networking;

import java.io.IOException;

import com.badlogic.gdx.utils.IntIntMap;
import com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer;

import java.nio.ByteBuffer;

import net.warsmash.uberserver.GamingNetworkConnection;
import net.warsmash.uberserver.GamingNetworkServerToClientListener;
import net.warsmash.uberserver.HostedGameVisibility;
import net.warsmash.uberserver.LobbyGameSpeed;
import net.warsmash.uberserver.LobbyPlayerType;

/**
 * Pluggable indirection for anything in the engine that reaches into
 * {@code java.net.*} on desktop — specifically the {@link GamingNetworkConnection}
 * TCP-client construction and the UDP-backed networked-game-client bundle that
 * {@code MenuUI} would otherwise build inline.
 *
 * <p>Same pattern as {@link com.etheller.warsmash.util.Platform} and
 * {@link com.etheller.warsmash.parsers.fdf.DynamicFontGeneratorHolderFactory}:
 * desktop installs a real implementation at startup, web installs a no-op
 * stub. The net effect is that {@code java.net.InetAddress} /
 * {@code InetSocketAddress} / {@code WarsmashClient} / {@code OrderedUdpClient}
 * never appear on the TeaVM reachability graph from {@code MenuUI} or
 * {@code WarsmashGdxMenuScreen}.
 */
public final class NetworkPlatform {
	/** Builds a {@link GamingNetworkConnection} for lobby/chat/account traffic. */
	public interface GamingNetworkConnectionFactory {
		GamingNetworkConnection create(String gateway);
	}

	/** Boots a UDP-backed game client that hands off to the map-screen layer. */
	public interface GameClientStarter {
		NetworkGameClientHandle start(byte[] hostAddress, int hostUdpPort, War3MapViewer viewer,
				long sessionToken, IntIntMap serverSlotToMapSlot) throws IOException;
	}

	private static GamingNetworkConnectionFactory gamingNetworkConnectionFactory = gateway -> new NoopGamingNetworkConnection(
			gateway);
	private static GameClientStarter gameClientStarter = (addr, port, v, s, m) -> {
		throw new UnsupportedOperationException("No NetworkPlatform.GameClientStarter installed for this backend");
	};

	private NetworkPlatform() {
	}

	public static void installGamingNetworkConnectionFactory(final GamingNetworkConnectionFactory factory) {
		gamingNetworkConnectionFactory = factory;
	}

	public static void installGameClientStarter(final GameClientStarter starter) {
		gameClientStarter = starter;
	}

	public static GamingNetworkConnection createGamingNetworkConnection(final String gateway) {
		return gamingNetworkConnectionFactory.create(gateway);
	}

	public static NetworkGameClientHandle startNetworkGameClient(final byte[] hostAddress, final int hostUdpPort,
			final War3MapViewer viewer, final long sessionToken, final IntIntMap serverSlotToMapSlot)
			throws IOException {
		return gameClientStarter.start(hostAddress, hostUdpPort, viewer, sessionToken, serverSlotToMapSlot);
	}

	/**
	 * Default fallback so the factory static field is never null. Does nothing —
	 * the backend never attempts to connect on web because the code path that
	 * would do so is gated behind a user-initiated action which the web build
	 * doesn't currently expose. Exists only to keep the classpath reachable and
	 * avoid NPEs during bootstrap.
	 */
	private static final class NoopGamingNetworkConnection implements GamingNetworkConnection {
		private final String gateway;

		NoopGamingNetworkConnection(final String gateway) {
			this.gateway = gateway;
		}

		@Override public String getGatewayString() { return this.gateway; }
		@Override public void addListener(final GamingNetworkServerToClientListener listener) { }
		@Override public void userRequestDisconnect() { }
		@Override public boolean userRequestConnect() { return false; }
		// GamingNetworkClientToServerListener no-ops:
		@Override public void handshake(final String gameId, final int version) { }
		@Override public void createAccount(final String username, final char[] passwordHash) { }
		@Override public void login(final String username, final char[] passwordHash) { }
		@Override public void joinChannel(final long sessionToken, final String channelName) { }
		@Override public void chatMessage(final long sessionToken, final String text) { }
		@Override public void emoteMessage(final long sessionToken, final String text) { }
		@Override public void queryGamesList(final long sessionToken) { }
		@Override public void queryGameInfo(final long sessionToken, final String gameName) { }
		@Override public void joinGame(final long sessionToken, final String gameName) { }
		@Override public void createGame(final long sessionToken, final String gameName, final String mapName,
				final int totalSlots, final LobbyGameSpeed gameSpeed, final HostedGameVisibility visibility,
				final long mapChecksum) { }
		@Override public void leaveGame(final long sessionToken) { }
		@Override public void uploadMapData(final long sessionToken, final int sequenceNumber, final ByteBuffer data) { }
		@Override public void mapDone(final long sessionToken, final int sequenceNumber) { }
		@Override public void requestMap(final long sessionToken) { }
		@Override public void gameLobbySetPlayerSlot(final long sessionToken, final int slot,
				final LobbyPlayerType lobbyPlayerType) { }
		@Override public void gameLobbySetPlayerRace(final long sessionToken, final int slot, final int raceItemIndex) { }
		@Override public void gameLobbyStartGame(final long sessionToken) { }
		// DisconnectListener:
		@Override public void disconnected() { }
	}
}
