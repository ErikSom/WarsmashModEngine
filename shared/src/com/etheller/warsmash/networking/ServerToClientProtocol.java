package com.etheller.warsmash.networking;

public class ServerToClientProtocol {

	public static final int ISSUE_TARGET_ORDER = 1;
	public static final int ISSUE_POINT_ORDER = 2;
	public static final int ISSUE_DROP_ITEM_ORDER = 3;
	public static final int ISSUE_DROP_ITEM_ON_TARGET_ORDER = 10;
	public static final int ISSUE_IMMEDIATE_ORDER = 4;
	public static final int UNIT_CANCEL_TRAINING = 5;
	public static final int ISSUE_GUI_PLAYER_EVENT = 11;
	public static final int FINISHED_TURN = 6;
	public static final int ACCEPT_JOIN = 7;
	public static final int START_GAME = 8;
	public static final int HEARTBEAT = 9;
	/**
	 * Server → all clients: lockstep desync was detected at turn N. Payload
	 * carries the per-peer hash list (UTF-8 string) so each client can show
	 * the divergence context to the user. After broadcasting this the
	 * server halts further turn dispatch — clients naturally stall on the
	 * next tick boundary because no new {@code FINISHED_TURN} message
	 * arrives. Each client renders a copyable diagnostic overlay so the
	 * user can share it with developers.
	 */
	public static final int DESYNC_DETECTED = 12;
	/**
	 * Server → all clients: combined desync report aggregated from every
	 * client's {@link ClientToServerProtocol#DESYNC_DUMP}. Sent after the
	 * server has received dumps from all clients (or after a short
	 * collection window). Clients render this in place of the initial
	 * local-only dump so the user has one report to share that has
	 * EVERYONE's state, not just their own machine's.
	 */
	public static final int COMBINED_DESYNC_REPORT = 13;
}
