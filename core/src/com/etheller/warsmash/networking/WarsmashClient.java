package com.etheller.warsmash.networking;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.IntIntMap;
import com.etheller.warsmash.util.WarsmashConstants;
import com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerUnitOrderExecutor;

import net.warsmash.networking.udp.OrderedUdpClient;
import net.warsmash.networking.udp.OrderedUdpCommuncation;

public class WarsmashClient implements ServerToClientListener, GameTurnManager {
	// Field type widened from OrderedUdpClient to its abstract parent so the
	// web build can plug in WebRtcOrderedClient. Desktop still uses
	// OrderedUdpClient (its concrete subtype) via the legacy constructor below.
	private final OrderedUdpCommuncation udpClient;
	private final War3MapViewer game;
	private final Map<Integer, CPlayerUnitOrderExecutor> indexToExecutor = new HashMap<>();
	private int latestCompletedTurn = -1;
	private int latestLocallyRequestedTurn = -1;
	/** Set when DESYNC_DETECTED arrives from the server. {@link #turnCompleted}
	 *  becomes a no-op so the lockstep loop stalls cleanly while the
	 *  diagnostic overlay is up. */
	private boolean haltedDueToDesync = false;

	/** State dump captured at the same turn-tick the most recent state
	 *  hash was computed at. Stashed so {@link #desyncDetected} can
	 *  publish it directly instead of taking a fresh dump (which would
	 *  drift by a render frame or two due to the postRunnable hop and
	 *  produce false-positive line-level differences in the diagnostic
	 *  combined report). */
	private String stateDumpAtLastHashTurn = "";
	private long stateHashAtLastHashTurn = 0L;
	private final WarsmashClientWriter writer;
	private final Queue<QueuedMessage> queuedMessages = new ArrayDeque<>();
	private final IntIntMap serverSlotToMapSlot;

	/**
	 * Desktop convenience constructor — opens a UDP socket to {@code serverAddress:udpPort}
	 * and wires it to a {@link WarsmashClientParser} that delegates back to this client.
	 * Pulls {@code java.net.*} into the reachability graph; do NOT call this from the
	 * web build — use {@link #WarsmashClient(OrderedUdpCommuncation, WarsmashClientParser, War3MapViewer, long, IntIntMap)}.
	 */
	public WarsmashClient(final InetAddress serverAddress, final int udpPort, final War3MapViewer game,
			final long sessionToken, final IntIntMap serverSlotToMapSlot) throws UnknownHostException, IOException {
		this.udpClient = new OrderedUdpClient(serverAddress, udpPort, new WarsmashClientParser(this));
		this.game = game;
		this.writer = new WarsmashClientWriter(this.udpClient, sessionToken);
		this.serverSlotToMapSlot = serverSlotToMapSlot;
	}

	/**
	 * Transport-injection constructor for non-UDP backends (currently the web
	 * build's {@link com.etheller.warsmash.html.network.WebRtcOrderedClient}). The
	 * caller is responsible for breaking the construction cycle:
	 * <pre>
	 *   WarsmashClientParser parser = new WarsmashClientParser();
	 *   OrderedUdpCommuncation transport = ...openClient(peerId, parser);
	 *   WarsmashClient client = new WarsmashClient(transport, parser, viewer, token, slotMap);
	 *   parser.setListener(client);
	 * </pre>
	 * The two-step parser wiring keeps the {@code WarsmashClient → parser → transport}
	 * chain free of "leaking this in constructor" gymnastics.
	 */
	public WarsmashClient(final OrderedUdpCommuncation transport, final WarsmashClientParser parser,
			final War3MapViewer game, final long sessionToken, final IntIntMap serverSlotToMapSlot) {
		// parser is accepted only to make the cycle explicit at the call site;
		// it's already attached to the transport, we just want the constructor
		// signature to scream "you must build a parser for this".
		if (parser == null) {
			throw new IllegalArgumentException("parser is required so the late-bound listener can route incoming traffic back to this client");
		}
		this.udpClient = transport;
		this.game = game;
		this.writer = new WarsmashClientWriter(transport, sessionToken);
		this.serverSlotToMapSlot = serverSlotToMapSlot;
	}

	private CPlayerUnitOrderExecutor getExecutor(final int serverPlayerIndex) {
		final int mapPlayerIndex = this.serverSlotToMapSlot.get(serverPlayerIndex, -1);
		CPlayerUnitOrderExecutor executor = this.indexToExecutor.get(serverPlayerIndex);
		if (executor == null) {
			executor = new CPlayerUnitOrderExecutor(this.game.simulation, mapPlayerIndex);
			this.indexToExecutor.put(serverPlayerIndex, executor);
		}
		return executor;
	}

	/**
	 * Start the background receive loop for transports that need one (i.e.
	 * desktop's {@link OrderedUdpClient} which owns a blocking
	 * {@code DatagramChannel.receive()}). For event-driven transports
	 * (WebRTC datachannels are pushed to us by the browser) this is a no-op.
	 */
	public void startThread() {
		if (this.udpClient instanceof Runnable) {
			new Thread((Runnable) this.udpClient).start();
		}
	}

	@Override
	public void acceptJoin(final int playerIndex) {
		System.err.println("acceptJoin " + playerIndex);
		this.game.setLocalPlayerServerSlot(playerIndex);
	}

	@Override
	public void issueTargetOrder(final int playerIndex, final int unitHandleId, final int abilityHandleId,
			final int orderId, final int targetHandleId, final boolean queue) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.issueTargetOrder(unitHandleId, abilityHandleId, orderId, targetHandleId, queue);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.issueTargetOrder(unitHandleId, abilityHandleId, orderId, targetHandleId, queue);
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});
	}

	@Override
	public void issuePointOrder(final int playerIndex, final int unitHandleId, final int abilityHandleId,
			final int orderId, final float x, final float y, final boolean queue) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.issuePointOrder(unitHandleId, abilityHandleId, orderId, x, y, queue);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.issuePointOrder(unitHandleId, abilityHandleId, orderId, x, y, queue);
					;
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});

	}

	@Override
	public void issueDropItemAtPointOrder(final int playerIndex, final int unitHandleId, final int abilityHandleId,
			final int orderId, final int targetHandleId, final float x, final float y, final boolean queue) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.issueDropItemAtPointOrder(unitHandleId, abilityHandleId, orderId, targetHandleId,
									x, y, queue);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.issueDropItemAtPointOrder(unitHandleId, abilityHandleId, orderId, targetHandleId, x, y,
							queue);
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});
	}

	@Override
	public void issueDropItemAtTargetOrder(final int playerIndex, final int unitHandleId, final int abilityHandleId,
			final int orderId, final int targetHandleId, final int targetHeroHandleId, final boolean queue) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.issueDropItemAtTargetOrder(unitHandleId, abilityHandleId, orderId, targetHandleId,
									targetHeroHandleId, queue);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.issueDropItemAtTargetOrder(unitHandleId, abilityHandleId, orderId, targetHandleId,
							targetHeroHandleId, queue);
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});
	}

	@Override
	public void issueImmediateOrder(final int playerIndex, final int unitHandleId, final int abilityHandleId,
			final int orderId, final boolean queue) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.issueImmediateOrder(unitHandleId, abilityHandleId, orderId, queue);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.issueImmediateOrder(unitHandleId, abilityHandleId, orderId, queue);
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});
	}

	@Override
	public void unitCancelTrainingItem(final int playerIndex, final int unitHandleId, final int cancelIndex) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.unitCancelTrainingItem(unitHandleId, cancelIndex);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.unitCancelTrainingItem(unitHandleId, cancelIndex);
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});
	}

	@Override
	public void issueGuiPlayerEvent(final int playerIndex, final int eventId) {
		final CPlayerUnitOrderExecutor executor = getExecutor(playerIndex);
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final int currentServerTurnInProgress = WarsmashClient.this.latestCompletedTurn + 1;
				if (currentServerTurnInProgress > WarsmashClient.this.latestLocallyRequestedTurn) {
					WarsmashClient.this.queuedMessages.add(new QueuedMessage(currentServerTurnInProgress) {
						@Override
						public void run() {
							executor.issueGuiPlayerEvent(eventId);
						}
					});
				}
				else if (currentServerTurnInProgress == WarsmashClient.this.latestLocallyRequestedTurn) {
					executor.issueGuiPlayerEvent(eventId);
				}
				else {
					System.err.println("Turn tick system mismatch: " + currentServerTurnInProgress + " < "
							+ WarsmashClient.this.latestLocallyRequestedTurn);
				}
			}
		});
	}

	@Override
	public void finishedTurn(final int gameTurnTick) {
		if (WarsmashConstants.VERBOSE_LOGGING) {
			System.out.println("finishedTurn " + gameTurnTick);
		}
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				WarsmashClient.this.latestCompletedTurn = gameTurnTick;
			}
		});
	}

	/**
	 * How often we send a state-hash to the server for desync detection.
	 * Cheap to lower (smaller window before a desync is caught) at the
	 * cost of a few more bytes/sec on the wire. 30 turns ≈ 7.5s at the
	 * default ~4 turns/sec; comfortable middle ground.
	 */
	private static final int STATE_HASH_EVERY_N_TURNS = 30;

	@Override
	public void turnCompleted(final int gameTurnTick) {
		if (this.haltedDueToDesync) {
			return; // Lockstep loop intentionally frozen post-desync
		}
		if (WarsmashConstants.VERBOSE_LOGGING) {
			System.out.println("turnCompleted " + gameTurnTick);
		}
		this.writer.finishedTurn(gameTurnTick);
		this.writer.send();
		this.latestLocallyRequestedTurn = gameTurnTick;
		while (!this.queuedMessages.isEmpty()
				&& (this.queuedMessages.peek().messageTurnTick == this.latestLocallyRequestedTurn)) {
			this.queuedMessages.poll().run();
		}
		if (WarsmashConstants.VERBOSE_LOGGING && !this.queuedMessages.isEmpty()) {
			System.out.println("stopped with " + this.queuedMessages.peek().messageTurnTick + " != "
					+ this.latestLocallyRequestedTurn);
		}
		// Periodic state-hash for desync detection. The hash is computed
		// on the simulation's current state — by the time turnCompleted
		// fires, all locally-known orders for this turn have been applied,
		// so every client should have the same world here. Server collects
		// hashes from all clients and compares; mismatches log a DESYNC
		// line server-side identifying which client(s) diverged.
		if ((gameTurnTick % STATE_HASH_EVERY_N_TURNS) == 0) {
			final long hash = this.game.simulation.computeStateHash();
			// Capture the dump synchronously here, while the simulation is
			// at the exact same tick the hash describes. This dump is what
			// gets published to the diagnostic overlay if a desync is
			// flagged later, so it must line up bit-for-bit with the hash.
			final String dump = this.game.simulation.dumpDebugState();
			this.stateHashAtLastHashTurn = hash;
			this.stateDumpAtLastHashTurn = dump;
			this.writer.stateHash(gameTurnTick, hash);
			this.writer.send();
		}
	}

	@Override
	public void startGame() {
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				WarsmashClient.this.game.setGameTurnManager(WarsmashClient.this);
			}
		});
	}

	@Override
	public void framesSkipped(final float skippedCount) {
		this.writer.framesSkipped((int) skippedCount);
		this.writer.send();
	}

	@Override
	public void heartbeat() {
		// Not doing anything here at the moment. The act of the server sending us that
		// packet
		// will let the middle layer UDP system know to re-request any lost packets
		// based
		// on the heartbeat seq no. But at app layer, here, we can ignore it.
		if (WarsmashConstants.VERBOSE_LOGGING) {
			System.out.println("got heartbeat() from server");
		}
	}

	@Override
	public void desyncDetected(final int gameTurnTick, final String peerHashSummary) {
		// Halt + publish-stashed-dump + send-stashed-dump-to-server. The
		// dump we publish is the one we captured at the last hash turn
		// (in turnCompleted), so it lines up perfectly with the hash that
		// triggered the divergence — diff'ing dumps across clients
		// produces only real differences, never render-frame-timing noise.
		this.haltedDueToDesync = true;
		final long stashedHash = this.stateHashAtLastHashTurn;
		final String stashedDump = this.stateDumpAtLastHashTurn;
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				DesyncReport.publish(gameTurnTick, peerHashSummary, stashedHash, stashedDump);
				WarsmashClient.this.writer.desyncDump(gameTurnTick, stashedDump);
				WarsmashClient.this.writer.send();
			}
		});
	}

	@Override
	public void combinedDesyncReport(final int gameTurnTick, final String combinedReport) {
		// Server has aggregated all clients' dumps. Replace the modal's
		// content with the combined version so the user has one report
		// covering everyone's view.
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				DesyncReport.publishCombined(gameTurnTick, combinedReport);
			}
		});
	}

	@Override
	public int getLatestCompletedTurn() {
		return this.latestCompletedTurn;
	}

	public WarsmashClientWriter getWriter() {
		return this.writer;
	}

	private static abstract class QueuedMessage implements Runnable {
		private final int messageTurnTick;

		public QueuedMessage(final int messageTurnTick) {
			this.messageTurnTick = messageTurnTick;
		}

		public final int getMessageTurnTick() {
			return this.messageTurnTick;
		}

		@Override
		public abstract void run();
	}
}
