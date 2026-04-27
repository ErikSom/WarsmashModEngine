package com.etheller.warsmash.viewer5.handlers.w3x.simulation.ai;

import com.etheller.warsmash.util.WarsmashConstants;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CSimulation;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnit;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitClassification;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilities.targeting.AbilityPointTarget;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.behaviors.CBehavior;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.behaviors.CBehaviorCategory;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.behaviors.harvest.CBehaviorReturnResources;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.orders.OrderIds;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CAllianceType;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayer;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CPlayerSlotState;

/**
 * Per-player melee AI driver. Runs every {@link #ACTION_INTERVAL_SECONDS} of
 * game time and pokes idle units owned by this player into doing something
 * useful. Two pokes today:
 *
 * <ul>
 * <li>Idle PEON-classified units (workers) → harvest from the nearest gold
 * mine if one exists. Mirrors the {@code MeleeStartingResources} initial
 * order in stock Blizzard.j, but keeps re-issuing for workers whose orders
 * got interrupted (combat, mine collapse, etc.).</li>
 * <li>Idle non-building, non-PEON units (combat units) → attack-move toward
 * the nearest enemy structure. Cheap heuristic that puts pressure on the
 * human player without needing the full Blizzard.ai script tree.</li>
 * </ul>
 *
 * <p>This is a deliberate placeholder for the actual {@code <race>melee.ai}
 * scripts WC3 ships with — those are loaded into a separate JASS-dialect AI
 * interpreter that this engine doesn't have yet. The Java driver gives the
 * computer player observable behaviour ("they walk towards me") while the
 * full AI implementation can come later.
 */
public final class CMeleePlayerAI {
	/** Wall-clock seconds between AI ticks. */
	private static final float ACTION_INTERVAL_SECONDS = 5.0f;

	private final int playerIndex;
	private int nextActionTurnTick = 0;

	public CMeleePlayerAI(final int playerIndex) {
		this.playerIndex = playerIndex;
	}

	public int getPlayerIndex() {
		return this.playerIndex;
	}

	public void update(final CSimulation game) {
		if (game.getGameTurnTick() < this.nextActionTurnTick) {
			return;
		}
		final int intervalTicks = Math.max(1,
				(int) (ACTION_INTERVAL_SECONDS / WarsmashConstants.SIMULATION_STEP_TIME));
		this.nextActionTurnTick = game.getGameTurnTick() + intervalTicks;

		final CPlayer self = game.getPlayer(this.playerIndex);
		if ((self == null) || (self.getSlotState() != CPlayerSlotState.PLAYING)) {
			return;
		}

		final AbilityPointTarget enemyStructure = findEnemyStructure(game, self);

		for (final CUnit unit : game.getUnits()) {
			if (unit.getPlayerIndex() != this.playerIndex) {
				continue;
			}
			if (unit.isDead() || unit.isBuilding()) {
				continue;
			}
			if (!isIdle(unit)) {
				continue;
			}
			if (unit.getClassifications().contains(CUnitClassification.PEON)) {
				final CUnit goldMine = CBehaviorReturnResources.findNearestMine(unit, game);
				if (goldMine != null) {
					unit.order(game, OrderIds.harvest, goldMine);
				}
			}
			else if (enemyStructure != null) {
				unit.order(game, OrderIds.attack, enemyStructure);
			}
		}
	}

	/** Pick any enemy-owned structure as the attack-move target. We don't
	 *  bother optimising for "closest" or "weakest" yet — the goal is just
	 *  to direct units somewhere, and structures are the win condition so
	 *  they're the right thing to head for. */
	private AbilityPointTarget findEnemyStructure(final CSimulation game, final CPlayer self) {
		for (final CUnit unit : game.getUnits()) {
			if (unit.isDead() || !unit.isBuilding()) {
				continue;
			}
			if (unit.getPlayerIndex() == this.playerIndex) {
				continue;
			}
			final CPlayer owner = game.getPlayer(unit.getPlayerIndex());
			if ((owner == null) || (owner.getSlotState() != CPlayerSlotState.PLAYING)) {
				continue;
			}
			// Skip allies — both the "we passively coexist" treaty (default
			// for neutrals) and any explicit alliance set by the map's
			// trigger script.
			if (self.hasAlliance(owner.getId(), CAllianceType.PASSIVE)) {
				continue;
			}
			return new AbilityPointTarget(unit.getX(), unit.getY());
		}
		return null;
	}

	private static boolean isIdle(final CUnit unit) {
		final CBehavior current = unit.getCurrentBehavior();
		if (current == null) {
			return true;
		}
		return current.getBehaviorCategory() == CBehaviorCategory.IDLE;
	}
}
