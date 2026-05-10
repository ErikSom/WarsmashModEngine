package com.etheller.warsmash.networking;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.warsmash.networking.udp.OrderedUdpServerListener;

public class WarsmashServerParser implements OrderedUdpServerListener {

	private ClientToServerListener listener;

	public WarsmashServerParser(final ClientToServerListener clientToServerListener) throws IOException {
		this.listener = clientToServerListener;
	}

	/**
	 * No-arg constructor for callers that need to break the construction
	 * cycle between {@link WarsmashServer}, this parser, and the underlying
	 * transport. Pair with {@link #setListener} after the {@link WarsmashServer}
	 * exists. Mirrors the same trick on the client side
	 * ({@link WarsmashClientParser}).
	 */
	public WarsmashServerParser() {
	}

	public void setListener(final ClientToServerListener listener) {
		this.listener = listener;
	}

	@Override
	public void parse(final Object sourceAddress, final ByteBuffer buffer) {
		final int initialLimit = buffer.limit();
		try {
			while (buffer.hasRemaining()) {
				final int length = buffer.getInt();
				if (length > buffer.remaining()) {
					// this packet is junk to us, so we will skip and continue (drop system will
					// handle it)
					System.err.println("Got mismatched protocol length " + length + " > " + buffer.remaining() + "!!");
					break;
				}
				final int protocol = buffer.getInt();
				switch (protocol) {
				case ClientToServerProtocol.ISSUE_TARGET_ORDER: {
					final long sessionToken = buffer.getLong();
					final int unitHandleId = buffer.getInt();
					final int abilityHandleId = buffer.getInt();
					final int orderId = buffer.getInt();
					final int targetHandleId = buffer.getInt();
					final boolean queue = buffer.get() == 1;
					this.listener.issueTargetOrder(sourceAddress, sessionToken, unitHandleId, abilityHandleId, orderId,
							targetHandleId, queue);
					break;
				}
				case ClientToServerProtocol.ISSUE_POINT_ORDER: {
					final long sessionToken = buffer.getLong();
					final int unitHandleId = buffer.getInt();
					final int abilityHandleId = buffer.getInt();
					final int orderId = buffer.getInt();
					final float x = buffer.getFloat();
					final float y = buffer.getFloat();
					final boolean queue = buffer.get() == 1;
					this.listener.issuePointOrder(sourceAddress, sessionToken, unitHandleId, abilityHandleId, orderId,
							x, y, queue);
					break;
				}
				case ClientToServerProtocol.ISSUE_DROP_ITEM_ORDER: {
					final long sessionToken = buffer.getLong();
					final int unitHandleId = buffer.getInt();
					final int abilityHandleId = buffer.getInt();
					final int orderId = buffer.getInt();
					final int targetHandleId = buffer.getInt();
					final float x = buffer.getFloat();
					final float y = buffer.getFloat();
					final boolean queue = buffer.get() == 1;
					this.listener.issueDropItemAtPointOrder(sourceAddress, sessionToken, unitHandleId, abilityHandleId,
							orderId, targetHandleId, x, y, queue);
					break;
				}
				case ClientToServerProtocol.ISSUE_DROP_ITEM_ON_TARGET_ORDER: {
					final long sessionToken = buffer.getLong();
					final int unitHandleId = buffer.getInt();
					final int abilityHandleId = buffer.getInt();
					final int orderId = buffer.getInt();
					final int targetHandleId = buffer.getInt();
					final int targetHeroHandleId = buffer.getInt();
					final boolean queue = buffer.get() == 1;
					this.listener.issueDropItemAtTargetOrder(sourceAddress, sessionToken, unitHandleId, abilityHandleId,
							orderId, targetHandleId, targetHeroHandleId, queue);
					break;
				}
				case ClientToServerProtocol.ISSUE_IMMEDIATE_ORDER: {
					final long sessionToken = buffer.getLong();
					final int unitHandleId = buffer.getInt();
					final int abilityHandleId = buffer.getInt();
					final int orderId = buffer.getInt();
					final boolean queue = buffer.get() == 1;
					this.listener.issueImmediateOrder(sourceAddress, sessionToken, unitHandleId, abilityHandleId,
							orderId, queue);
					break;
				}
				case ClientToServerProtocol.UNIT_CANCEL_TRAINING: {
					final long sessionToken = buffer.getLong();
					final int unitHandleId = buffer.getInt();
					final int cancelIndex = buffer.getInt();
					this.listener.unitCancelTrainingItem(sourceAddress, sessionToken, unitHandleId, cancelIndex);
					break;
				}
				case ClientToServerProtocol.ISSUE_GUI_PLAYER_EVENT: {
					final long sessionToken = buffer.getLong();
					final int eventId = buffer.getInt();
					this.listener.issueGuiPlayerEvent(sourceAddress, sessionToken, eventId);
					break;
				}
				case ClientToServerProtocol.FINISHED_TURN: {
					final long sessionToken = buffer.getLong();
					final int gameTurnTick = buffer.getInt();
					this.listener.finishedTurn(sourceAddress, sessionToken, gameTurnTick);
					break;
				}
				case ClientToServerProtocol.JOIN_GAME: {
					final long sessionToken = buffer.getLong();
					this.listener.joinGame(sourceAddress, sessionToken);
					break;
				}
				case ClientToServerProtocol.FRAMES_SKIPPED: {
					final long sessionToken = buffer.getLong();
					final int nFramesSkipped = buffer.getInt();
					this.listener.framesSkipped(sessionToken, nFramesSkipped);
					break;
				}
				case ClientToServerProtocol.STATE_HASH: {
					final long sessionToken = buffer.getLong();
					final int gameTurnTick = buffer.getInt();
					final long stateHash = buffer.getLong();
					this.listener.stateHash(sourceAddress, sessionToken, gameTurnTick, stateHash);
					break;
				}
				case ClientToServerProtocol.DESYNC_DUMP: {
					final long sessionToken = buffer.getLong();
					final int gameTurnTick = buffer.getInt();
					final int dumpLen = buffer.getInt();
					final byte[] dumpBytes = new byte[dumpLen];
					buffer.get(dumpBytes);
					final String dump = new String(dumpBytes, java.nio.charset.StandardCharsets.UTF_8);
					this.listener.desyncDump(sourceAddress, sessionToken, gameTurnTick, dump);
					break;
				}

				default:
					System.err.println("Got unknown protocol: " + protocol);
					break;
				}
			}
		}
		finally {
			buffer.position(initialLimit);
		}
	}

	@Override
	public void cantReplay(final Object sourceAddress, final int seqNo) {
		throw new IllegalStateException("Cant replay " + seqNo + " to " + sourceAddress + " !");
	}
}
