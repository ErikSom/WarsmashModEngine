package com.etheller.warsmash.networking;

import com.badlogic.gdx.utils.IntIntMap;

/**
 * Snapshot of the multiplayer lobby's slot configuration at the
 * moment the host clicked Start. Carried from the platform-specific
 * start handshake (web: {@code WebMultiplayerCoordinator}, desktop:
 * the BattleNet/skirmish flow) into
 * {@link com.etheller.warsmash.viewer5.handlers.w3x.ui.MenuUI#startMultiplayerGameDirect}
 * so the engine applies the lobby's race/color/team picks to
 * {@code CBasePlayer} after {@code loadAndCacheMapConfigs}.
 *
 * <p>Slot indexes that don't appear in any of these maps fall through
 * to whatever the map declared (the {@code .w3i}-shipped defaults).
 *
 * <p>All maps are 0-based slot id → value:
 * <ul>
 *   <li>{@code slotTypes}: 0 = open, 1 = closed</li>
 *   <li>{@code slotRaces}: matches {@code CRaceManager} race ids
 *       (0 = random, 1 = human, 2 = orc, 3 = undead, 4 = nightelf)</li>
 *   <li>{@code slotColors}: 0..11 (the standard WC3 player color id)</li>
 *   <li>{@code slotTeams}: force / team index from the map's w3i
 *       forces table; -1 means no force / FFA</li>
 *   <li>{@code slotHandicaps}: 50, 60, 70, 80, 90, or 100 percent</li>
 * </ul>
 *
 * <p>An instance with all empty maps is equivalent to "use map
 * defaults for everything" — call sites that don't have lobby data
 * (legacy desktop paths) can pass {@link #empty()}.
 */
public final class MultiplayerLobbyConfig {
	public static final int SLOT_TYPE_OPEN   = 0;
	public static final int SLOT_TYPE_CLOSED = 1;

	private final IntIntMap slotTypes;
	private final IntIntMap slotRaces;
	private final IntIntMap slotColors;
	private final IntIntMap slotTeams;
	private final IntIntMap slotHandicaps;
	/**
	 * Mirror of the map's w3i {@code FIXED_PLAYER_SETTINGS_FOR_CUSTOM_FORCES}
	 * flag — the host's mapInfo carries it from war3map.w3i and forwards
	 * it through the start handshake. When true the engine treats every
	 * map-declared Computer slot as PLAYING (matching single-player
	 * skirmish around line 1185 of MenuUI), instead of only the first.
	 */
	private final boolean fixedPlayerSettings;

	public MultiplayerLobbyConfig(final IntIntMap slotTypes, final IntIntMap slotRaces,
			final IntIntMap slotColors, final IntIntMap slotTeams, final IntIntMap slotHandicaps,
			final boolean fixedPlayerSettings) {
		this.slotTypes = slotTypes;
		this.slotRaces = slotRaces;
		this.slotColors = slotColors;
		this.slotTeams = slotTeams;
		this.slotHandicaps = slotHandicaps;
		this.fixedPlayerSettings = fixedPlayerSettings;
	}

	public static MultiplayerLobbyConfig empty() {
		return new MultiplayerLobbyConfig(new IntIntMap(), new IntIntMap(),
				new IntIntMap(), new IntIntMap(), new IntIntMap(), false);
	}

	public IntIntMap getSlotTypes()     { return this.slotTypes; }
	public IntIntMap getSlotRaces()     { return this.slotRaces; }
	public IntIntMap getSlotColors()    { return this.slotColors; }
	public IntIntMap getSlotTeams()     { return this.slotTeams; }
	public IntIntMap getSlotHandicaps() { return this.slotHandicaps; }
	public boolean isFixedPlayerSettings() { return this.fixedPlayerSettings; }

	public boolean hasAnyConfig() {
		return this.slotTypes.size > 0 || this.slotRaces.size > 0
				|| this.slotColors.size > 0 || this.slotTeams.size > 0
				|| this.slotHandicaps.size > 0;
	}
}
