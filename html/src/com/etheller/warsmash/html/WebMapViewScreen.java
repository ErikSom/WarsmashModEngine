package com.etheller.warsmash.html;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.etheller.warsmash.WarsmashGdxGame;
import com.etheller.warsmash.datasources.InMemoryDataSource;
import com.etheller.warsmash.networking.GameTurnManager;
import com.etheller.warsmash.parsers.w3x.War3Map;
import com.etheller.warsmash.parsers.w3x.w3i.Force;
import com.etheller.warsmash.parsers.w3x.w3i.Player;
import com.etheller.warsmash.parsers.w3x.w3i.War3MapW3i;
import com.etheller.warsmash.parsers.w3x.w3i.War3MapW3iFlags;
import com.etheller.warsmash.units.DataTable;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.War3ID;
import com.etheller.warsmash.util.WarsmashConstants;
import com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer;
import com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer.MapLoader;
import com.etheller.warsmash.viewer5.handlers.w3x.camera.CameraPreset;
import com.etheller.warsmash.viewer5.handlers.w3x.camera.CameraRates;
import com.etheller.warsmash.viewer5.handlers.w3x.camera.GameCameraManager;
import com.etheller.warsmash.viewer5.handlers.w3x.rendersim.RenderUnit;
import com.etheller.warsmash.viewer5.handlers.w3x.rendersim.RenderWidget;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CSimulation;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.CUnit;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilities.CAbility;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilities.targeting.AbilityPointTarget;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.util.BooleanAbilityActivationReceiver;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.util.PointAbilityTargetCheckReceiver;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.config.CBasePlayer;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.config.War3MapConfig;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.orders.OrderIds;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CAllianceType;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CMapControl;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayer;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerUnitOrderExecutor;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerUnitOrderListener;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CRace;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CRacePreference;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.trigger.enumtypes.CPlayerSlotState;

final class WebMapViewScreen implements Screen, InputProcessor {
	private static final int LOCAL_PLAYER_INDEX = 0;
	private static final int MELEE_START_GOLD = 750;
	private static final int MELEE_START_LUMBER = 200;
	private static final int MELEE_START_HERO_TOKENS = 1;

	private static final RaceStartSetup HUMAN_START = new RaceStartSetup(War3ID.fromString("htow"),
			War3ID.fromString("hpea"), 5, null, 0);
	private static final RaceStartSetup ORC_START = new RaceStartSetup(War3ID.fromString("ogre"),
			War3ID.fromString("opeo"), 5, null, 0);
	private static final RaceStartSetup UNDEAD_START = new RaceStartSetup(War3ID.fromString("unpl"),
			War3ID.fromString("uaco"), 3, War3ID.fromString("ugho"), 1);
	private static final RaceStartSetup NIGHT_ELF_START = new RaceStartSetup(War3ID.fromString("etol"),
			War3ID.fromString("ewsp"), 5, null, 0);

	private static final class CameraSetup {
		private final CameraPreset[] presets;
		private final CameraRates rates;

		private CameraSetup(final CameraPreset[] presets, final CameraRates rates) {
			this.presets = presets;
			this.rates = rates;
		}
	}

	private static final class RaceStartSetup {
		private final War3ID townHallId;
		private final War3ID workerId;
		private final int workerCount;
		private final War3ID supportUnitId;
		private final int supportUnitCount;

		private RaceStartSetup(final War3ID townHallId, final War3ID workerId, final int workerCount,
				final War3ID supportUnitId, final int supportUnitCount) {
			this.townHallId = townHallId;
			this.workerId = workerId;
			this.workerCount = workerCount;
			this.supportUnitId = supportUnitId;
			this.supportUnitCount = supportUnitCount;
		}
	}

	private final WebWarsmashGame game;
	private final InMemoryDataSource preloadedSource;
	private final String mapPath;
	private final Rectangle cameraViewport = new Rectangle();
	private final Vector2 dragStartScreen = new Vector2();
	private final Vector2 dragStartTarget = new Vector2();

	private OrthographicCamera uiCamera;
	private ExtendViewport uiViewport;
	private SpriteBatch batch;
	private BitmapFont font;
	private War3MapViewer viewer;
	private War3Map map;
	private War3MapW3i mapInfo;
	private War3MapConfig mapConfig;
	private DataTable worldEditData;
	private MapLoader mapLoader;
	private GameCameraManager cameraManager;
	private CPlayerUnitOrderListener orderListener;
	private final List<RenderWidget> selectedUnits = new ArrayList<>();
	private final Vector3 clickLocationTemp = new Vector3();
	private final AbilityPointTarget clickLocationTemp2 = new AbilityPointTarget(0f, 0f);
	private boolean initialized;
	private boolean ready;
	private boolean failed;
	private boolean draggingCamera;
	// Click-vs-drag discrimination: remember the touchDown position/button so
	// touchUp can decide whether the user clicked or dragged.
	private int touchDownScreenX;
	private int touchDownScreenY;
	private int touchDownButton = -1;
	private static final int CLICK_DRAG_THRESHOLD_PX = 4;
	private String failureMessage = "";
	private String loadingMessage = "";

	WebMapViewScreen(final WebWarsmashGame game, final InMemoryDataSource preloadedSource, final String mapPath) {
		this.game = game;
		this.preloadedSource = preloadedSource;
		this.mapPath = mapPath;
	}

	@Override
	public void show() {
		if (!this.initialized) {
			initialize();
		}
		Gdx.input.setInputProcessor(this);
	}

	private void initialize() {
		this.initialized = true;
		createGlState();
		initializeUiViewport(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		this.batch = new SpriteBatch();
		this.font = new BitmapFont();

		try {
			this.mapConfig = new War3MapConfig(WarsmashConstants.MAX_PLAYERS);
			this.viewer = new War3MapViewer(this.preloadedSource, this.game, this.mapConfig, GameTurnManager.LOCAL);
			this.map = new War3Map(this.preloadedSource, this.mapPath);
			this.worldEditData = this.viewer.loadWorldEditData(this.map);
			this.viewer.preloadWTS(this.map);
			this.mapInfo = this.map.readMapInformation();
			seedMapConfigFromMapInfo();
			this.mapLoader = this.viewer.createMapLoader(this.map, this.mapInfo, LOCAL_PLAYER_INDEX);
			this.loadingMessage = "loading map tasks...";
			this.game.status("starting direct map load for " + this.mapPath);
		}
		catch (final Exception e) {
			fail(e);
		}
	}

	private void initializeUiViewport(final int width, final int height) {
		this.uiCamera = new OrthographicCamera();
		int aspect3By4Width;
		int aspect3By4Height;
		if (width < ((height * 4) / 3)) {
			aspect3By4Width = width;
			aspect3By4Height = (width * 3) / 4;
		}
		else {
			aspect3By4Width = (height * 4) / 3;
			aspect3By4Height = height;
		}
		this.uiViewport = new ExtendViewport(aspect3By4Width, aspect3By4Height, this.uiCamera);
		this.uiViewport.update(width, height);
		this.uiCamera.position.set(this.uiViewport.getMinWorldWidth() / 2, this.uiViewport.getMinWorldHeight() / 2, 0);
		this.uiCamera.update();
	}

	private void createGlState() {
		final ByteBuffer tempByteBuffer = ByteBuffer.allocateDirect(4);
		tempByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		final IntBuffer temp = tempByteBuffer.asIntBuffer();
		Gdx.gl30.glGenVertexArrays(1, temp);
		WarsmashGdxGame.VAO = temp.get(0);
		Gdx.gl30.glBindVertexArray(WarsmashGdxGame.VAO);
		Gdx.gl30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		Gdx.gl30.glEnable(GL30.GL_SCISSOR_TEST);
	}

	private void seedMapConfigFromMapInfo() {
		this.mapConfig.setMapName(this.mapInfo.getName());
		this.mapConfig.setMapDescription(this.mapInfo.getDescription());
		this.mapConfig.setPlayerCount(this.mapInfo.getPlayers().size());
		this.mapConfig.setTeamCount(Math.max(1, this.mapInfo.getForces().size()));

		for (final Player mapPlayer : this.mapInfo.getPlayers()) {
			final int playerIndex = mapPlayer.getId();
			if ((playerIndex < 0) || (playerIndex >= WarsmashConstants.MAX_PLAYERS)) {
				continue;
			}
			final CBasePlayer configPlayer = this.mapConfig.getPlayer(playerIndex);
			final float[] startLocation = mapPlayer.getStartLocation();
			this.mapConfig.defineStartLocation(playerIndex, startLocation[0], startLocation[1]);
			configPlayer.setName(mapPlayer.getName());
			configPlayer.setColor(playerIndex);
			configPlayer.setStartLocationIndex(playerIndex);
			configPlayer.setController(mapPlayerTypeToControl(mapPlayer.getType()));
			configPlayer.setSlotState(configPlayer.getController() == CMapControl.NONE ? CPlayerSlotState.EMPTY
					: CPlayerSlotState.PLAYING);
			final CRacePreference racePreference = mapPlayerRaceToPreference(mapPlayer.getRace());
			if (racePreference != null) {
				configPlayer.setRacePref(racePreference);
			}
			configPlayer.setRaceSelectable(racePreference == WarsmashConstants.RACE_MANAGER.getRandomRacePreference());
			configPlayer.setTeam(playerIndex);
		}

		for (int forceIndex = 0; forceIndex < this.mapInfo.getForces().size(); forceIndex++) {
			final Force force = this.mapInfo.getForces().get(forceIndex);
			applyForceConfiguration(forceIndex, force);
		}

		final CBasePlayer localPlayer = this.mapConfig.getPlayer(LOCAL_PLAYER_INDEX);
		if (localPlayer.getController() == CMapControl.NONE) {
			localPlayer.setController(CMapControl.USER);
			localPlayer.setSlotState(CPlayerSlotState.PLAYING);
			localPlayer.setStartLocationIndex(LOCAL_PLAYER_INDEX);
			if (mapPlayerRaceToPreference(1) != null) {
				localPlayer.setRacePref(mapPlayerRaceToPreference(1));
			}
			localPlayer.setName("Player 1");
		}
	}

	private void applyForceConfiguration(final int forceIndex, final Force force) {
		final List<Integer> forcePlayers = new ArrayList<>();
		for (final Player mapPlayer : this.mapInfo.getPlayers()) {
			final int playerIndex = mapPlayer.getId();
			if ((force.getPlayerMasks() & (1L << playerIndex)) == 0) {
				continue;
			}
			forcePlayers.add(playerIndex);
			this.mapConfig.getPlayer(playerIndex).setTeam(forceIndex);
		}
		for (final int playerIndex : forcePlayers) {
			for (final int otherPlayerIndex : forcePlayers) {
				if (playerIndex == otherPlayerIndex) {
					continue;
				}
				if ((force.getFlags() & Force.Flag.ALLIED) != 0) {
					this.mapConfig.getPlayer(playerIndex).setAlliance(otherPlayerIndex, CAllianceType.SHARED_XP, true);
					this.mapConfig.getPlayer(playerIndex).setAlliance(otherPlayerIndex, CAllianceType.HELP_REQUEST, true);
					this.mapConfig.getPlayer(playerIndex).setAlliance(otherPlayerIndex, CAllianceType.HELP_RESPONSE,
							true);
				}
				if ((force.getFlags() & Force.Flag.SHARE_VISION) != 0) {
					this.mapConfig.getPlayer(playerIndex).setAlliance(otherPlayerIndex, CAllianceType.SHARED_VISION,
							true);
				}
				if ((force.getFlags() & Force.Flag.SHARE_UNIT_CONTROL) != 0) {
					this.mapConfig.getPlayer(playerIndex).setAlliance(otherPlayerIndex, CAllianceType.SHARED_CONTROL,
							true);
				}
				if ((force.getFlags() & Force.Flag.SHARE_ADV_UNIT_CONTROL) != 0) {
					this.mapConfig.getPlayer(playerIndex).setAlliance(otherPlayerIndex,
							CAllianceType.SHARED_ADVANCED_CONTROL, true);
				}
			}
		}
	}

	private static CMapControl mapPlayerTypeToControl(final int mapPlayerType) {
		switch (mapPlayerType) {
		case 1:
			return CMapControl.USER;
		case 2:
			return CMapControl.COMPUTER;
		case 3:
			return CMapControl.NEUTRAL;
		case 4:
			return CMapControl.RESCUABLE;
		default:
			return CMapControl.NONE;
		}
	}

	private static CRacePreference mapPlayerRaceToPreference(final int mapPlayerRace) {
		final CRacePreference specificRace = WarsmashConstants.RACE_MANAGER.getRacePreferenceById(mapPlayerRace);
		if (specificRace != null) {
			return specificRace;
		}
		return WarsmashConstants.RACE_MANAGER.getRandomRacePreference();
	}

	@Override
	public void render(final float delta) {
		if (this.failed) {
			renderOverlay("Map boot failed: " + this.failureMessage, true);
			return;
		}

		if (!this.ready) {
			processLoadingTasks();
			renderOverlay(this.loadingMessage, true);
			return;
		}

		updateCamera(delta);
		this.uiCamera.update();
		Gdx.gl30.glEnable(GL30.GL_SCISSOR_TEST);
		Gdx.gl30.glBindVertexArray(WarsmashGdxGame.VAO);
		this.viewer.updateAndRender();
		renderOverlay("Direct map view: drag to pan, wheel to zoom, Esc goes back\n" + getSimulationStatusLine(),
				false);
	}

	private void processLoadingTasks() {
		try {
			boolean done = false;
			for (int i = 0; (i < 3) && !done; i++) {
				done = this.mapLoader.process();
			}
			this.loadingMessage = "loading map... " + Math.round(this.mapLoader.getCompletionRatio() * 100f) + "%";
			if (done) {
				finishLoading();
			}
		}
		catch (final Exception e) {
			fail(e);
		}
	}

	private void finishLoading() throws IOException {
		configureDayNightModels();
		this.viewer.loadAfterUI();
		spawnMeleeStartUnitsIfNeeded();
		this.viewer.terrain.reloadFogOfWarDataToGPU(this.viewer.simulation);
		setupCamera();
		// Local, non-networked order listener: issued orders get applied directly
		// to the sim. This is the entry point for click-to-move / right-click
		// orders from the web harness.
		this.orderListener = new CPlayerUnitOrderExecutor(this.viewer.simulation, LOCAL_PLAYER_INDEX);
		this.ready = true;
		this.loadingMessage = "first rendered frame ready";
		this.game.status("simulation booted with local turn manager");
		this.game.status("first rendered frame ready for " + this.mapPath);
	}

	private void setupCamera() {
		final CameraSetup cameraSetup = createCameraSetup();
		this.cameraManager = new GameCameraManager(cameraSetup.presets, cameraSetup.rates);
		this.cameraManager.setupCamera(this.viewer.worldScene);
		final float[] defaultCameraBounds = this.viewer.terrain.getDefaultCameraBounds();
		if ((defaultCameraBounds != null) && (defaultCameraBounds.length >= 4)) {
			this.cameraManager.setCameraBounds(new Rectangle(defaultCameraBounds[0], defaultCameraBounds[1],
					defaultCameraBounds[2] - defaultCameraBounds[0], defaultCameraBounds[3] - defaultCameraBounds[1]));
		}
		final float[] localStartLocation = this.viewer.simulation.getPlayer(LOCAL_PLAYER_INDEX).getStartLocation();
		this.cameraManager.target.x = localStartLocation[0];
		this.cameraManager.target.y = localStartLocation[1];
		resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		updateCamera(0);
	}

	private void updateCamera(final float delta) {
		if ((this.cameraManager == null) || (this.viewer == null) || (this.viewer.terrain == null)) {
			return;
		}
		this.cameraManager.applyVelocity(delta, false, false, false, false);
		final float targetX = this.cameraManager.target.x;
		final float targetY = this.cameraManager.target.y;
		final float groundHeight = Math.max(this.viewer.terrain.getGroundHeight(targetX, targetY),
				this.viewer.terrain.getWaterHeight(targetX, targetY));
		this.cameraManager.updateTargetZ(groundHeight);
		this.cameraManager.updateCamera();
	}

	private void spawnMeleeStartUnitsIfNeeded() {
		if (!this.mapInfo.hasFlag(War3MapW3iFlags.MELEE_MAP) || (this.viewer.simulation == null)) {
			return;
		}
		int spawnedPlayers = 0;
		for (final Player mapPlayer : this.mapInfo.getPlayers()) {
			final int playerIndex = mapPlayer.getId();
			if ((playerIndex < 0) || (playerIndex >= WarsmashConstants.MAX_PLAYERS)) {
				continue;
			}
			final CMapControl control = this.mapConfig.getPlayer(playerIndex).getController();
			if (!isPlayableControl(control) || playerAlreadyHasUnits(playerIndex)) {
				continue;
			}
			if (spawnDefaultMeleeStart(playerIndex)) {
				spawnedPlayers++;
			}
		}
		if (spawnedPlayers > 0) {
			this.game.status("spawned fallback melee starts for " + spawnedPlayers + " players");
		}
	}

	private static boolean isPlayableControl(final CMapControl control) {
		return (control == CMapControl.USER) || (control == CMapControl.COMPUTER) || (control == CMapControl.RESCUABLE);
	}

	private boolean playerAlreadyHasUnits(final int playerIndex) {
		for (final CUnit unit : this.viewer.simulation.getUnits()) {
			if (unit.getPlayerIndex() == playerIndex) {
				return true;
			}
		}
		return false;
	}

	private boolean spawnDefaultMeleeStart(final int playerIndex) {
		final CSimulation simulation = this.viewer.simulation;
		final CPlayer player = simulation.getPlayer(playerIndex);
		final RaceStartSetup raceStartSetup = getRaceStartSetup(player.getRace());
		if (raceStartSetup == null) {
			this.game.status("no fallback melee start mapping for player " + playerIndex);
			return false;
		}

		player.setGold(MELEE_START_GOLD);
		player.setLumber(MELEE_START_LUMBER);
		player.setHeroTokens(MELEE_START_HERO_TOKENS);

		final float[] startLocation = player.getStartLocation();
		final List<CUnit> spawnedUnits = new ArrayList<>();
		final CUnit townHall = spawnUnitIfPresent(raceStartSetup.townHallId, playerIndex, startLocation[0],
				startLocation[1], 270f);
		if (townHall != null) {
			spawnedUnits.add(townHall);
		}

		final float[][] workerOffsets = { { -224f, -32f }, { -128f, -160f }, { 0f, -224f }, { 128f, -160f },
				{ 224f, -32f } };
		for (int i = 0; i < raceStartSetup.workerCount; i++) {
			final float[] offset = workerOffsets[i % workerOffsets.length];
			final CUnit worker = spawnUnitIfPresent(raceStartSetup.workerId, playerIndex, startLocation[0] + offset[0],
					startLocation[1] + offset[1], 270f);
			if (worker != null) {
				spawnedUnits.add(worker);
			}
		}

		if ((raceStartSetup.supportUnitId != null) && (raceStartSetup.supportUnitCount > 0)) {
			for (int i = 0; i < raceStartSetup.supportUnitCount; i++) {
				final float supportX = startLocation[0] + 256f + (i * 96f);
				final float supportY = startLocation[1] + 96f;
				final CUnit supportUnit = spawnUnitIfPresent(raceStartSetup.supportUnitId, playerIndex, supportX,
						supportY, 270f);
				if (supportUnit != null) {
					spawnedUnits.add(supportUnit);
				}
			}
		}

		for (final CUnit spawnedUnit : spawnedUnits) {
			spawnedUnit.updateFogOfWar(simulation);
		}
		return !spawnedUnits.isEmpty();
	}

	private CUnit spawnUnitIfPresent(final War3ID unitTypeId, final int playerIndex, final float x, final float y,
			final float facing) {
		if (this.viewer.simulation.getUnitData().getUnitType(unitTypeId) == null) {
			this.game.status("missing fallback start unit type " + unitTypeId + " for player " + playerIndex);
			return null;
		}
		return this.viewer.simulation.createUnitSimple(unitTypeId, playerIndex, x, y, facing);
	}

	private static RaceStartSetup getRaceStartSetup(final CRace race) {
		if (race == null) {
			return HUMAN_START;
		}
		switch (race.getId()) {
		case 1:
			return HUMAN_START;
		case 2:
			return ORC_START;
		case 3:
			return UNDEAD_START;
		case 4:
			return NIGHT_ELF_START;
		default:
			return HUMAN_START;
		}
	}

	private void configureDayNightModels() {
		final Element unitLights = this.worldEditData.get("UnitLights");
		final Element terrainLights = this.worldEditData.get("TerrainLights");
		if ((unitLights == null) || (terrainLights == null)) {
			this.game.status("DNC: worldEditData missing UnitLights=" + (unitLights == null) + " TerrainLights="
					+ (terrainLights == null) + " — models will render black");
			return;
		}
		final String tilesetString = String.valueOf(this.mapInfo.getTileset());
		final String unitLightString = unitLights.getField(tilesetString);
		final String terrainLightString = terrainLights.getField(tilesetString);
		this.game.status("DNC paths for tileset '" + tilesetString + "': unit=" + unitLightString + " terrain="
				+ terrainLightString);
		if ((unitLightString != null) && (terrainLightString != null)) {
			this.game.status("DNC assets present: unit.mdl=" + this.preloadedSource.has(unitLightString) + " unit.mdx="
					+ this.preloadedSource.has(toMdx(unitLightString)) + " terrain.mdl="
					+ this.preloadedSource.has(terrainLightString) + " terrain.mdx="
					+ this.preloadedSource.has(toMdx(terrainLightString)));
			this.viewer.setDayNightModels(terrainLightString, unitLightString);
			final int unitLights2 = (this.viewer.dncUnit == null) ? -1 : this.viewer.dncUnit.lights.size();
			final int terrainLights2 = (this.viewer.dncTerrain == null) ? -1 : this.viewer.dncTerrain.lights.size();
			this.game.status("DNC loaded: dncUnit="
					+ ((this.viewer.dncUnit == null) ? "null" : ("lights=" + unitLights2)) + " dncTerrain="
					+ ((this.viewer.dncTerrain == null) ? "null" : ("lights=" + terrainLights2)));
		}
	}

	private static String toMdx(final String path) {
		if (path == null) {
			return "";
		}
		final String lower = path.toLowerCase();
		if (lower.endsWith(".mdx")) {
			return path;
		}
		if (lower.endsWith(".mdl")) {
			return path.substring(0, path.length() - 4) + ".mdx";
		}
		return path + ".mdx";
	}

	private CameraSetup createCameraSetup() {
		final Element cameraData = this.viewer.miscData.get("Camera");
		Element cameraListenerData = this.viewer.miscData.get("Listener");
		if (cameraListenerData == null) {
			cameraListenerData = new Element("Listener", new DataTable(null));
		}
		final CameraPreset[] cameraPresets = new CameraPreset[6];
		for (int i = 0; i < cameraPresets.length; i++) {
			cameraPresets[i] = new CameraPreset(cameraData.getFieldFloatValue("AOA", i),
					cameraData.getFieldFloatValue("FOV", i), cameraData.getFieldFloatValue("Rotation", i),
					cameraData.getFieldFloatValue("Rotation", i + cameraPresets.length),
					cameraData.getFieldFloatValue("Rotation", i + (cameraPresets.length * 2)),
					cameraData.getFieldFloatValue("Distance", i), cameraData.getFieldFloatValue("FarZ", i),
					cameraData.getFieldFloatValue("NearZ", i), cameraData.getFieldFloatValue("Height", i),
					cameraListenerData.getFieldFloatValue("ListenerDistance", i),
					cameraListenerData.getFieldFloatValue("ListenerAOA", i));
		}
		final Element cameraRatesElement = this.viewer.miscData.get("CameraRates");
		final CameraRates cameraRates = new CameraRates(cameraRatesElement.getFieldFloatValue("AOA"),
				cameraRatesElement.getFieldFloatValue("FOV"), cameraRatesElement.getFieldFloatValue("Rotation"),
				cameraRatesElement.getFieldFloatValue("Distance"), cameraRatesElement.getFieldFloatValue("Forward"),
				cameraRatesElement.getFieldFloatValue("Strafe"));
		return new CameraSetup(cameraPresets, cameraRates);
	}

	private void renderOverlay(final String message, final boolean clearFirst) {
		if (clearFirst) {
			Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
			Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
		}
		Gdx.gl30.glDisable(GL30.GL_SCISSOR_TEST);
		Gdx.gl30.glDisable(GL30.GL_CULL_FACE);
		Gdx.gl30.glDisable(GL20.GL_DEPTH_TEST);
		if (this.viewer != null) {
			this.viewer.webGL.useShaderProgram(null);
		}
		Gdx.gl30.glActiveTexture(GL30.GL_TEXTURE0);
		this.uiViewport.apply();
		this.batch.setColor(1.0f, 1.0f, 1.0f, 1.0f);
		this.batch.setProjectionMatrix(this.uiCamera.combined);
		this.batch.begin();
		this.font.draw(this.batch, message, 16, this.uiViewport.getWorldHeight() - 16);
		this.batch.end();
		Gdx.gl30.glEnable(GL30.GL_SCISSOR_TEST);
	}

	private String getSimulationStatusLine() {
		if ((this.viewer == null) || (this.viewer.simulation == null)) {
			return "sim: unavailable";
		}
		return "sim tick=" + this.viewer.simulation.getGameTurnTick() + " timeOfDay="
				+ (Math.round(this.viewer.simulation.getGameTimeOfDay() * 100f) / 100f) + " units="
				+ this.viewer.simulation.getUnits().size();
	}

	private void fail(final Exception e) {
		logFailure(e);
		this.failed = true;
		this.failureMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
		this.game.status("map view failed: " + this.failureMessage);
	}

	private void logFailure(final Throwable throwable) {
		Throwable cursor = throwable;
		int depth = 0;
		while ((cursor != null) && (depth < 8)) {
			System.err.println("web-map-failure[" + depth + "]: " + cursor.getClass().getName() + ": "
					+ cursor.getMessage());
			for (final StackTraceElement stackTraceElement : cursor.getStackTrace()) {
				System.err.println("  at " + stackTraceElement);
			}
			cursor = cursor.getCause();
			depth++;
		}
	}

	@Override
	public void resize(final int width, final int height) {
		if (this.uiViewport != null) {
			this.uiViewport.update(width, height);
			this.uiCamera.position.set(this.uiViewport.getMinWorldWidth() / 2, this.uiViewport.getMinWorldHeight() / 2,
					0);
			this.uiCamera.update();
		}
		if (this.cameraManager != null) {
			this.cameraViewport.set(0, 0, width, height);
			this.cameraManager.resize(this.cameraViewport);
		}
	}

	@Override
	public boolean keyDown(final int keycode) {
		if (keycode == Input.Keys.ESCAPE) {
			this.game.setScreen(new WebMapBootScreen(this.game));
			return true;
		}
		return (this.cameraManager != null) && this.cameraManager.keyDown(keycode);
	}

	@Override
	public boolean keyUp(final int keycode) {
		return (this.cameraManager != null) && this.cameraManager.keyUp(keycode);
	}

	@Override
	public boolean keyTyped(final char character) {
		return false;
	}

	@Override
	public boolean touchDown(final int screenX, final int screenY, final int pointer, final int button) {
		if ((button != Input.Buttons.LEFT) && (button != Input.Buttons.RIGHT)) {
			return false;
		}
		if (this.cameraManager == null) {
			return false;
		}
		// Don't commit to "this is a drag" yet — wait to see if the pointer moves
		// beyond CLICK_DRAG_THRESHOLD_PX before touchUp. If it doesn't, treat the
		// gesture as a click in touchUp.
		this.touchDownButton = button;
		this.touchDownScreenX = screenX;
		this.touchDownScreenY = screenY;
		this.draggingCamera = false;
		this.dragStartScreen.set(screenX, screenY);
		this.dragStartTarget.set(this.cameraManager.target.x, this.cameraManager.target.y);
		return true;
	}

	@Override
	public boolean touchUp(final int screenX, final int screenY, final int pointer, final int button) {
		final boolean wasDrag = this.draggingCamera;
		final int heldButton = this.touchDownButton;
		this.draggingCamera = false;
		this.touchDownButton = -1;
		if (!wasDrag && this.ready && (heldButton == button)) {
			if (button == Input.Buttons.LEFT) {
				handleLeftClick(screenX, screenY);
				return true;
			}
			if (button == Input.Buttons.RIGHT) {
				handleRightClick(screenX, screenY);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean touchDragged(final int screenX, final int screenY, final int pointer) {
		if ((this.touchDownButton == -1) || (this.cameraManager == null)) {
			return false;
		}
		if (!this.draggingCamera) {
			final int dx = screenX - this.touchDownScreenX;
			final int dy = screenY - this.touchDownScreenY;
			if (((dx * dx) + (dy * dy)) < (CLICK_DRAG_THRESHOLD_PX * CLICK_DRAG_THRESHOLD_PX)) {
				// Still within the click-slop region — don't start panning yet.
				return false;
			}
			this.draggingCamera = true;
		}
		final float dragScale = Math.max(0.5f, this.cameraManager.distance / 900f);
		this.cameraManager.target.x = this.dragStartTarget.x - ((screenX - this.dragStartScreen.x) * dragScale);
		this.cameraManager.target.y = this.dragStartTarget.y + ((screenY - this.dragStartScreen.y) * dragScale);
		return true;
	}

	// ------------------------------------------------------------------
	// Click handlers
	// ------------------------------------------------------------------

	private void handleLeftClick(final int screenX, final int screenY) {
		if (this.viewer == null) {
			return;
		}
		final float rayY = Gdx.graphics.getHeight() - screenY;
		final RenderWidget picked = this.viewer.rayPickUnit(screenX, rayY);
		if (picked != null) {
			this.selectedUnits.clear();
			this.selectedUnits.add(picked);
			this.viewer.doSelectUnit(new ArrayList<>(this.selectedUnits));
			if (picked instanceof RenderUnit) {
				final CUnit cu = ((RenderUnit) picked).getSimulationUnit();
				this.game.status("selected " + cu.getUnitType().getName() + " @ (" + Math.round(cu.getX())
						+ ", " + Math.round(cu.getY()) + ") for player " + cu.getPlayerIndex());
			}
		}
		else {
			this.selectedUnits.clear();
			this.viewer.deselect();
		}
	}

	private void handleRightClick(final int screenX, final int screenY) {
		if ((this.viewer == null) || (this.orderListener == null) || this.selectedUnits.isEmpty()) {
			return;
		}
		final int rayY = Gdx.graphics.getHeight() - screenY;
		// Resolve the ground point under the cursor. Lift above water for any unit
		// that requires land pathing — conservative default for first-cut harness.
		final boolean allowWaterTarget = anySelectedUnitAllowsWater();
		this.viewer.getClickLocation(this.clickLocationTemp, screenX, rayY, allowWaterTarget, true);
		this.clickLocationTemp2.set(this.clickLocationTemp.x, this.clickLocationTemp.y);

		int ordered = 0;
		for (final RenderWidget widget : this.selectedUnits) {
			if (!(widget instanceof RenderUnit)) {
				continue;
			}
			final CUnit unit = ((RenderUnit) widget).getSimulationUnit();
			if (unit.getPlayerIndex() != LOCAL_PLAYER_INDEX) {
				continue;
			}
			// Find the first ability on this unit that can execute a "smart" order
			// at the clicked point (for most units that's the move ability, for
			// workers it might be mine-harvest etc.). Mirrors MeleeUI.rightClickMove.
			for (final CAbility ability : unit.getAbilities()) {
				ability.checkCanUse(this.viewer.simulation, unit, OrderIds.smart,
						BooleanAbilityActivationReceiver.INSTANCE);
				if (!BooleanAbilityActivationReceiver.INSTANCE.isOk()) {
					continue;
				}
				ability.checkCanTarget(this.viewer.simulation, unit, OrderIds.smart, this.clickLocationTemp2,
						PointAbilityTargetCheckReceiver.INSTANCE);
				final Vector2 target = PointAbilityTargetCheckReceiver.INSTANCE.getTarget();
				if (target == null) {
					continue;
				}
				this.orderListener.issuePointOrder(unit.getHandleId(), ability.getHandleId(), OrderIds.smart,
						target.x, target.y, false);
				ordered++;
				break;
			}
		}
		if (ordered > 0) {
			this.viewer.showConfirmation(this.clickLocationTemp, 0, 1, 0);
		}
	}

	private boolean anySelectedUnitAllowsWater() {
		for (final RenderWidget widget : this.selectedUnits) {
			if (widget instanceof RenderUnit) {
				if (((RenderUnit) widget).getSimulationUnit().isMovementOnWaterAllowed()) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseMoved(final int screenX, final int screenY) {
		return false;
	}

	@Override
	public boolean scrolled(final float amountX, final float amountY) {
		if (this.cameraManager == null) {
			return false;
		}
		this.cameraManager.scrolled((int) amountY);
		return true;
	}

	@Override
	public boolean touchCancelled(final int screenX, final int screenY, final int pointer, final int button) {
		this.draggingCamera = false;
		return false;
	}

	@Override
	public void pause() {
	}

	@Override
	public void resume() {
	}

	@Override
	public void hide() {
	}

	@Override
	public void dispose() {
		if (this.batch != null) {
			this.batch.dispose();
		}
		if (this.font != null) {
			this.font.dispose();
		}
		try {
			if (this.map != null) {
				this.map.close();
			}
		}
		catch (final IOException e) {
			// Ignore close failures on shutdown.
		}
		this.preloadedSource.close();
	}
}
