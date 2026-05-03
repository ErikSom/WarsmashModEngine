package com.etheller.warsmash.networking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import net.warsmash.networking.udp.OrderedUdpServer;
import net.warsmash.uberserver.GamingNetwork;

public class WarsmashServer implements ClientToServerListener {
	private static final boolean VERBOSE_LOGGING = false;
	private static final int MAGIC_DELAY_OFFSET = 4; // 4
	// Field type widened to MessageSender so non-UDP transports can plug in.
	// The "address" type used by the writer / per-client tracking is Object —
	// see the rationale on UdpServerListener.parse: keeps java.net.SocketAddress
	// out of the TeaVM web reachability graph. Concrete types under the hood
	// are still SocketAddress on desktop and a custom marker on web; the
	// engine only treats them as opaque map keys.
	private final MessageSender transport;
	private final Set<Object> socketAddressesKnown = new HashSet<>();
	private final Map<Long, Integer> sessionTokenToPermittedSlot;
	private final Map<Object, Integer> clientToTurnFinished = new HashMap<>();
	private final List<Runnable> turnActions = new ArrayList<>();
	private final WarsmashServerWriter writer;
	private int currentTurnTick = MAGIC_DELAY_OFFSET;
	private boolean gameStarted = false;
	private long lastServerHeartbeatTime = 0;
	private int joinCount = 0;

	/**
	 * Desktop convenience constructor — opens a UDP socket on {@code port} and
	 * wires it to a {@link WarsmashServerParser} that delegates back to this
	 * server. Pulls {@code java.net.*} into the reachability graph; do NOT call
	 * this from the web build — use the transport-injection constructor below.
	 */
	public WarsmashServer(final int port, final Map<Long, Integer> sessionTokenToPermittedSlot) throws IOException {
		final OrderedUdpServer udp = new OrderedUdpServer(port, new WarsmashServerParser(this));
		this.transport = udp;
		this.writer = new WarsmashServerWriter(udp, this.socketAddressesKnown);
		this.sessionTokenToPermittedSlot = sessionTokenToPermittedSlot;
	}

	/**
	 * Transport-injection constructor for non-UDP backends (currently the web
	 * build's {@code WebRtcOrderedServer}). The caller is responsible for
	 * breaking the construction cycle:
	 * <pre>
	 *   WarsmashServerParser parser = new WarsmashServerParser();
	 *   WebRtcOrderedServer transport = new WebRtcOrderedServer(parser);
	 *   WarsmashServer server = new WarsmashServer(transport, sessionTokenToPermittedSlot);
	 *   parser.setListener(server);
	 * </pre>
	 */
	public WarsmashServer(final MessageSender transport, final Map<Long, Integer> sessionTokenToPermittedSlot) {
		this.transport = transport;
		this.writer = new WarsmashServerWriter(transport, this.socketAddressesKnown);
		this.sessionTokenToPermittedSlot = sessionTokenToPermittedSlot;
	}

	// Useful for if they pass 0 as port and get an auto-assigned one.
	// Only meaningful for UDP transports — returns -1 for transports that
	// don't bind a port (e.g. WebRTC, where peers are addressed by netlib id).
	public int getPort() {
		return (this.transport instanceof OrderedUdpServer) ? ((OrderedUdpServer) this.transport).getPort() : -1;
	}

	// Only meaningful for UDP transports — returns null for transports that
	// don't have an OS-level local address.
	public InetSocketAddress getLocalAddress() {
		return (this.transport instanceof OrderedUdpServer) ? ((OrderedUdpServer) this.transport).getLocalAddress() : null;
	}

	/**
	 * Start the background receive loop for transports that need one (i.e.
	 * desktop's {@link OrderedUdpServer}). Event-driven transports (WebRTC
	 * datachannels are pushed to us by the browser) make this a no-op.
	 */
	public void startThread() {
		if (this.transport instanceof Runnable) {
			new Thread((Runnable) this.transport).start();
		}
	}

	public void startGame() {
		this.gameStarted = true;
		WarsmashServer.this.writer.startGame();
		WarsmashServer.this.writer.send();
		startTurn();
	}

	private void startTurn() {
		System.out.println("sending finishedTurn " + this.currentTurnTick);
		WarsmashServer.this.writer.finishedTurn(this.currentTurnTick);
		WarsmashServer.this.writer.send();
		this.currentTurnTick++;
	}

	private int getPlayerIndex(final Object sourceAddress, final long sessionToken) {
		final Integer permittedSlot = this.sessionTokenToPermittedSlot.get(sessionToken);
		if (permittedSlot != null) {
			this.socketAddressesKnown.add(sourceAddress);
			return permittedSlot;
		}
		System.err.println("received bad session token during game: " + sessionToken);
		return -1;
	}

	@Override
	public void joinGame(final Object sourceAddress, final long sessionToken) {
		System.out.println("joinGame " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		WarsmashServer.this.writer.acceptJoin(playerIndex);
		WarsmashServer.this.writer.send(sourceAddress);

		this.joinCount++;
		if (this.joinCount == this.sessionTokenToPermittedSlot.size()) {
			startGame();
		}
	}

	@Override
	public void issueTargetOrder(final Object sourceAddress, final long sessionToken, final int unitHandleId,
			final int abilityHandleId, final int orderId, final int targetHandleId, final boolean queue) {
		System.out.println("issueTargetOrder from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.issueTargetOrder(playerIndex, unitHandleId, abilityHandleId, orderId,
						targetHandleId, queue);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void issuePointOrder(final Object sourceAddress, final long sessionToken, final int unitHandleId,
			final int abilityHandleId, final int orderId, final float x, final float y, final boolean queue) {
		System.out.println("issuePointOrder from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.issuePointOrder(playerIndex, unitHandleId, abilityHandleId, orderId, x, y,
						queue);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void issueDropItemAtPointOrder(final Object sourceAddress, final long sessionToken,
			final int unitHandleId, final int abilityHandleId, final int orderId, final int targetHandleId,
			final float x, final float y, final boolean queue) {
		System.out.println("issueDropItemAtPointOrder from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.issueDropItemAtPointOrder(playerIndex, unitHandleId, abilityHandleId,
						orderId, targetHandleId, x, y, queue);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void issueDropItemAtTargetOrder(final Object sourceAddress, final long sessionToken,
			final int unitHandleId, final int abilityHandleId, final int orderId, final int targetHandleId,
			final int targetHeroHandleId, final boolean queue) {
		System.out.println("issueDropItemAtTargetOrder from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.issueDropItemAtTargetOrder(playerIndex, unitHandleId, abilityHandleId,
						orderId, targetHandleId, targetHeroHandleId, queue);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void issueImmediateOrder(final Object sourceAddress, final long sessionToken, final int unitHandleId,
			final int abilityHandleId, final int orderId, final boolean queue) {
		System.out.println("issueImmediateOrder from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.issueImmediateOrder(playerIndex, unitHandleId, abilityHandleId, orderId,
						queue);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void unitCancelTrainingItem(final Object sourceAddress, final long sessionToken,
			final int unitHandleId, final int cancelIndex) {
		System.out.println("unitCancelTrainingItem from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.unitCancelTrainingItem(playerIndex, unitHandleId, cancelIndex);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void issueGuiPlayerEvent(final Object sourceAddress, final long sessionToken, final int eventId) {
		System.out.println("issueGuiPlayerEvent from " + sourceAddress);
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.turnActions.add(new Runnable() {
			@Override
			public void run() {
				WarsmashServer.this.writer.issueGuiPlayerEvent(playerIndex, eventId);
				WarsmashServer.this.writer.send();
			}
		});
	}

	@Override
	public void finishedTurn(final Object sourceAddress, final long sessionToken, final int clientGameTurnTick) {
		final int gameTurnTick = clientGameTurnTick + MAGIC_DELAY_OFFSET;
		if (VERBOSE_LOGGING) {
			System.out.println("finishedTurn(" + gameTurnTick + ") from " + sourceAddress);
		}
		if (!this.gameStarted) {
			throw new IllegalStateException(
					"Client should not send us finishedTurn() message when game has not started!");
		}
		this.clientToTurnFinished.put(sourceAddress, gameTurnTick);
		boolean allDone = true;
		for (final Object clientAddress : this.socketAddressesKnown) {
			final Integer turnFinishedValue = this.clientToTurnFinished.get(clientAddress);
			if ((turnFinishedValue == null) || (turnFinishedValue < gameTurnTick)) {
				allDone = false;
			}
		}
		if (allDone) {
			for (final Runnable turnAction : this.turnActions) {
				turnAction.run();
			}
			this.turnActions.clear();
			startTurn();
		}
	}

	@Override
	public void framesSkipped(final long sessionToken, final int nFramesSkipped) {
		if (this.sessionTokenToPermittedSlot.containsKey(sessionToken)) {
			// dont care for now
			final long currentTimeMillis = System.currentTimeMillis();
			if ((currentTimeMillis - this.lastServerHeartbeatTime) > 3000) {
				// 3 seconds of frame skipping, make sure we keep in contact with client
				System.out.println("sending server heartbeat()");
				WarsmashServer.this.writer.heartbeat();
				WarsmashServer.this.writer.send();
				this.lastServerHeartbeatTime = currentTimeMillis;
			}
		}
	}

	public static void main(final String[] args) {
		try {
			final Map<Long, Integer> sessionTokenToPermittedSlot = new HashMap<>();
			sessionTokenToPermittedSlot.put(1337001L, 0);
			sessionTokenToPermittedSlot.put(1337002L, 1);
			final WarsmashServer server = new WarsmashServer(GamingNetwork.UDP_SINGLE_GAME_PORT,
					sessionTokenToPermittedSlot);
			server.startThread();

			final Scanner scanner = new Scanner(System.in);
			while (scanner.hasNextLine()) {
				final String line = scanner.nextLine();
				if ("start".equals(line)) {
					server.startGame();
					break;
				}
			}
			scanner.close();
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
	}
}
