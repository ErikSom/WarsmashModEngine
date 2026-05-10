package com.etheller.warsmash.networking;

public interface ClientToServerListener {
	// `sourceAddress` widened from java.net.SocketAddress to Object so the
	// shared protocol code doesn't pull java.net.* into the TeaVM
	// reachability graph (web build's WebRtcOrderedServer uses a custom
	// marker class as the per-peer key). Desktop's OrderedUdpServer
	// continues to pass real SocketAddress instances via these methods.
	// See UdpServerListener.parse for the wider rationale.
	void joinGame(Object sourceAddress, long sessionToken);

	void issueTargetOrder(Object sourceAddress, long sessionToken, int unitHandleId, int abilityHandleId,
			int orderId, int targetHandleId, boolean queue);

	void issuePointOrder(Object sourceAddress, long sessionToken, int unitHandleId, int abilityHandleId,
			int orderId, float x, float y, boolean queue);

	void issueDropItemAtPointOrder(Object sourceAddress, long sessionToken, int unitHandleId,
			int abilityHandleId, int orderId, int targetHandleId, float x, float y, final boolean queue);

	void issueDropItemAtTargetOrder(Object sourceAddress, long sessionToken, int unitHandleId,
			int abilityHandleId, int orderId, int targetHandleId, int targetHeroHandleId, final boolean queue);

	void issueImmediateOrder(Object sourceAddress, long sessionToken, int unitHandleId, int abilityHandleId,
			int orderId, boolean queue);

	void unitCancelTrainingItem(Object sourceAddress, long sessionToken, int unitHandleId, int cancelIndex);

	void issueGuiPlayerEvent(Object sourceAddress, long sessionToken, int eventId);

	void finishedTurn(Object sourceAddress, long sessionToken, int gameTurnTick);

	void framesSkipped(long sessionToken, int nFramesSkipped);

	/**
	 * Periodic state-hash for desync detection. Server collects hashes
	 * from all clients for the same {@code gameTurnTick}; mismatches are
	 * logged. See {@link ClientToServerProtocol#STATE_HASH}.
	 */
	void stateHash(Object sourceAddress, long sessionToken, int gameTurnTick, long stateHash);

	/**
	 * Client's local state dump submitted in response to DESYNC_DETECTED.
	 * See {@link ClientToServerProtocol#DESYNC_DUMP}.
	 */
	void desyncDump(Object sourceAddress, long sessionToken, int gameTurnTick, String localStateDump);

}
