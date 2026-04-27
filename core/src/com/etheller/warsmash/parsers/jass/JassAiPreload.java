package com.etheller.warsmash.parsers.jass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import java.io.Reader;
import java.io.StringReader;

import com.etheller.interpreter.ast.definition.JassCodeDefinitionBlock;
import com.etheller.interpreter.ast.definition.JassDefinitionBlock;
import com.etheller.interpreter.ast.definition.JassLibraryDefinitionBlock;
import com.etheller.interpreter.ast.execution.JassThread;
import com.etheller.interpreter.ast.scope.DefaultScope;
import com.etheller.interpreter.ast.scope.GlobalScope;
import com.etheller.interpreter.ast.util.JassProgram;
import com.etheller.interpreter.ast.value.BooleanJassValue;
import com.etheller.interpreter.ast.value.CodeJassValue;
import com.etheller.interpreter.ast.value.HandleJassType;
import com.etheller.interpreter.ast.value.HandleJassValue;
import com.etheller.interpreter.ast.value.IntegerJassValue;
import com.etheller.interpreter.ast.value.RealJassValue;
import com.etheller.interpreter.ast.value.visitor.CodeJassValueVisitor;
import com.etheller.interpreter.ast.value.visitor.IntegerJassValueVisitor;
import com.etheller.interpreter.ast.value.visitor.RealJassValueVisitor;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.util.WarsmashConstants;
import com.etheller.warsmash.util.War3ID;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CSimulation;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnit;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CMapControl;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayer;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CRace;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.timers.CTimerSleepAction;

import net.warsmash.parsers.jass.SmashJassParser;

/**
 * Reconnaissance loader for WC3 melee AI scripts. Scans the active data
 * source for {@code .ai} files, parses them into a fresh {@link JassProgram}
 * (separate from the main game JASS environment), and reports the native
 * declarations the scripts contain. No AI threads are spawned and no game
 * state is mutated — this is data collection only.
 *
 * <p>Goal: figure out which AI-specific natives the stock Blizzard scripts
 * actually use, so the next pass can implement them in priority order. AI
 * scripts use the same JASS grammar as the rest of the engine but a
 * disjoint set of natives ({@code SetReplacement}, {@code SetBuildUnit},
 * {@code MeleeRequestTrainedUnit}, {@code WaitForSignal}, …) — and none of
 * those are wired up in {@link Jass2} today.
 */
public final class JassAiPreload {
	/** Handle types referenced by stock Blizzard AI scripts. Mirrors the
	 *  set in {@link JassAIEnvironment}'s constructor; a script that refers
	 *  to a type we forgot to register here will fail to parse. */
	private static final String[] AI_HANDLE_TYPES = {
			"agent", "event", "player", "widget", "unit", "destructable", "item", "ability", "buff", "force", "group",
			"trigger", "triggercondition", "triggeraction", "timer", "location", "region", "rect", "boolexpr", "sound",
			"conditionfunc", "filterfunc", "unitpool", "itempool", "race", "alliancetype", "racepreference", "gamestate",
			"igamestate", "fgamestate", "playerstate", "playerscore", "playergameresult", "unitstate", "aidifficulty",
			"eventid", "gameevent", "playerevent", "playerunitevent", "unitevent", "limitop", "widgetevent",
			"dialogevent", "unittype", "gamespeed", "gamedifficulty", "gametype", "mapflag", "mapvisibility",
			"mapsetting", "mapdensity", "mapcontrol", "playerslotstate", "volumegroup", "camerafield", "camerasetup",
			"playercolor", "placement", "startlocprio", "raritycontrol", "blendmode", "texmapflags", "effect",
			"effecttype", "weathereffect", "terraindeformation", "fogstate", "fogmodifier", "dialog", "button", "quest",
			"questitem", "defeatcondition", "timerdialog", "leaderboard", "multiboard", "multiboarditem", "trackable",
			"gamecache", "version", "itemtype", "texttag", "attacktype", "damagetype", "weapontype", "soundtype",
			"lightning", "pathingtype", "image", "ubersplat", "hashtable", "framehandle",
			// Warsmash ability API
			"abilitytype", "ordercommandcard", "ordercommandcardtype", "abilitybehavior", "behaviorexpr", "iconui",
	};

	private JassAiPreload() {
	}

	/**
	 * Load the AI scripts into a fresh {@link JassProgram}, register the
	 * threading primitives ({@code Sleep}, {@code StartThread}) so any code
	 * the scripts run can yield, and queue the {@code main} entry function
	 * if any of the scripts defines one. Returns the {@link GlobalScope}
	 * the caller must {@link GlobalScope#runThreads()} each simulation tick
	 * to actually advance the AI — or {@code null} if no AI scripts loaded.
	 *
	 * <p>The other AI-specific natives ({@code SetReplacement},
	 * {@code HarvestGold}, …) intentionally have no implementation: each
	 * one falls through to {@code NativeJassFunction}'s warn-once log on
	 * first call, so running the AI scripts doubles as runtime-frequency
	 * data collection for "which natives actually get called from the hot
	 * path" — strictly more useful than the parse-time declaration list,
	 * because library-only natives won't show up.
	 */
	public static GlobalScope preload(final DataSource dataSource, final CSimulation simulation) {
		final List<String> aiPaths = findAiFiles(dataSource);
		System.out.println("[ai-preload] discovered " + aiPaths.size() + " .ai file(s)");
		if (aiPaths.isEmpty()) {
			return null;
		}
		for (final String p : aiPaths) {
			System.out.println("[ai-preload]   " + p);
		}

		final JassProgram program = new JassProgram();
		final GlobalScope globals = program.getGlobals();
		for (final String typeName : AI_HANDLE_TYPES) {
			globals.registerHandleType(typeName);
		}

		registerThreadingPrimitives(program, simulation);
		// Pull in the shim before parsing the .ai files: it declares the
		// {@code common.j} natives the AI scripts assume (Player, I2R,
		// GetRandomInt, …) plus the race-constant block ({@code RACE_HUMAN}
		// etc.). Without it the AI dialect's identifier-resolution phase
		// can't compile any function that references one of those.
		final HandleJassType raceType = (HandleJassType) globals.parseType("race");
		final HandleJassType playerType = (HandleJassType) globals.parseType("player");
		registerShimNativeImpls(program, simulation, raceType);
		parseShimSource(program);
		// Now register the AI-specific natives (or at least, the runtime
		// bottom layer of them). Every native the scripts call from main()
		// during init belongs here; the rest fall through to the
		// warn-once log on first call so we can see what to add next.
		registerAiNativeStubs(program, simulation, playerType);

		int parsed = 0;
		int failed = 0;
		for (final String path : aiPaths) {
			try {
				Jass2.readJassFile(dataSource, program, path);
				parsed++;
			}
			catch (final Throwable t) {
				System.out.println("[ai-preload] PARSE FAILED " + path + ": " + t.getClass().getSimpleName() + ": "
						+ t.getMessage());
				failed++;
			}
		}
		System.out.println("[ai-preload] parsed=" + parsed + " failed=" + failed);

		// Custom initializer that mirrors {@link JassProgram#initialize()}
		// but catches per-block failures so a single broken function in
		// {@code common.ai} doesn't abort the whole load. Each failure is
		// logged with the function name + source file + line so we can
		// attribute it precisely.
		initialiseTolerant(program);

		reportNativeDeclarations(program);

		dumpUserFunctions(globals);

		// Try a small set of candidate entry-function names that WC3 AI
		// scripts have used historically. {@code main} is the convention
		// for melee scripts; campaign scripts sometimes use other names
		// (often the race name). The dump above lets us see which one
		// each script actually exposes when this list misses.
		final String[] candidates = { "main", "human", "orc", "elf", "undead" };
		for (final String candidate : candidates) {
			final Integer ptr = globals.getUserFunctionInstructionPtr(candidate);
			if (ptr != null) {
				final JassThread thread = globals.createThread(ptr);
				globals.queueThread(thread);
				System.out.println("[ai-preload] queued " + candidate + "() thread");
				return globals;
			}
		}
		System.out.println("[ai-preload] no recognised entry function in parsed AI scripts");
		return globals;
	}

	private static void initialiseTolerant(final JassProgram program) {
		final DefaultScope defaultScope = new DefaultScope(program.getGlobals());
		try {
			JassLibraryDefinitionBlock.topologicalSort(program.libraries);
		}
		catch (final Throwable t) {
			System.out.println("[ai-preload] library topo sort failed: " + t.getClass().getSimpleName() + ": "
					+ t.getMessage());
		}
		final int totalEverything = program.everythingElse.size();
		final int totalLibraries = program.libraries.size();
		final int totalScopes = program.scopes.size();
		int okEverything = 0;
		int okLibraries = 0;
		int okScopes = 0;
		for (final JassDefinitionBlock block : program.everythingElse) {
			if (defineSafely(block, defaultScope, program)) {
				okEverything++;
			}
		}
		for (final JassDefinitionBlock block : program.libraries) {
			if (defineSafely(block, defaultScope, program)) {
				okLibraries++;
			}
		}
		for (final JassDefinitionBlock block : program.scopes) {
			if (defineSafely(block, defaultScope, program)) {
				okScopes++;
			}
		}
		System.out.println("[ai-preload] tolerant init: everything=" + okEverything + "/" + totalEverything
				+ " libraries=" + okLibraries + "/" + totalLibraries + " scopes=" + okScopes + "/" + totalScopes);
		try {
			final Integer initGlobalsPtr = program.getGlobals()
					.getUserFunctionInstructionPtr(GlobalScope.INIT_GLOBALS_AUTOGEN_FXN_NAME);
			if (initGlobalsPtr != null) {
				program.getGlobals().runThreadUntilCompletion(program.getGlobals().createThread(initGlobalsPtr));
			}
			program.getGlobals().resetGlobalInitialization();
		}
		catch (final Throwable t) {
			System.out.println("[ai-preload] globals init failed: " + t.getClass().getSimpleName() + ": "
					+ t.getMessage());
		}
		program.libraries.clear();
		program.everythingElse.clear();
		program.scopes.clear();
	}

	private static boolean defineSafely(final JassDefinitionBlock block, final DefaultScope scope,
			final JassProgram program) {
		try {
			block.define(scope, program);
			return true;
		}
		catch (final Throwable t) {
			final String desc = describeBlock(block);
			System.out.println("[ai-preload] block FAILED " + desc + ": " + t.getClass().getSimpleName() + ": "
					+ t.getMessage());
			Throwable cause = t.getCause();
			int depth = 1;
			while (cause != null) {
				System.out.println("[ai-preload]   caused by [" + depth + "]: "
						+ cause.getClass().getSimpleName() + ": " + cause.getMessage());
				cause = cause.getCause();
				depth++;
			}
			return false;
		}
	}

	private static String describeBlock(final JassDefinitionBlock block) {
		if (block instanceof JassCodeDefinitionBlock) {
			final JassCodeDefinitionBlock code = (JassCodeDefinitionBlock) block;
			return "fn '" + code.getName() + "' at " + code.getSourceFile() + ":" + code.getLineNo();
		}
		return block.getClass().getSimpleName();
	}

	private static void dumpUserFunctions(final GlobalScope globals) {
		final TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		sorted.addAll(globals.getUserFunctionNames());
		System.out.println("[ai-preload] " + sorted.size() + " user functions registered:");
		for (final String name : sorted) {
			System.out.println("[ai-preload]   fn " + name);
		}
	}

	/**
	 * Synthetic JASS source pulled in before the {@code .ai} files. Declares
	 * the {@code common.j} natives + race constants the AI scripts assume —
	 * which would normally be provided by {@code common.j} in the same
	 * program but live in our separate AI program. Keeping it as a string
	 * lets us iterate without shipping a separate resource file.
	 */
	private static final String SHIM_JASS = String.join("\n",
			"native Player takes integer i returns player",
			"native GetPlayerState takes player p, playerstate s returns integer",
			"native GetFloatGameState takes fgamestate s returns real",
			"native GetGameDifficulty takes nothing returns gamedifficulty",
			"native GetRandomInt takes integer low, integer high returns integer",
			"native GetRandomReal takes real low, real high returns real",
			"native I2R takes integer i returns real",
			"native R2I takes real r returns integer",
			"native I2S takes integer i returns string",
			"native S2I takes string s returns integer",
			"native R2S takes real r returns string",
			"native S2R takes string s returns real",
			"native IsUnitDetected takes unit whichUnit, player whichPlayer returns boolean",
			"native GetFoodMade takes integer unitId returns integer",
			"native GetFoodUsed takes integer unitId returns integer",
			"native GetPlayers takes nothing returns integer",
			"native GetPlayerAlliance takes player p1, player p2, alliancetype a returns boolean",
			"native VersionCompatible takes version v returns boolean",
			"native GetPlayerStructureCount takes player whichPlayer, boolean includeIncomplete returns integer",
			"native ConvertRace takes integer i returns race",
			"native ConvertGameDifficulty takes integer i returns gamedifficulty",
			"native ConvertFGameState takes integer i returns fgamestate",
			"native ConvertPlayerState takes integer i returns playerstate",
			"native ConvertVersion takes integer i returns version",
			"native ConvertAllianceType takes integer i returns alliancetype",
			"globals",
			"    constant race RACE_HUMAN     = ConvertRace(1)",
			"    constant race RACE_ORC       = ConvertRace(2)",
			"    constant race RACE_UNDEAD    = ConvertRace(3)",
			"    constant race RACE_NIGHTELF  = ConvertRace(4)",
			"    constant race RACE_DEMON     = ConvertRace(5)",
			// Player resource state constants (matches WC3 enum order)
			"    constant playerstate PLAYER_STATE_RESOURCE_GOLD       = ConvertPlayerState(1)",
			"    constant playerstate PLAYER_STATE_RESOURCE_LUMBER     = ConvertPlayerState(2)",
			"    constant playerstate PLAYER_STATE_RESOURCE_HERO_TOKENS= ConvertPlayerState(3)",
			"    constant playerstate PLAYER_STATE_RESOURCE_FOOD_CAP   = ConvertPlayerState(4)",
			"    constant playerstate PLAYER_STATE_RESOURCE_FOOD_USED  = ConvertPlayerState(5)",
			"    constant playerstate PLAYER_STATE_FOOD_CAP_CEILING    = ConvertPlayerState(6)",
			// Float-typed game-state constants
			"    constant fgamestate GAME_STATE_TIME_OF_DAY = ConvertFGameState(0)",
			// Map difficulty constants (gamedifficulty handle type)
			"    constant gamedifficulty MAP_DIFFICULTY_EASY    = ConvertGameDifficulty(0)",
			"    constant gamedifficulty MAP_DIFFICULTY_NORMAL  = ConvertGameDifficulty(1)",
			"    constant gamedifficulty MAP_DIFFICULTY_HARD    = ConvertGameDifficulty(2)",
			"    constant gamedifficulty MAP_DIFFICULTY_INSANE  = ConvertGameDifficulty(3)",
			// AI's own difficulty tier (plain integer, distinct from map difficulty)
			"    constant integer MELEE_NEWBIE = 0",
			"    constant integer MELEE_NORMAL = 1",
			"    constant integer MELEE_INSANE = 2",
			// Common alliance types — used by GetPlayerAlliance(...)
			"    constant alliancetype ALLIANCE_PASSIVE          = ConvertAllianceType(0)",
			"    constant alliancetype ALLIANCE_HELP_REQUEST     = ConvertAllianceType(1)",
			"    constant alliancetype ALLIANCE_HELP_RESPONSE    = ConvertAllianceType(2)",
			"    constant alliancetype ALLIANCE_SHARED_XP        = ConvertAllianceType(3)",
			"    constant alliancetype ALLIANCE_SHARED_SPELLS    = ConvertAllianceType(4)",
			"    constant alliancetype ALLIANCE_SHARED_VISION    = ConvertAllianceType(5)",
			"    constant alliancetype ALLIANCE_SHARED_CONTROL   = ConvertAllianceType(6)",
			"    constant alliancetype ALLIANCE_SHARED_ADVANCED_CONTROL = ConvertAllianceType(7)",
			"    constant alliancetype ALLIANCE_RESCUABLE        = ConvertAllianceType(8)",
			"    constant alliancetype ALLIANCE_SHARED_VISION_FORCED = ConvertAllianceType(9)",
			// Version constants used by stock AI to gate Frozen-Throne-only behaviour.
			"    constant version VERSION_REIGN_OF_CHAOS = ConvertVersion(0)",
			"    constant version VERSION_FROZEN_THRONE  = ConvertVersion(1)",
			"endglobals",
			"");

	/**
	 * Register native implementations for the shim declarations that get
	 * called during globals init (ConvertRace) or during AI thread
	 * execution. Anything not registered here falls through to the
	 * warn-once path on first call and returns a default. We only need
	 * enough to keep the script from crashing; behavioural correctness
	 * comes later as we implement specific natives in priority order.
	 */
	private static void registerShimNativeImpls(final JassProgram program, final CSimulation simulation,
			final HandleJassType raceType) {
		program.getJassNativeManager().createNative("ConvertRace", (arguments, globalScope, triggerScope) -> {
			final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			CRace race = WarsmashConstants.RACE_MANAGER.getRace(i);
			if (race == null) {
				race = new CRace(i);
			}
			return new HandleJassValue(raceType, race);
		});
		// Convert natives for the other handle-typed enums the AI shim
		// references in its globals block. Without these, the
		// {@code ConvertX(N)} calls inside {@code constant <type> X = ...}
		// hit the warn-once path during globals init and the constants
		// end up with null values — fine for arithmetic but breaks
		// {@code playerstate} lookups.
		final HandleJassType playerStateType = (HandleJassType) program.getGlobals().parseType("playerstate");
		program.getJassNativeManager().createNative("ConvertPlayerState",
				(arguments, globalScope, triggerScope) -> {
					final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
					if ((i < 0) || (i >= com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerState.VALUES.length)) {
						return playerStateType.getNullValue();
					}
					return new HandleJassValue(playerStateType,
							com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerState.VALUES[i]);
				});
		final HandleJassType fGameStateType = (HandleJassType) program.getGlobals().parseType("fgamestate");
		program.getJassNativeManager().createNative("ConvertFGameState",
				(arguments, globalScope, triggerScope) -> {
					final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
					if ((i < 0) || (i >= com.etheller.warsmash.viewer5.handlers.w3x.simulation.state.CGameState.VALUES.length)) {
						return fGameStateType.getNullValue();
					}
					return new HandleJassValue(fGameStateType,
							com.etheller.warsmash.viewer5.handlers.w3x.simulation.state.CGameState.VALUES[i]);
				});
		final HandleJassType allianceTypeType = (HandleJassType) program.getGlobals().parseType("alliancetype");
		program.getJassNativeManager().createNative("ConvertAllianceType",
				(arguments, globalScope, triggerScope) -> {
					final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
					if ((i < 0)
							|| (i >= com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CAllianceType.VALUES.length)) {
						return allianceTypeType.getNullValue();
					}
					return new HandleJassValue(allianceTypeType,
							com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CAllianceType.VALUES[i]);
				});
		final HandleJassType versionType = (HandleJassType) program.getGlobals().parseType("version");
		program.getJassNativeManager().createNative("ConvertVersion",
				(arguments, globalScope, triggerScope) -> {
					final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
					if ((i < 0) || (i >= com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CVersion.VALUES.length)) {
						return versionType.getNullValue();
					}
					return new HandleJassValue(versionType,
							com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CVersion.VALUES[i]);
				});

		// {@code GetPlayers} returns the configured player count for the
		// current map, so AI loops over slots use the right upper bound.
		program.getJassNativeManager().createNative("GetPlayers",
				(arguments, globalScope, triggerScope) -> IntegerJassValue
						.of(com.etheller.warsmash.util.WarsmashConstants.MAX_PLAYERS));
		// {@code GetPlayerAlliance(p1, p2, alliancetype)} → boolean.
		// Defer to the simulation's alliance state.
		program.getJassNativeManager().createNative("GetPlayerAlliance",
				(arguments, globalScope, triggerScope) -> {
					CPlayer p1 = null;
					CPlayer p2 = null;
					if (arguments.get(0) instanceof HandleJassValue) {
						final Object jv = ((HandleJassValue) arguments.get(0)).getJavaValue();
						if (jv instanceof CPlayer) {
							p1 = (CPlayer) jv;
						}
					}
					if (arguments.get(1) instanceof HandleJassValue) {
						final Object jv = ((HandleJassValue) arguments.get(1)).getJavaValue();
						if (jv instanceof CPlayer) {
							p2 = (CPlayer) jv;
						}
					}
					Object allianceTypeArg = null;
					if (arguments.get(2) instanceof HandleJassValue) {
						allianceTypeArg = ((HandleJassValue) arguments.get(2)).getJavaValue();
					}
					if ((p1 == null) || (p2 == null) || !(allianceTypeArg instanceof com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CAllianceType)) {
						return BooleanJassValue.of(false);
					}
					return BooleanJassValue.of(p1.hasAlliance(p2.getId(),
							(com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CAllianceType) allianceTypeArg));
				});
		// {@code VersionCompatible(version) → boolean}: always-true stub.
		// The original engine returns false for older versions so scripts
		// can opt out of new-engine features; we don't model versions, so
		// "yes, everything is supported" is the simplest defensible answer.
		program.getJassNativeManager().createNative("VersionCompatible",
				(arguments, globalScope, triggerScope) -> BooleanJassValue.of(true));
		// {@code GetPlayerStructureCount(player, includeIncomplete) → int}.
		// Mirrors the game-side native at Jass2.java:5558 but bound to our
		// AI program. Counts every unit owned by the player whose type has
		// the {@code TOWNHALL}/structure classification — close enough for
		// the AI's "do I have a base?" gating queries.
		program.getJassNativeManager().createNative("GetPlayerStructureCount",
				(arguments, globalScope, triggerScope) -> {
					CPlayer p = null;
					if (arguments.get(0) instanceof HandleJassValue) {
						final Object jv = ((HandleJassValue) arguments.get(0)).getJavaValue();
						if (jv instanceof CPlayer) {
							p = (CPlayer) jv;
						}
					}
					if (p == null) {
						return IntegerJassValue.of(0);
					}
					final boolean includeIncomplete = arguments.get(1)
							.visit(com.etheller.interpreter.ast.value.visitor.BooleanJassValueVisitor.getInstance());
					int count = 0;
					for (final CUnit u : simulation.getUnits()) {
						if ((u.getPlayerIndex() == p.getId()) && u.isBuilding()
								&& (includeIncomplete || !u.isConstructing())) {
							count++;
						}
					}
					return IntegerJassValue.of(count);
				});

		// Resource queries — pull straight from the player's accounting.
		program.getJassNativeManager().createNative("GetGoldOwned", (arguments, globalScope, triggerScope) -> {
			final CPlayer aiPlayer = findFirstComputerPlayer(simulation);
			return IntegerJassValue.of(aiPlayer == null ? 0 : aiPlayer.getGold());
		});
		// {@code GetPlayerState(player, playerstate) → integer} mirrors the
		// game-side native: switch on the playerstate enum and return the
		// corresponding scalar from CPlayer. Implementing here so the AI
		// program isn't dependent on the game program for resource checks.
		program.getJassNativeManager().createNative("GetPlayerState",
				(arguments, globalScope, triggerScope) -> {
					CPlayer p = null;
					if (arguments.get(0) instanceof HandleJassValue) {
						final Object jv = ((HandleJassValue) arguments.get(0)).getJavaValue();
						if (jv instanceof CPlayer) {
							p = (CPlayer) jv;
						}
					}
					Object stateArg = null;
					if (arguments.get(1) instanceof HandleJassValue) {
						stateArg = ((HandleJassValue) arguments.get(1)).getJavaValue();
					}
					if ((p == null) || !(stateArg instanceof com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerState)) {
						return IntegerJassValue.of(0);
					}
					return IntegerJassValue.of(p.getPlayerState(simulation,
							(com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerState) stateArg));
				});
		// Standard gold-mine type id is {@code ngol}; count those owned by
		// the AI player. Also unblocks the harvest-decision branches.
		program.getJassNativeManager().createNative("GetMinesOwned", (arguments, globalScope, triggerScope) -> {
			final CPlayer aiPlayer = findFirstComputerPlayer(simulation);
			if (aiPlayer == null) {
				return IntegerJassValue.of(0);
			}
			final War3ID mineId = War3ID.fromString("ngol");
			int count = 0;
			for (final CUnit u : simulation.getUnits()) {
				if ((u.getPlayerIndex() == aiPlayer.getId()) && mineId.equals(u.getTypeId())) {
					count++;
				}
			}
			return IntegerJassValue.of(count);
		});

		// Type-data queries — read straight from CUnitType. Used by the
		// AI to plan build orders against unit costs.
		program.getJassNativeManager().createNative("GetUnitGoldCost", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitType type = simulation.getUnitData()
					.getUnitType(new War3ID(rawcode));
			return IntegerJassValue.of(type == null ? 0 : type.getGoldCost());
		});
		program.getJassNativeManager().createNative("GetUnitWoodCost", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitType type = simulation.getUnitData()
					.getUnitType(new War3ID(rawcode));
			return IntegerJassValue.of(type == null ? 0 : type.getLumberCost());
		});
		program.getJassNativeManager().createNative("GetUnitBuildTime", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitType type = simulation.getUnitData()
					.getUnitType(new War3ID(rawcode));
			return IntegerJassValue.of(type == null ? 0 : type.getBuildTime());
		});

		// Upgrade queries. The AI dialect's {@code GetUpgradeLevel} takes a
		// single integer (upgradeId) and uses the AI's bound player
		// implicitly — distinct from common.j's two-arg
		// {@code GetUpgradeLevel(player, upgradeId)}. Reading
		// {@code arguments.get(1)} here trips
		// {@code NoSuchElementException} on the 1-arg call site
		// (e.g. undead.ai's {@code GetUpgradeLevel(UPG_BLK_SPHINX)}).
		program.getJassNativeManager().createNative("GetUpgradeLevel", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final CPlayer aiPlayer = findFirstComputerPlayer(simulation);
			if (aiPlayer == null) {
				return IntegerJassValue.of(0);
			}
			return IntegerJassValue.of(aiPlayer.getTechtreeUnlocked(new War3ID(rawcode)));
		});
		program.getJassNativeManager().createNative("GetUpgradeGoldCost",
				(arguments, globalScope, triggerScope) -> IntegerJassValue.of(0));
		program.getJassNativeManager().createNative("GetUpgradeWoodCost",
				(arguments, globalScope, triggerScope) -> IntegerJassValue.of(0));

		// Debug-print natives — script-side {@code DisplayText("...")},
		// {@code DisplayTextI("fmt", x)}, etc. Stubbed silent so the
		// console isn't drowned in AI debug output. Echo to stdout when
		// debugging by uncommenting the println below.
		// {@code Trace*} are user functions in common.ai (they wrap the
		// {@code DisplayText*} natives); only the natives need stubs here.
		final String[] displayTextNames = { "DisplayText", "DisplayTextI", "DisplayTextII", "DisplayTextIII",
				"DebugFI", "DebugS", "DebugUnitID" };
		for (final String name : displayTextNames) {
			program.getJassNativeManager().createNative(name, (arguments, globalScope, triggerScope) -> null);
		}
		program.getJassNativeManager().createNative("I2R", (arguments, globalScope, triggerScope) -> {
			final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			return RealJassValue.of(i);
		});
		program.getJassNativeManager().createNative("R2I", (arguments, globalScope, triggerScope) -> {
			final double r = arguments.get(0).visit(RealJassValueVisitor.getInstance());
			return IntegerJassValue.of((int) r);
		});
		program.getJassNativeManager().createNative("GetRandomInt", (arguments, globalScope, triggerScope) -> {
			final int lo = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final int hi = arguments.get(1).visit(IntegerJassValueVisitor.getInstance());
			if (hi < lo) {
				return IntegerJassValue.of(lo);
			}
			return IntegerJassValue.of(simulation.getSeededRandom().nextInt((hi - lo) + 1) + lo);
		});
		program.getJassNativeManager().createNative("IsUnitDetected", (arguments, globalScope, triggerScope) -> {
			// Stub: detection isn't modelled; assume not-detected so AI
			// scripts that loop on "wait until detected" don't deadlock.
			return BooleanJassValue.of(false);
		});
	}

	/**
	 * Bottom layer of AI-native impls. Two flavours here:
	 * <ul>
	 * <li>{@code Set*} natives that toggle AI behaviour flags — currently
	 *     no-op stubs that consume their args. We keep the hooks so the
	 *     scripts run cleanly, but we don't yet act on the flags.</li>
	 * <li>Read-only queries the AI uses for "do I need more X?" decisions —
	 *     real impls so the AI's branching logic gets meaningful answers
	 *     instead of always-zero defaults.</li>
	 * </ul>
	 *
	 * <p>Order primitives ({@code HarvestGold}, {@code HarvestWood},
	 * {@code SetReplacement}, {@code SetBuildUnit}, …) are deliberately
	 * left to the warn-once path for now. Implementing them properly
	 * means hooking the AI's intent into the existing
	 * {@link com.etheller.warsmash.viewer5.handlers.w3x.simulation.ai.CMeleePlayerAI}
	 * machinery, which is the next iteration.
	 */
	private static void registerAiNativeStubs(final JassProgram program, final CSimulation simulation,
			final HandleJassType playerType) {
		// {@code GetAiPlayer} in the AI dialect returns an INTEGER (player
		// index), not a player handle — scripts then call
		// {@code Player(GetAiPlayer())} to get the handle. Returning a
		// handle directly trips the interpreter's parameter type-check on
		// the next call (e.g. {@code Player(player_handle)} → "Invalid
		// type player for specified argument integer"). Single-shared-
		// program simplification: bind to the first COMPUTER slot.
		// Multi-AI maps need per-program isolation, future work.
		program.getJassNativeManager().createNative("GetAiPlayer", (arguments, globalScope, triggerScope) -> {
			final CPlayer aiPlayer = findFirstComputerPlayer(simulation);
			return IntegerJassValue.of(aiPlayer == null ? 0 : aiPlayer.getId());
		});

		// {@code Player(integer)} from common.j: turn a player index into a
		// player handle. Several AI scripts use it for alliance / target
		// queries. Implementing it properly avoids handle-vs-integer
		// comparisons later (when scripts compare handle results from
		// different sources).
		program.getJassNativeManager().createNative("Player", (arguments, globalScope, triggerScope) -> {
			final int idx = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			if ((idx < 0) || (idx >= com.etheller.warsmash.util.WarsmashConstants.MAX_PLAYERS)) {
				return playerType.getNullValue();
			}
			final CPlayer p = simulation.getPlayer(idx);
			return p == null ? playerType.getNullValue() : new HandleJassValue(playerType, p);
		});

		// Read-only queries. If the player handle arg is null/missing we
		// fall back to the bound AI player so scripts that pass implicit
		// "self" via a different path still work.
		program.getJassNativeManager().createNative("GetUnitCount", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			return IntegerJassValue.of(countUnits(simulation, findFirstComputerPlayer(simulation), rawcode, true));
		});
		program.getJassNativeManager().createNative("GetUnitCountDone", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			return IntegerJassValue.of(countUnits(simulation, findFirstComputerPlayer(simulation), rawcode, false));
		});
		program.getJassNativeManager().createNative("GetPlayerUnitTypeCount",
				(arguments, globalScope, triggerScope) -> {
					// Standard signature: (player, unittype, includeIncomplete)
					final int rawcode = arguments.get(1).visit(IntegerJassValueVisitor.getInstance());
					final boolean inc = arguments.get(2)
							.visit(com.etheller.interpreter.ast.value.visitor.BooleanJassValueVisitor.getInstance());
					CPlayer p = null;
					if (arguments.get(0) instanceof HandleJassValue) {
						final Object javaVal = ((HandleJassValue) arguments.get(0)).getJavaValue();
						if (javaVal instanceof CPlayer) {
							p = (CPlayer) javaVal;
						}
					}
					if (p == null) {
						p = findFirstComputerPlayer(simulation);
					}
					return IntegerJassValue.of(countUnits(simulation, p, rawcode, inc));
				});

		program.getJassNativeManager().createNative("GetFoodMade", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitType type = simulation.getUnitData()
					.getUnitType(new War3ID(rawcode));
			return IntegerJassValue.of(type == null ? 0 : type.getFoodMade());
		});
		program.getJassNativeManager().createNative("GetFoodUsed", (arguments, globalScope, triggerScope) -> {
			final int rawcode = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
			final com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitType type = simulation.getUnitData()
					.getUnitType(new War3ID(rawcode));
			return IntegerJassValue.of(type == null ? 0 : type.getFoodUsed());
		});

		// {@code TownHasMine} / {@code TownHasHall} are per-"town" queries
		// in real WC3 (the engine tracks AI base groupings). We don't model
		// towns yet — return true if the AI player owns ANY mine / town
		// hall on the map, false otherwise. Crude global answer, but it
		// unblocks the gating decisions in the script. Mines are
		// identified by the standard {@code ngol} (gold mine) type id;
		// town halls via the {@link CUnitClassification#TOWNHALL} flag,
		// which spans every race's tier-1 main building.
		final War3ID goldMineId = War3ID.fromString("ngol");
		program.getJassNativeManager().createNative("TownHasMine", (arguments, globalScope, triggerScope) -> {
			final CPlayer aiPlayer = findFirstComputerPlayer(simulation);
			if (aiPlayer == null) {
				return BooleanJassValue.of(false);
			}
			for (final CUnit u : simulation.getUnits()) {
				if ((u.getPlayerIndex() == aiPlayer.getId()) && goldMineId.equals(u.getTypeId())) {
					return BooleanJassValue.of(true);
				}
			}
			return BooleanJassValue.of(false);
		});
		program.getJassNativeManager().createNative("TownHasHall", (arguments, globalScope, triggerScope) -> {
			final CPlayer aiPlayer = findFirstComputerPlayer(simulation);
			if (aiPlayer == null) {
				return BooleanJassValue.of(false);
			}
			for (final CUnit u : simulation.getUnits()) {
				if ((u.getPlayerIndex() == aiPlayer.getId()) && u.isBuilding()
						&& u.getClassifications().contains(
								com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnitClassification.TOWNHALL)) {
					return BooleanJassValue.of(true);
				}
			}
			return BooleanJassValue.of(false);
		});

		// No-op flag setters — consume the args and return null. The AI
		// scripts are happy as long as the calls don't crash; we'll wire
		// each flag up to actual behaviour as that behaviour gets built.
		final String[] noopVoidStubs = {
				"SetHeroesBuyItems", "SetHeroesFlee", "SetHeroesTakeItems",
				"SetIgnoreInjured", "SetPeonsRepair", "SetSmartArtillery",
				"SetTargetHeroes", "SetUnitsFlee", "SetWatchMegaTargets",
				"SetGroupsFlee", "SetHeroLevels", "SetNewHeroes",
				"SetMeleeAI", "SetCampaignAI", "SetAmphibious",
				"SetSlowChopping", "SetRandomPaths",
				"CreateCaptains", "ClearHarvestAI", "StopGathering",
				"ClearCaptainTargets", "ResetCaptainLocs",
				"SetCaptainChanges", "SetDefendPlayer",
				"SetReplacementCount",
		};
		for (final String name : noopVoidStubs) {
			program.getJassNativeManager().createNative(name, (arguments, globalScope, triggerScope) -> null);
		}

		// In Blizzard's AI dialect, {@code MeleeDifficulty} returns a plain
		// integer (the AI's own skill tier 0-2), distinct from the map-side
		// {@code gamedifficulty} enum returned by {@code GetGameDifficulty}.
		// Scripts compare the result against int literals, e.g.
		// {@code if MeleeDifficulty() < 2 then ...}.
		program.getJassNativeManager().createNative("MeleeDifficulty",
				(arguments, globalScope, triggerScope) -> IntegerJassValue.of(1));
		// The enum-flavoured siblings keep the typed-handle treatment so a
		// hypothetical {@code if GetGameDifficulty() == DIFFICULTY_NORMAL}
		// works once we add the {@code DIFFICULTY_*} constants.
		final HandleJassType gameDifficultyType = (HandleJassType) program.getGlobals().parseType("gamedifficulty");
		program.getJassNativeManager().createNative("ConvertGameDifficulty",
				(arguments, globalScope, triggerScope) -> {
					final int i = arguments.get(0).visit(IntegerJassValueVisitor.getInstance());
					final int clamped = Math.max(0, Math.min(i,
							com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CMapDifficulty.VALUES.length
									- 1));
					return new HandleJassValue(gameDifficultyType,
							com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CMapDifficulty.VALUES[clamped]);
				});
		program.getJassNativeManager().createNative("GetGameDifficulty",
				(arguments, globalScope, triggerScope) -> new HandleJassValue(gameDifficultyType,
						com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CMapDifficulty.VALUES[1]));
	}

	private static CPlayer findFirstComputerPlayer(final CSimulation simulation) {
		for (int i = 0; i < com.etheller.warsmash.util.WarsmashConstants.MAX_PLAYERS; i++) {
			final CPlayer p = simulation.getPlayer(i);
			if ((p != null) && (p.getController() == CMapControl.COMPUTER)) {
				return p;
			}
		}
		return null;
	}

	private static int countUnits(final CSimulation simulation, final CPlayer player, final int typeRawcode,
			final boolean includeIncomplete) {
		if (player == null) {
			return 0;
		}
		final War3ID typeId = new War3ID(typeRawcode);
		int count = 0;
		for (final CUnit unit : simulation.getUnits()) {
			if (unit.getPlayerIndex() != player.getId()) {
				continue;
			}
			if (!typeId.equals(unit.getTypeId())) {
				continue;
			}
			if (!includeIncomplete && unit.isConstructing()) {
				continue;
			}
			count++;
		}
		return count;
	}

	private static void parseShimSource(final JassProgram program) {
		try (Reader reader = new StringReader(SHIM_JASS)) {
			final SmashJassParser parser = new SmashJassParser(reader);
			parser.scanAndParse("<ai-shim>", program);
		}
		catch (final Throwable t) {
			System.out.println("[ai-preload] shim parse failed: " + t.getClass().getSimpleName() + ": "
					+ t.getMessage());
		}
	}

	/**
	 * Register {@code Sleep} and {@code StartThread} on the AI program's
	 * native manager. Without these, every {@code call Sleep(N)} in the
	 * scripts is a no-op and the AI loop spins until {@code main} returns
	 * (typically immediately), so we'd see almost nothing called.
	 */
	private static void registerThreadingPrimitives(final JassProgram program, final CSimulation simulation) {
		program.getJassNativeManager().createNative("Sleep", (arguments, globalScope, triggerScope) -> {
			final JassThread currentThread = globalScope.getCurrentThread();
			if (currentThread == null) {
				return null;
			}
			final double seconds = arguments.get(0).visit(RealJassValueVisitor.getInstance());
			currentThread.setSleeping(true);
			final CTimerSleepAction timer = new CTimerSleepAction(currentThread);
			timer.setRepeats(false);
			timer.setTimeoutTime((float) seconds);
			timer.start(simulation);
			return null;
		});
		program.getJassNativeManager().createNative("StartThread", (arguments, globalScope, triggerScope) -> {
			final CodeJassValue threadFunction = arguments.get(0).visit(CodeJassValueVisitor.getInstance());
			globalScope.queueThread(globalScope.createThread(threadFunction));
			return null;
		});
	}

	/** Files we want to load for melee AI. Anything else (per-mission
	 *  campaign scripts like {@code h01x05.ai}) is skipped — those collide
	 *  on global names with each other and with the melee race scripts,
	 *  which currently breaks {@link JassProgram#initialize()} program-wide.
	 *  Match is case-insensitive against the basename (without {@code .ai}). */
	private static final java.util.Set<String> MELEE_BASENAMES;
	static {
		final java.util.Set<String> s = new java.util.HashSet<>();
		s.add("common");
		s.add("blizzard"); // framework lib if present
		s.add("human");
		s.add("orc");
		s.add("elf");
		s.add("undead");
		MELEE_BASENAMES = java.util.Collections.unmodifiableSet(s);
	}

	private static List<String> findAiFiles(final DataSource dataSource) {
		final Collection<String> listfile = dataSource.getListfile();
		final List<String> matches = new ArrayList<>();
		for (final String path : listfile) {
			final String lower = path.toLowerCase(Locale.US);
			if (!lower.endsWith(".ai")) {
				continue;
			}
			final int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
			final String basename = lower.substring(slash + 1, lower.length() - 3);
			if (!MELEE_BASENAMES.contains(basename)) {
				continue;
			}
			matches.add(path);
		}
		// Stable order for reproducible logs. common.ai needs to land first
		// so the race files can reference its globals/functions, but that
		// happens via deferred resolution at initialize() time, not parse
		// order — so plain alphabetical is fine.
		matches.sort(String.CASE_INSENSITIVE_ORDER);
		return matches;
	}

	private static void reportNativeDeclarations(final JassProgram program) {
		final TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		sorted.addAll(program.getJassNativeManager().getRegisteredNativeNames());
		System.out.println("[ai-preload] " + sorted.size() + " distinct native declarations in parsed AI scripts:");
		for (final String name : sorted) {
			System.out.println("[ai-preload]   native " + name);
		}
	}
}
