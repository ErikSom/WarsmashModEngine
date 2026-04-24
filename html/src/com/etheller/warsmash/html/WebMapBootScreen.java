package com.etheller.warsmash.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.etheller.warsmash.datasources.InMemoryDataSource;
import com.etheller.warsmash.datasources.MapBytesEnsurer;
import com.etheller.warsmash.parsers.w3x.War3Map;
import com.etheller.warsmash.parsers.w3x.w3i.War3MapW3i;

final class WebMapBootScreen implements Screen, InputProcessor {
	private static final List<String> PREFERRED_MAPS = Arrays.asList(
			"Maps/FrozenThrone/(2)EchoIsles.w3x",
			"Maps/FrozenThrone/(2)TerenasStand_LV.w3x",
			"Maps/FrozenThrone/(4)TwistedMeadows.w3x",
			"Maps/FrozenThrone/(4)TurtleRock.w3x",
			"Maps/FrozenThrone/(6)GnollWood.w3x");
	private static final int MAX_VISIBLE_MAPS = 10;
	private static final long RESCAN_INTERVAL_MILLIS = 2000L;

	private enum State {
		SCANNING,
		SELECTING,
		PRELOADING,
		FAILED
	}

	private final WebWarsmashGame game;
	private final List<String> extractedPaths = new ArrayList<>();
	private final List<String> candidateMaps = new ArrayList<>();

	private SpriteBatch batch;
	private BitmapFont font;
	private State state = State.SCANNING;
	private long nextScanTimeMillis;
	private boolean scanInFlight;
	private boolean disposed;
	private boolean extractionReady;
	private boolean scanStatusLogged;
	private int selectedIndex;
	private int lastReportedMapCount = -1;
	private String selectedMapPath;
	private String failureMessage = "";
	private String preloadMessage = "";

	WebMapBootScreen(final WebWarsmashGame game) {
		this.game = game;
	}

	@Override
	public void show() {
		if (this.batch == null) {
			this.batch = new SpriteBatch();
			this.font = new BitmapFont();
		}
		PreloadTuning.ensureInitialized();
		Gdx.input.setInputProcessor(this);
		queueScan(0L);
	}

	@Override
	public void render(final float delta) {
		if ((this.state == State.SCANNING) && !this.scanInFlight
				&& (System.currentTimeMillis() >= this.nextScanTimeMillis)) {
			startScan();
		}

		Gdx.gl.glClearColor(0.03f, 0.04f, 0.06f, 1.0f);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

		this.batch.begin();
		float y = Gdx.graphics.getHeight() - 24;
		drawLine("Warsmash web boot", 24, y);
		y -= 28;
		switch (this.state) {
		case SCANNING:
			drawLine("Waiting for extracted assets and mirrored maps...", 24, y);
			y -= 22;
			drawLine("The worker extracts staged MPQs and mirrors staged .w3x/.w3m maps into /extracted.", 24, y);
			break;
		case SELECTING:
			drawLine("Select a map to boot. Arrow keys move, Enter loads, or click a map.", 24, y);
			y -= 22;
			drawLine("First milestone is direct map boot, not the desktop main menu.", 24, y);
			y -= 32;
			drawMapList(y);
			break;
		case PRELOADING:
			drawLine("Preloading manifest for: " + mapName(this.selectedMapPath), 24, y);
			y -= 22;
			drawLine(this.preloadMessage, 24, y);
			break;
		case FAILED:
			drawLine("Boot failed: " + this.failureMessage, 24, y);
			y -= 22;
			drawLine("If you staged only MPQs, add a map file or use the full Warcraft III folder, then retry.", 24, y);
			break;
		default:
			break;
		}
		this.batch.end();
	}

	private void drawMapList(final float startY) {
		int startIndex = Math.max(0, this.selectedIndex - (MAX_VISIBLE_MAPS / 2));
		startIndex = Math.min(startIndex, Math.max(0, this.candidateMaps.size() - MAX_VISIBLE_MAPS));
		float y = startY;
		for (int i = startIndex; (i < this.candidateMaps.size()) && (i < (startIndex + MAX_VISIBLE_MAPS)); i++) {
			final String prefix = (i == this.selectedIndex) ? "> " : "  ";
			drawLine(prefix + this.candidateMaps.get(i), 24, y);
			y -= 20;
		}
	}

	private void drawLine(final String text, final float x, final float y) {
		this.font.draw(this.batch, text, x, y);
	}

	private void queueScan(final long delayMillis) {
		this.nextScanTimeMillis = System.currentTimeMillis() + delayMillis;
	}

	private void startScan() {
		this.scanInFlight = true;
		if (!this.scanStatusLogged) {
			this.game.status("scanning /extracted for bootable maps");
			this.scanStatusLogged = true;
		}
		MainOpfsBridge.listExtracted(paths -> {
			this.scanInFlight = false;
			if (this.disposed) {
				return;
			}
			this.extractedPaths.clear();
			this.extractedPaths.addAll(Arrays.asList(paths));
			Collections.sort(this.extractedPaths, String.CASE_INSENSITIVE_ORDER);
			refreshMapList();
		});
	}

	private void refreshMapList() {
		final String previousSelectedMapPath = this.selectedMapPath;
		this.candidateMaps.clear();
		boolean readyMarkerSeen = false;
		for (final String path : this.extractedPaths) {
			final String lower = normalize(path);
			if (".w3-ready".equals(lower)) {
				readyMarkerSeen = true;
			}
			if (lower.endsWith(".w3m") || lower.endsWith(".w3x")) {
				this.candidateMaps.add(path);
			}
		}
		this.extractionReady = readyMarkerSeen;
		this.candidateMaps.sort(Comparator.naturalOrder());
		if (!this.candidateMaps.isEmpty() && readyMarkerSeen) {
			this.selectedIndex = selectedMapIndex(this.candidateMaps, previousSelectedMapPath);
			this.selectedMapPath = this.candidateMaps.get(this.selectedIndex);
			this.state = State.SELECTING;
			if ((this.lastReportedMapCount != this.candidateMaps.size())
					|| !normalize(this.selectedMapPath).equals(normalizeOrEmpty(previousSelectedMapPath))) {
				this.game.status("found " + this.candidateMaps.size() + " extracted maps; selected "
						+ this.selectedMapPath);
				this.lastReportedMapCount = this.candidateMaps.size();
			}
			if (PreloadTuning.menuMode) {
				this.game.status("menu-mode: auto-loading selected map " + this.selectedMapPath);
				startSelectedMapLoad();
			}
			return;
		}
		if (!this.candidateMaps.isEmpty()) {
			this.state = State.SCANNING;
			if (this.lastReportedMapCount != this.candidateMaps.size()) {
				this.game.status("discovered " + this.candidateMaps.size()
						+ " maps so far; waiting for extraction to finish");
				this.lastReportedMapCount = this.candidateMaps.size();
			}
			queueScan(RESCAN_INTERVAL_MILLIS);
			return;
		}
		this.state = readyMarkerSeen ? State.FAILED : State.SCANNING;
		this.failureMessage = readyMarkerSeen
				? "extraction finished but no .w3x/.w3m maps were mirrored into /extracted; add a map file or upload the full install"
				: "waiting for extraction worker";
		queueScan(RESCAN_INTERVAL_MILLIS);
	}

	private int selectedMapIndex(final List<String> maps, final String preferredExistingMapPath) {
		if (preferredExistingMapPath != null) {
			for (int i = 0; i < maps.size(); i++) {
				if (normalize(maps.get(i)).equals(normalize(preferredExistingMapPath))) {
					return i;
				}
			}
		}
		return preferredMapIndex(maps);
	}

	private int preferredMapIndex(final List<String> maps) {
		for (final String preferred : PREFERRED_MAPS) {
			for (int i = 0; i < maps.size(); i++) {
				if (normalize(maps.get(i)).equals(normalize(preferred))) {
					return i;
				}
			}
		}
		return 0;
	}

	private void startSelectedMapLoad() {
		if ((this.state != State.SELECTING) || this.candidateMaps.isEmpty()) {
			return;
		}
		this.selectedMapPath = this.candidateMaps.get(this.selectedIndex);
		this.state = State.PRELOADING;
		this.preloadMessage = "reading map metadata...";
		this.game.status("selected map " + this.selectedMapPath);
		MainOpfsBridge.readExtracted(this.selectedMapPath, data -> {
			if (this.disposed) {
				return;
			}
			if (data == null) {
				fail("selected map bytes are missing from /extracted");
				return;
			}
			try {
				final War3MapW3i mapInfo = readMapInfo(this.selectedMapPath, data);
				final List<String> manifest = WebPreloadManifest.build(this.extractedPaths, this.selectedMapPath, mapInfo);
				this.preloadMessage = "preloading " + manifest.size() + " files";
				this.game.status("preloading manifest of " + manifest.size() + " files for " + this.selectedMapPath);
				ExtractedPreloader.preload(manifest, (indexDone, total, path, bytes) -> {
					if (((indexDone % 100) == 0) || (indexDone == total)) {
						this.preloadMessage = "preload " + indexDone + "/" + total + " (" + mapName(path) + ")";
						this.game.status(this.preloadMessage);
					}
				}, (source, missing) -> {
					if (this.disposed) {
						return;
					}
					// Surface every OPFS .w3x/.w3m as a bare-filename placeholder in
					// the shared in-memory DataSource so MenuUI's MapListContainer
					// (which filters getListfile() for extension + no-slash entries)
					// actually shows them. The real MPQ bytes are still in OPFS —
					// fetched lazily by the web MapBytesEnsurer the first time the
					// user clicks a map in the list, before War3Map's synchronous
					// constructor runs. See MapBytesEnsurer / InMemoryDataSource.put.
					registerOpfsMapsAndInstallEnsurer(source, this.candidateMaps);
					this.game.status("manifest preload complete: " + source.getListfile().size()
							+ " files, missing=" + missing);
					this.game.requestMapLaunch(source, this.selectedMapPath);
				});
			}
			catch (final Exception e) {
				fail(e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		});
	}

	private static War3MapW3i readMapInfo(final String mapPath, final byte[] mapBytes) throws IOException {
		final InMemoryDataSource mapSource = new InMemoryDataSource(Collections.singletonMap(mapPath, mapBytes));
		final War3Map map = new War3Map(mapSource, mapPath);
		return map.readMapInformation();
	}

	private void fail(final String message) {
		this.state = State.FAILED;
		this.failureMessage = message;
		this.game.status("web boot failed: " + message);
		queueScan(RESCAN_INTERVAL_MILLIS);
	}

	@Override
	public boolean keyDown(final int keycode) {
		if (this.state != State.SELECTING) {
			return false;
		}
		if (keycode == Input.Keys.UP) {
			this.selectedIndex = Math.max(0, this.selectedIndex - 1);
			this.selectedMapPath = this.candidateMaps.get(this.selectedIndex);
			return true;
		}
		if (keycode == Input.Keys.DOWN) {
			this.selectedIndex = Math.min(this.candidateMaps.size() - 1, this.selectedIndex + 1);
			this.selectedMapPath = this.candidateMaps.get(this.selectedIndex);
			return true;
		}
		if ((keycode == Input.Keys.ENTER) || (keycode == Input.Keys.SPACE)) {
			startSelectedMapLoad();
			return true;
		}
		return false;
	}

	@Override
	public boolean touchDown(final int screenX, final int screenY, final int pointer, final int button) {
		if (this.state != State.SELECTING) {
			return false;
		}
		final int startIndex = Math.max(0,
				Math.min(this.selectedIndex - (MAX_VISIBLE_MAPS / 2), Math.max(0, this.candidateMaps.size() - MAX_VISIBLE_MAPS)));
		final float top = Gdx.graphics.getHeight() - 106;
		final int clickedRow = (int) ((top - screenY) / 20f);
		if ((clickedRow >= 0) && (clickedRow < MAX_VISIBLE_MAPS)) {
			final int clickedIndex = startIndex + clickedRow;
			if (clickedIndex < this.candidateMaps.size()) {
				this.selectedIndex = clickedIndex;
				this.selectedMapPath = this.candidateMaps.get(this.selectedIndex);
				startSelectedMapLoad();
				return true;
			}
		}
		return false;
	}

	private static String mapName(final String path) {
		final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return (slash == -1) ? path : path.substring(slash + 1);
	}

	/**
	 * After the shared-asset preload finishes, seed the shared
	 * {@link InMemoryDataSource} with every OPFS-discovered map so that
	 * MenuUI's {@code MapListContainer} (which filters getListfile() for
	 * bare-filename .w3x/.w3m) actually shows them. We register each under
	 * its basename with a zero-length placeholder — the real MPQ bytes are
	 * only fetched (async, from OPFS) when the user clicks a map in the
	 * list, via the {@link MapBytesEnsurer} installed here.
	 *
	 * <p>The basename→OPFS-path index lives in the ensurer closure; the
	 * ensurer's contract to the core engine is "after I call onReady,
	 * {@code dataSource.read(mapKey)} returns real bytes."
	 */
	private static void registerOpfsMapsAndInstallEnsurer(final InMemoryDataSource source,
			final List<String> opfsMapPaths) {
		final Map<String, String> basenameLowerToOpfsPath = new HashMap<>();
		for (final String opfsPath : opfsMapPaths) {
			final String basename = mapName(opfsPath);
			final String key = basename.toLowerCase(Locale.ROOT).replace('\\', '/');
			// Last one wins on duplicate basenames. Given the tree is user-staged
			// assets (not the production Blizzard install), collisions are rare
			// and "latest scan wins" is a reasonable default.
			basenameLowerToOpfsPath.put(key, opfsPath);
			if (!source.has(basename)) {
				source.put(basename, new byte[0]);
			}
		}
		MapBytesEnsurer.install((dataSource, mapKey, onReady) -> {
			if (!(dataSource instanceof InMemoryDataSource)) {
				// Unexpected on web but don't block the caller.
				onReady.run();
				return;
			}
			final InMemoryDataSource imds = (InMemoryDataSource) dataSource;
			final java.nio.ByteBuffer existing = imds.read(mapKey);
			if ((existing != null) && existing.remaining() > 0) {
				onReady.run();
				return;
			}
			final String lookupKey = mapKey.toLowerCase(Locale.ROOT).replace('\\', '/');
			final String opfsPath = basenameLowerToOpfsPath.get(lookupKey);
			if (opfsPath == null) {
				// Map not in the OPFS index — let the sync loader throw its
				// usual "no such map" error so the user sees the existing
				// failure mode instead of a silent hang.
				onReady.run();
				return;
			}
			MainOpfsBridge.readExtracted(opfsPath, data -> {
				if (data != null) {
					imds.put(mapKey, data);
				}
				onReady.run();
			});
		});
	}

	private static String normalize(final String path) {
		return path.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private static String normalizeOrEmpty(final String path) {
		return (path == null) ? "" : normalize(path);
	}

	@Override
	public void resize(final int width, final int height) {
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
		this.disposed = true;
		if (this.batch != null) {
			this.batch.dispose();
		}
		if (this.font != null) {
			this.font.dispose();
		}
	}

	@Override
	public boolean keyUp(final int keycode) {
		return false;
	}

	@Override
	public boolean keyTyped(final char character) {
		return false;
	}

	@Override
	public boolean touchUp(final int screenX, final int screenY, final int pointer, final int button) {
		return false;
	}

	@Override
	public boolean touchCancelled(final int screenX, final int screenY, final int pointer, final int button) {
		return false;
	}

	@Override
	public boolean touchDragged(final int screenX, final int screenY, final int pointer) {
		return false;
	}

	@Override
	public boolean mouseMoved(final int screenX, final int screenY) {
		return false;
	}

	@Override
	public boolean scrolled(final float amountX, final float amountY) {
		return false;
	}
}
