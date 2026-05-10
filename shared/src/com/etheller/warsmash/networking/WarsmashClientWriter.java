package com.etheller.warsmash.networking;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.warsmash.networking.udp.OrderedUdpCommuncation;

public class WarsmashClientWriter {
	// Type widened from OrderedUdpClient (UDP-specific) to its abstract parent
	// so non-UDP transports — currently only WebRtcOrderedClient on the web
	// build — can plug in without subclassing OrderedUdpClient (which would
	// drag java.net.* into the TeaVM reachability graph).
	private final OrderedUdpCommuncation client;
	private final ByteBuffer sendBuffer = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
	private final long sessionToken;

	public WarsmashClientWriter(final OrderedUdpCommuncation client, final long sessionToken) {
		this.client = client;
		this.sessionToken = sessionToken;
	}

	public void issueTargetOrder(final int unitHandleId, final int abilityHandleId, final int orderId,
			final int targetHandleId, final boolean queue) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 4 + 4 + 4 + 1);
		this.sendBuffer.putInt(ClientToServerProtocol.ISSUE_TARGET_ORDER);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(unitHandleId);
		this.sendBuffer.putInt(abilityHandleId);
		this.sendBuffer.putInt(orderId);
		this.sendBuffer.putInt(targetHandleId);
		this.sendBuffer.put(queue ? (byte) 1 : (byte) 0);
	}

	public void issuePointOrder(final int unitHandleId, final int abilityHandleId, final int orderId, final float x,
			final float y, final boolean queue) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 4 + 4 + 4 + 4 + 1);
		this.sendBuffer.putInt(ClientToServerProtocol.ISSUE_POINT_ORDER);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(unitHandleId);
		this.sendBuffer.putInt(abilityHandleId);
		this.sendBuffer.putInt(orderId);
		this.sendBuffer.putFloat(x);
		this.sendBuffer.putFloat(y);
		this.sendBuffer.put(queue ? (byte) 1 : (byte) 0);
	}

	public void issueDropItemAtPointOrder(final int unitHandleId, final int abilityHandleId, final int orderId,
			final int targetHandleId, final float x, final float y, final boolean queue) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 4 + 4 + 4 + 4 + 4 + 1);
		this.sendBuffer.putInt(ClientToServerProtocol.ISSUE_DROP_ITEM_ORDER);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(unitHandleId);
		this.sendBuffer.putInt(abilityHandleId);
		this.sendBuffer.putInt(orderId);
		this.sendBuffer.putInt(targetHandleId);
		this.sendBuffer.putFloat(x);
		this.sendBuffer.putFloat(y);
		this.sendBuffer.put(queue ? (byte) 1 : (byte) 0);
	}

	public void issueDropItemAtTargetOrder(final int unitHandleId, final int abilityHandleId, final int orderId,
			final int targetHandleId, final int targetHeroHandleId, final boolean queue) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 4 + 4 + 4 + 4 + 1);
		this.sendBuffer.putInt(ClientToServerProtocol.ISSUE_DROP_ITEM_ON_TARGET_ORDER);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(unitHandleId);
		this.sendBuffer.putInt(abilityHandleId);
		this.sendBuffer.putInt(orderId);
		this.sendBuffer.putInt(targetHandleId);
		this.sendBuffer.putInt(targetHeroHandleId);
		this.sendBuffer.put(queue ? (byte) 1 : (byte) 0);
	}

	public void issueImmediateOrder(final int unitHandleId, final int abilityHandleId, final int orderId,
			final boolean queue) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 4 + 4 + 1);
		this.sendBuffer.putInt(ClientToServerProtocol.ISSUE_IMMEDIATE_ORDER);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(unitHandleId);
		this.sendBuffer.putInt(abilityHandleId);
		this.sendBuffer.putInt(orderId);
		this.sendBuffer.put(queue ? (byte) 1 : (byte) 0);
	}

	public void unitCancelTrainingItem(final int unitHandleId, final int cancelIndex) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 4);
		this.sendBuffer.putInt(ClientToServerProtocol.UNIT_CANCEL_TRAINING);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(unitHandleId);
		this.sendBuffer.putInt(cancelIndex);
	}

	public void issueGuiPlayerEvent(final int eventId) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4);
		this.sendBuffer.putInt(ClientToServerProtocol.ISSUE_GUI_PLAYER_EVENT);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(eventId);
	}

	public void finishedTurn(final int gameTurnTick) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4);
		this.sendBuffer.putInt(ClientToServerProtocol.FINISHED_TURN);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(gameTurnTick);
	}

	public void framesSkipped(final int skippedCount) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4);
		this.sendBuffer.putInt(ClientToServerProtocol.FRAMES_SKIPPED);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(skippedCount);
	}

	/**
	 * Periodic state-hash report for desync detection.
	 * Wire payload: protocol(4) + sessionToken(8) + gameTurnTick(4) + stateHash(8).
	 */
	public void stateHash(final int gameTurnTick, final long stateHash) {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8 + 4 + 8);
		this.sendBuffer.putInt(ClientToServerProtocol.STATE_HASH);
		this.sendBuffer.putLong(this.sessionToken);
		this.sendBuffer.putInt(gameTurnTick);
		this.sendBuffer.putLong(stateHash);
	}

	/** See {@link ClientToServerProtocol#DESYNC_DUMP}.
	 *  Sized dynamically — local sim dumps for 100+ unit games can run
	 *  10+ KB, well past the shared 1024-byte sendBuffer. We allocate a
	 *  one-shot buffer here and route through the transport directly,
	 *  same trick {@code WarsmashServerWriter.combinedDesyncReport} uses. */
	public void desyncDump(final int gameTurnTick, final String localDump) {
		final byte[] dumpBytes = localDump == null
				? new byte[0]
				: localDump.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final int header = 4 + 4 + 8 + 4 + 4; // length + protocol + sessionToken + turnTick + dumpLen
		final ByteBuffer buf = (header + dumpBytes.length <= this.sendBuffer.capacity())
				? this.sendBuffer
				: ByteBuffer.allocate(header + dumpBytes.length).order(ByteOrder.BIG_ENDIAN);
		buf.clear();
		buf.putInt(4 + 8 + 4 + 4 + dumpBytes.length);
		buf.putInt(ClientToServerProtocol.DESYNC_DUMP);
		buf.putLong(this.sessionToken);
		buf.putInt(gameTurnTick);
		buf.putInt(dumpBytes.length);
		buf.put(dumpBytes);
		// If we used a one-shot buffer, flush it directly via the
		// transport (the canonical send() path reads from sendBuffer only).
		if (buf != this.sendBuffer) {
			buf.flip();
			try {
				this.client.send(buf);
			}
			catch (final IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void joinGame() {
		this.sendBuffer.clear();
		this.sendBuffer.putInt(4 + 8);
		this.sendBuffer.putInt(ClientToServerProtocol.JOIN_GAME);
		this.sendBuffer.putLong(this.sessionToken);
	}

	public void send() {
		this.sendBuffer.flip();
		try {
			this.client.send(this.sendBuffer);
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}

}
