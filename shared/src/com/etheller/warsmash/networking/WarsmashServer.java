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
	/** Set to true when {@link #stateHash} detects a hash mismatch. Once
	 *  raised, {@link #finishedTurn} stops broadcasting the next turn —
	 *  clients stall naturally on the next tick boundary because no new
	 *  FINISHED_TURN message arrives. The desync overlay each client
	 *  rendered locally takes over from there. */
	private boolean haltedDueToDesync = false;

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
		if (VERBOSE_LOGGING) {
			System.out.println("sending finishedTurn " + this.currentTurnTick);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("joinGame " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("issueTargetOrder from " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("issuePointOrder from " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("issueDropItemAtPointOrder from " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("issueDropItemAtTargetOrder from " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("issueImmediateOrder from " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("unitCancelTrainingItem from " + sourceAddress);
		}
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
		if (VERBOSE_LOGGING) {
			System.out.println("issueGuiPlayerEvent from " + sourceAddress);
		}
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
		if (this.haltedDueToDesync) {
			// Don't advance any further turns — clients stall here, the
			// per-client desync overlay handles the user-facing report.
			return;
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

	// Per-desync-event collection of client local dumps for the combined
	// report. Cleared once we've broadcast the combined report.
	private final Map<Object, String> desyncDumpsByAddress = new HashMap<>();
	private int desyncTurnAwaitingDumps = -1;
	/** Hash list summary captured at desync detection — included verbatim
	 *  in the combined report so users see all peer hashes alongside the
	 *  per-peer dumps. */
	private String desyncHashListSummary = "";

	@Override
	public void desyncDump(final Object sourceAddress, final long sessionToken, final int gameTurnTick,
			final String localStateDump) {
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		this.desyncDumpsByAddress.put(sourceAddress, localStateDump == null ? "" : localStateDump);
		// Wait until we have one dump per known client (or per allocated
		// session-token slot, whichever is smaller — disconnected clients
		// just don't show up). Then build + broadcast the combined report.
		if (this.desyncDumpsByAddress.size() < this.sessionTokenToPermittedSlot.size()) {
			return;
		}
		final StringBuilder combined = new StringBuilder();
		combined.append("=== Combined desync report (turn ").append(gameTurnTick).append(") ===\n");
		combined.append("Per-peer state hashes (from server):\n");
		combined.append(this.desyncHashListSummary);
		combined.append('\n');
		// Line-by-line diff across peers — the actionable section. Dumps
		// are aligned (same turn snapshot, same handle-id sort), so a
		// straight per-line comparison surfaces the divergent rows
		// directly. User can paste just this section in a bug report and
		// it tells us exactly which units' state differs.
		combined.append(buildDivergentRowsSection(this.desyncDumpsByAddress));
		// Followed by the full per-peer dumps so the user can also see
		// the full context if they want.
		for (final Map.Entry<Object, String> e : this.desyncDumpsByAddress.entrySet()) {
			combined.append("\n--- Full dump from ").append(e.getKey()).append(" ---\n");
			combined.append(e.getValue());
		}
		this.writer.combinedDesyncReport(gameTurnTick, combined.toString());
		// combinedDesyncReport's writer flushes its own buffer (large
		// payloads bypass the shared sendBuffer); no extra send() needed.
		this.desyncDumpsByAddress.clear();
		this.desyncTurnAwaitingDumps = -1;
		this.desyncHashListSummary = "";
	}

	/**
	 * Per-line diff across peer dumps. Both client dumps are produced by
	 * {@code CSimulation.dumpDebugState} which emits a fixed structure
	 * (header lines + units sorted by handleId), so the same line index
	 * means the same logical row across clients. This walks line-by-line
	 * and emits only the rows that disagree, with each peer's value.
	 *
	 * <p>Falls back to a "(no per-line divergence)" note if dumps are
	 * line-by-line identical — which can happen if the divergence was
	 * salt-only or otherwise outside the dump's scope (e.g. a different
	 * RNG state that hasn't yet manifested in unit positions).
	 */
	private static String buildDivergentRowsSection(final Map<Object, String> dumps) {
		if (dumps.size() < 2) {
			return "Divergent rows:\n  (only one peer reported a dump — nothing to diff)\n";
		}
		// Linearise the map into parallel arrays so we can iterate by
		// peer position consistently (HashMap iteration order is fine
		// here because we only care about within-iteration consistency).
		final java.util.List<Object> peerKeys = new java.util.ArrayList<>(dumps.keySet());
		final java.util.List<String[]> peerLines = new java.util.ArrayList<>();
		int maxLines = 0;
		for (final Object key : peerKeys) {
			final String[] lines = dumps.get(key).split("\n", -1);
			peerLines.add(lines);
			if (lines.length > maxLines) {
				maxLines = lines.length;
			}
		}
		final StringBuilder sb = new StringBuilder();
		sb.append("Divergent rows (line-by-line diff across peer dumps):\n");
		int divergentCount = 0;
		for (int i = 0; i < maxLines; i++) {
			String first = null;
			boolean differs = false;
			for (final String[] lines : peerLines) {
				final String line = (i < lines.length) ? lines[i] : "(missing)";
				if (first == null) {
					first = line;
				}
				else if (!first.equals(line)) {
					differs = true;
					break;
				}
			}
			if (differs) {
				divergentCount++;
				sb.append("  line ").append(i).append(":\n");
				for (int p = 0; p < peerKeys.size(); p++) {
					final String[] lines = peerLines.get(p);
					sb.append("    ").append(peerKeys.get(p)).append(": ");
					sb.append(i < lines.length ? lines[i] : "(missing)");
					sb.append('\n');
				}
			}
		}
		if (divergentCount == 0) {
			sb.append("  (no per-line divergence — dumps are byte-identical, but hashes differ;"
					+ " divergence is in state outside the dump's scope, likely RNG counter)\n");
		}
		else {
			sb.insert("Divergent rows (line-by-line diff across peer dumps):\n".length(),
					"  " + divergentCount + " divergent row(s).\n");
		}
		return sb.toString();
	}

	// Per-turn collection of client state hashes for desync detection.
	// Keyed by gameTurnTick → (clientAddress → hash). When all clients
	// have reported for a turn, compare; on mismatch log loudly. Cleaned
	// up when consensus is reached so the map doesn't grow unboundedly.
	private final Map<Integer, Map<Object, Long>> turnToClientStateHash = new HashMap<>();

	@Override
	public void stateHash(final Object sourceAddress, final long sessionToken, final int gameTurnTick,
			final long hashValue) {
		final int playerIndex = getPlayerIndex(sourceAddress, sessionToken);
		if (playerIndex == -1) {
			return;
		}
		Map<Object, Long> hashesForTurn = this.turnToClientStateHash.get(gameTurnTick);
		if (hashesForTurn == null) {
			hashesForTurn = new HashMap<>();
			this.turnToClientStateHash.put(gameTurnTick, hashesForTurn);
		}
		hashesForTurn.put(sourceAddress, hashValue);
		if (hashesForTurn.size() < this.sessionTokenToPermittedSlot.size()) {
			return;
		}
		// All clients reported for this turn. Compare.
		Long reference = null;
		boolean diverged = false;
		for (final Long h : hashesForTurn.values()) {
			if (reference == null) {
				reference = h;
			}
			else if (!reference.equals(h)) {
				diverged = true;
				break;
			}
		}
		if (diverged) {
			final StringBuilder sb = new StringBuilder();
			sb.append("DESYNC at gameTurnTick=").append(gameTurnTick).append(":");
			for (final Map.Entry<Object, Long> e : hashesForTurn.entrySet()) {
				sb.append(' ').append(e.getKey()).append("=0x")
						.append(Long.toHexString(e.getValue()));
			}
			System.err.println(sb.toString());
			// Build a human-readable per-peer summary for the diagnostic
			// overlay each client will render. One line per peer makes it
			// easy to scan in the modal and to paste into a bug report.
			final StringBuilder summary = new StringBuilder();
			for (final Map.Entry<Object, Long> e : hashesForTurn.entrySet()) {
				summary.append(e.getKey()).append(" = 0x")
						.append(Long.toHexString(e.getValue()))
						.append('\n');
			}
			this.haltedDueToDesync = true;
			this.desyncTurnAwaitingDumps = gameTurnTick;
			this.desyncHashListSummary = summary.toString();
			this.desyncDumpsByAddress.clear();
			this.writer.desyncDetected(gameTurnTick, summary.toString());
			this.writer.send();
		}
		else if (VERBOSE_LOGGING) {
			System.out.println("stateHash consensus at turn " + gameTurnTick + ": 0x"
					+ Long.toHexString(reference));
		}
		this.turnToClientStateHash.remove(gameTurnTick);
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
