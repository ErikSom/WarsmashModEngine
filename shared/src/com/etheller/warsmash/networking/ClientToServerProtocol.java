package com.etheller.warsmash.networking;

public class ClientToServerProtocol {

	public static final int ISSUE_TARGET_ORDER = 1;
	public static final int ISSUE_POINT_ORDER = 2;
	public static final int ISSUE_DROP_ITEM_ORDER = 3;
	public static final int ISSUE_DROP_ITEM_ON_TARGET_ORDER = 9;
	public static final int ISSUE_IMMEDIATE_ORDER = 4;
	public static final int UNIT_CANCEL_TRAINING = 5;
	public static final int FINISHED_TURN = 6;
	public static final int JOIN_GAME = 7;
	public static final int FRAMES_SKIPPED = 8;
	public static final int ISSUE_GUI_PLAYER_EVENT = 10;
	/**
	 * Periodic state-hash report from the client to the server, used for
	 * lockstep desync detection. Each client computes a stable hash over
	 * critical simulation state every N turns and sends it; the server
	 * compares hashes from all clients and flags divergence. Cheap (~10us
	 * per hash, +20 bytes per packet, sampled every ~30 turns) but catches
	 * determinism regressions immediately rather than mid-game-from-a-bug-report.
	 */
	public static final int STATE_HASH = 11;
	/**
	 * Client → server: this client's local simulation state dump after a
	 * DESYNC_DETECTED was received. The server collects dumps from all
	 * clients and broadcasts a {@link ServerToClientProtocol#COMBINED_DESYNC_REPORT}
	 * with everyone's state so each client's diagnostic overlay can show
	 * the full picture (host's view + joiners' views, side-by-side
	 * diffable in a single textarea).
	 */
	public static final int DESYNC_DUMP = 12;
}
