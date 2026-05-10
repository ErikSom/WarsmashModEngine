package com.etheller.warsmash.networking;

public interface ServerToClientListener {
	void acceptJoin(int playerIndex);

	void issueTargetOrder(int playerIndex, int unitHandleId, int abilityHandleId, int orderId, int targetHandleId,
			boolean queue);

	void issuePointOrder(int playerIndex, int unitHandleId, int abilityHandleId, int orderId, float x, float y,
			boolean queue);

	void issueDropItemAtPointOrder(int playerIndex, int unitHandleId, int abilityHandleId, int orderId,
			int targetHandleId, float x, float y, final boolean queue);

	void issueDropItemAtTargetOrder(int playerIndex, int unitHandleId, int abilityHandleId, int orderId,
			int targetHandleId, int targetHeroHandleId, final boolean queue);

	void issueImmediateOrder(int playerIndex, int unitHandleId, int abilityHandleId, int orderId, boolean queue);

	void unitCancelTrainingItem(int playerIndex, int unitHandleId, int cancelIndex);

	void issueGuiPlayerEvent(int playerIndex, int eventId);

	void startGame();

	void finishedTurn(int gameTurnTick);

	void heartbeat();

	/**
	 * Lockstep desync was detected. {@code peerHashSummary} is a human-
	 * readable multi-line string: one peer per line, "{@code peer:<id> = 0x<hash>}".
	 * Clients should halt simulation, gather diagnostic state, and surface
	 * a copyable report to the user. See {@link ServerToClientProtocol#DESYNC_DETECTED}.
	 */
	void desyncDetected(int gameTurnTick, String peerHashSummary);

	/**
	 * Combined desync report aggregated by the server from all clients'
	 * dumps. Replaces the initial local-only modal contents on each
	 * client. See {@link ServerToClientProtocol#COMBINED_DESYNC_REPORT}.
	 */
	void combinedDesyncReport(int gameTurnTick, String combinedReport);
}
