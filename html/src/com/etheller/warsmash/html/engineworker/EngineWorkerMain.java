package com.etheller.warsmash.html.engineworker;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.etheller.warsmash.datasources.CompoundDataSource;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.datasources.InMemoryDataSource;
import com.etheller.warsmash.datasources.MpqDataSource;
import com.etheller.warsmash.html.worker.OpfsBridge;
import com.etheller.warsmash.html.worker.OpfsSeekableByteChannel;
import com.github.xpenatan.gdx.teavm.backends.web.WebGL30;
import com.github.xpenatan.gdx.teavm.backends.web.gl.WebGL2RenderingContextExt;

import mpq.MPQArchive;

/**
 * Phase 1 spike entrypoint for the engine-in-worker port.
 *
 * <p>Bootstraps the libGDX surface inside the worker:
 * <ul>
 *   <li>OffscreenCanvas → WebGL2 context → {@link WebGL30} (which extends
 *       {@code WebGL20}; both are DOM-free in gdx-teavm and reusable as-is).</li>
 *   <li>Wires {@link Gdx#gl}/{@code gl20}/{@code gl30}, {@link Gdx#graphics}
 *       ({@link WorkerGraphics}), {@link Gdx#app} ({@link WorkerApplication}).</li>
 *   <li>Constructs a {@link SpriteBatch} and a {@link ShapeRenderer} and
 *       renders coloured geometry every frame. The point of this layer of
 *       the spike is the surface-area diagnostic — TeaVM tells us which
 *       libGDX classes need workarounds before we can layer Texture/BitmapFont.</li>
 * </ul>
 */
public final class EngineWorkerMain {
	private static WorkerGraphics graphics;
	private static WorkerApplication app;
	private static WorkerInput input;
	private static SpriteBatch batch;
	private static ShapeRenderer shapes;
	private static Texture proceduralTexture;

	private EngineWorkerMain() {
	}

	public static void main(final String[] args) {
		postMessage("engine-worker: java main running");
		// Block main() on the init message via @Async so subsequent setup
		// (especially OpfsBridge.openMpqHandle, which is itself @Async) runs
		// inside TeaVM's threading context. If we returned from main here and
		// did the work in a JS-callback context, async natives would trip
		// "Suspension point reached from non-threading context".
		final JSObject init = waitForInit("_");
		postMessage("engine-worker: init received, opening WebGL2");
		try {
			startRender(init);
		}
		catch (final Throwable t) {
			postMessage("engine-worker: STARTUP ERROR " + t);
			t.printStackTrace();
		}
	}

	private static void startRender(final JSObject init) {
		try {
			final JSObject offscreenCanvas = initCanvas(init);
			final JSObject ctx = getWebGL2Context(offscreenCanvas);
			if (ctx == null) {
				postMessage("engine-worker: ERROR webgl2 unavailable");
				return;
			}
			final WebGL2RenderingContextExt glContext = ctx.cast();
			final int backW = canvasWidth(offscreenCanvas);
			final int backH = canvasHeight(offscreenCanvas);
			final int cssW = initCssWidth(init);
			final int cssH = initCssHeight(init);

			// Wire libGDX globals. WebGL30 extends WebGL20 — installing it on
			// gl/gl20/gl30 enables the SpriteBatch VAO path. Wrapped via
			// HiDpiWebGL30 so logical-pixel viewport/scissor calls land in
			// back-buffer space without engine-code changes.
			final float dpiScale = (cssW > 0) ? (backW / (float) cssW) : 1f;
			final WebGL30 gl = new HiDpiWebGL30(glContext, dpiScale);
			Gdx.gl = gl;
			Gdx.gl20 = gl;
			Gdx.gl30 = gl;

			graphics = new WorkerGraphics(cssW, cssH, backW, backH);
			graphics.setGL20(gl);
			graphics.setGL30(gl);
			graphics.setGLVersion(new GLVersion(com.badlogic.gdx.Application.ApplicationType.WebGL,
					gl.glGetString(GL20.GL_VERSION),
					gl.glGetString(GL20.GL_VENDOR),
					gl.glGetString(GL20.GL_RENDERER)));
			Gdx.graphics = graphics;

			app = new WorkerApplication(graphics);
			Gdx.app = app;
			// Anchor WorkerApplication.getConfig in the reachable graph so
			// TeaVM keeps it on the prototype. The gdx-teavm emulated Pixmap
			// ctor does ((WebApplication)Gdx.app).getConfig() via dynamic
			// dispatch — without a real caller it'd be dead-code-eliminated.
			app.getConfig();

			input = new WorkerInput();
			Gdx.input = input;
			registerEventHandlers();

			Gdx.audio = new WorkerAudio();

			postMessage("engine-worker: Gdx wiring complete · logical " + cssW + "x" + cssH
					+ " · backbuffer " + backW + "x" + backH);

			batch = new SpriteBatch();
			postMessage("engine-worker: SpriteBatch constructed");

			shapes = new ShapeRenderer();
			postMessage("engine-worker: ShapeRenderer constructed");

			proceduralTexture = buildProceduralTexture(256, 256);
			postMessage("engine-worker: procedural Texture constructed (" + proceduralTexture.getWidth()
					+ "x" + proceduralTexture.getHeight() + ")");

			openMpqDataSources();

			tryConstructEngineGame();

			// Drive the engine's libGDX lifecycle. resize() once now so screens
			// pick up correct dimensions on their first render() call.
			if (realEngine != null) {
				try {
					realEngine.resize(graphics.getWidth(), graphics.getHeight());
					postMessage("engine-worker: realEngine.resize(" + graphics.getWidth() + ", "
							+ graphics.getHeight() + ") OK");
				}
				catch (final Throwable t) {
					postMessage("engine-worker: realEngine.resize FAILED — " + t);
				}
				launchRealMenu();
			}

			startRafLoop(EngineWorkerMain::renderFrame);
		}
		catch (final Throwable t) {
			// Avoid t.getClass().getSimpleName() — TeaVM doesn't always carry
			// the class-name metadata needed for getSimpleName, and a missing
			// name there NPEs inside the catch block, hiding the real error.
			postMessage("engine-worker: STARTUP ERROR " + t);
			t.printStackTrace();
		}
	}

	private static double hue;

	private static boolean frameErrorReported;

	private static void renderFrame(final double timestampMs) {
		try {
			graphics.onFrame(timestampMs);
			input.processEvents((long) (timestampMs * 1_000_000L));
			app.runPending();

			// Logical dims here; HiDpiWebGL30 scales to back buffer.
			Gdx.gl.glViewport(0, 0, graphics.getWidth(), graphics.getHeight());
			Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
			Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

			if (realEngine != null) {
				// Game.render() delegates to the current Screen.render(deltaTime).
				// All the spike's hand-rolled pie / quad rendering is gone —
				// what we see now IS the engine's WebMapBootScreen (or whatever
				// screen the engine has set).
				realEngine.render();
			}
		}
		catch (final Throwable t) {
			// Throttle frame errors — a per-frame failure would otherwise
			// drown the HUD.
			if (!frameErrorReported) {
				frameErrorReported = true;
				postMessage("engine-worker: FRAME ERROR (first occurrence, suppressing further) — " + t);
				t.printStackTrace();
			}
		}
	}

	/** warsmash.ini priority order. Later entries override earlier ones. */
	private static final String[] MAIN_MPQS = {
			"war3.mpq",
			"war3local.mpq",
			"war3x.mpq",
			"war3xlocal.mpq",
	};

	/**
	 * Opens the four main-game MPQs from OPFS {@code /w3} via
	 * {@link OpfsBridge}'s SAH machinery, wraps them in a
	 * {@link CompoundDataSource}, and installs that as {@link Gdx#files}.
	 *
	 * <p>Movies/Deprecated MPQs in /w3 are skipped — they're either Blizzard
	 * video containers (not real MPQs) or not on the engine's read path.
	 *
	 * <p>If {@code /w3} is empty (build dropped it after extraction in an
	 * earlier session), this falls through with a HUD note and {@code Gdx.files}
	 * stays null.
	 */
	private static void openMpqDataSources() {
		final String[] available = OpfsBridge.findMpqFiles();
		if (available.length == 0) {
			postMessage("engine-worker: no MPQs in /w3 — re-upload via main page to enable engine-worker MPQ reads");
			return;
		}
		postMessage("engine-worker: /w3 has " + available.length + " .mpq files; mounting main 4 in priority order");

		final java.util.List<DataSource> sources = new java.util.ArrayList<>();
		for (final String wanted : MAIN_MPQS) {
			final String mpqPath = findByBaseName(available, wanted);
			if (mpqPath == null) {
				postMessage("engine-worker:   missing " + wanted + " — skipping");
				continue;
			}
			try {
				OpfsBridge.openMpqHandle(mpqPath);
				final OpfsSeekableByteChannel channel = new OpfsSeekableByteChannel(mpqPath);
				final MPQArchive archive = new MPQArchive(channel);
				final MpqDataSource source = new MpqDataSource(archive, channel);
				sources.add(source);
				final int listfile = source.getListfile() == null ? -1 : source.getListfile().size();
				postMessage("engine-worker:   mounted " + mpqPath + " (" + listfile + " entries)");
			}
			catch (final Throwable t) {
				postMessage("engine-worker:   " + mpqPath + ": OPEN FAILED — " + t);
			}
		}
		if (sources.isEmpty()) {
			postMessage("engine-worker: no main MPQs mounted; Gdx.files stays unset");
			return;
		}

		// Engine-internal assets first (warsmash.ini, ability JSONs, etc.) —
		// the gdx-teavm asset bundle equivalent for our worker. Fetched in
		// engine-worker-boot.js via the preload.txt manifest.
		final InMemoryDataSource engineAssets = preloadEngineAssets();

		// Loose .w3x/.w3m files in /w3/Maps/ aren't inside any MPQ — Blizzard's
		// installer drops stock maps as files alongside the MPQs. Preload them
		// into an in-memory layer so War3Map / MapListContainer can open them
		// without async-await machinery. Bytes are tens of MB total at most;
		// negligible next to the existing main-thread preload we're replacing.
		final InMemoryDataSource looseMaps = preloadLooseMaps();
		final java.util.List<DataSource> all = new java.util.ArrayList<>();
		if (engineAssets != null) all.add(engineAssets);
		all.addAll(sources);
		if (looseMaps != null) {
			all.add(looseMaps);
		}
		final DataSource compound = (all.size() == 1) ? all.get(0) : new CompoundDataSource(all);
		gameDataSource = compound;
		Gdx.files = new WorkerFiles(compound);
		postMessage("engine-worker: Gdx.files wired (" + sources.size() + " MPQs"
				+ (looseMaps == null ? "" : " + " + looseMaps.getListfile().size() + " loose files") + ")");
		demoFileRead(compound);
		demoMapParse(compound);
	}

	/**
	 * First contact with the real engine entry point in worker context. Pulls
	 * {@link com.etheller.warsmash.html.WebWarsmashGame} into the TeaVM
	 * reachability graph so we can see which classes (if any) come back as
	 * unresolved, then attempts the constructor.
	 *
	 * <p>Deliberately not calling {@code create()} yet — that path runs
	 * {@code tryReadExtractedSample} which uses {@code MainOpfsBridge}'s
	 * window-only async OPFS readers. Need to either swap that for OPFS
	 * worker-side reads or guard it before {@code create()} is safe to call
	 * from worker context. Constructor side-effects are field assignments
	 * only, so this is the cheap test.
	 */
	private static com.etheller.warsmash.html.WebWarsmashGame realEngine;
	private static DataSource gameDataSource;

	/**
	 * Skip past WebMapBootScreen (which loops forever scanning a non-existent
	 * /extracted) and put the engine straight onto the real WC3 menu using
	 * our MPQ-backed compound DataSource. The menu's constructor reaches into
	 * lots of engine subsystems — this is the deepest stress-test of "engine
	 * in worker" we can run with the existing code.
	 */
	private static void launchRealMenu() {
		try {
			postMessage("engine-worker: bypassing WebMapBootScreen, launching WarsmashGdxMenuScreen…");
			final com.etheller.warsmash.units.DataTable warsmashIni =
					new com.etheller.warsmash.units.DataTable(com.etheller.warsmash.util.StringBundle.EMPTY);
			try (java.io.InputStream in = Gdx.files.internal("warsmash.ini").read()) {
				warsmashIni.readTXT(in, true);
			}
			realEngine.setScreen(new com.etheller.warsmash.WarsmashGdxMenuScreen(
					warsmashIni, realEngine, gameDataSource));
			postMessage("engine-worker: setScreen(WarsmashGdxMenuScreen) returned");
		}
		catch (final Throwable t) {
			postMessage("engine-worker: real-menu launch FAILED — " + t);
			t.printStackTrace();
		}
	}

	private static void tryConstructEngineGame() {
		try {
			postMessage("engine-worker: constructing real WebWarsmashGame...");
			realEngine = new com.etheller.warsmash.html.WebWarsmashGame();
			postMessage("engine-worker: ctor returned");

			postMessage("engine-worker: calling realEngine.create()...");
			realEngine.create();
			postMessage("engine-worker: create() returned cleanly");
		}
		catch (final Throwable t) {
			postMessage("engine-worker: real-engine boot FAILED — " + t);
			t.printStackTrace();
		}
	}

	/**
	 * Pulls the engine-bundled assets that {@code engine-worker-boot.js} fetched
	 * via the gdx-teavm preload manifest into an in-memory layer. Same files
	 * that {@code Gdx.files.internal(...)} would resolve on the main-thread
	 * build via the gdx-teavm asset loader — warsmash.ini, ability JSONs,
	 * resource icons, etc.
	 */
	private static InMemoryDataSource preloadEngineAssets() {
		final String[] paths = EngineAssetsBridge.paths();
		if (paths.length == 0) {
			postMessage("engine-worker: no engine assets fetched (preload.txt missing?)");
			return null;
		}
		final java.util.Map<String, byte[]> map = new java.util.LinkedHashMap<>();
		long total = 0;
		for (final String p : paths) {
			final byte[] bytes = EngineAssetsBridge.read(p);
			if (bytes != null) {
				map.put(p, bytes);
				total += bytes.length;
			}
		}
		postMessage("engine-worker: preloaded " + map.size() + " engine assets ("
				+ (total / 1024) + " KB)");
		return new InMemoryDataSource(map);
	}

	/**
	 * Loads stock map files (.w3x, .w3m) and the listfile-less few-and-far
	 * loose assets that ship in /w3 outside of any MPQ. Preloaded into
	 * memory because the engine reads them synchronously and OPFS reads from
	 * worker context outside TeaVM threading need SAH per-call (slower for
	 * the kind of map enumeration the menu does).
	 */
	private static InMemoryDataSource preloadLooseMaps() {
		final String[] all = OpfsBridge.listUnder("Maps/");
		if (all.length == 0) {
			postMessage("engine-worker: no loose Maps/ files in /w3");
			return null;
		}
		final java.util.Map<String, byte[]> bytesByPath = new java.util.LinkedHashMap<>();
		long totalBytes = 0;
		int loaded = 0;
		int skipped = 0;
		for (final String path : all) {
			final String lower = path.toLowerCase(java.util.Locale.ROOT);
			if (!lower.endsWith(".w3x") && !lower.endsWith(".w3m")) {
				skipped++;
				continue;
			}
			try {
				final byte[] bytes = OpfsBridge.readFull(path);
				// Store under BOTH the full OPFS path and the bare basename.
				// MapListContainer filters its display list to keys that
				// contain no slash/backslash — i.e. bare filenames — so the
				// basename copy is what makes maps appear in the menu. The
				// full-path copy keeps subdirectory lookups working for
				// anything that addresses maps by their MPQ-internal path.
				bytesByPath.put(path, bytes);
				final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
				if (slash >= 0) {
					bytesByPath.put(path.substring(slash + 1), bytes);
				}
				totalBytes += bytes.length;
				loaded++;
			}
			catch (final Throwable t) {
				postMessage("engine-worker: skipping " + path + ": " + t);
			}
		}
		postMessage("engine-worker: preloaded " + loaded + " loose maps ("
				+ (totalBytes / 1024 / 1024) + " MB) from /w3, skipped " + skipped + " non-maps");
		return new InMemoryDataSource(bytesByPath);
	}

	private static String findByBaseName(final String[] paths, final String wantedLower) {
		for (final String p : paths) {
			final int slash = p.lastIndexOf('/');
			final String base = (slash == -1) ? p : p.substring(slash + 1);
			if (base.toLowerCase(java.util.Locale.ROOT).equals(wantedLower)) {
				return p;
			}
		}
		return null;
	}

	/**
	 * Reads a known-present text asset through the just-installed
	 * {@code Gdx.files} chain to prove the data path round-trips. MPQs use
	 * backslash separators internally; we try both spellings since the engine
	 * code mixes them.
	 */
	private static void demoFileRead(final DataSource fallback) {
		final String[] candidates = {
				"Units\\CampaignUnitStrings.txt",
				"Units/CampaignUnitStrings.txt",
				"UI\\FrameDef\\UI\\EscMenuTemplates.fdf",
				"UI\\Glues.txt",
		};
		for (final String path : candidates) {
			try {
				final com.badlogic.gdx.files.FileHandle h = Gdx.files.internal(path);
				if (!h.exists()) {
					continue;
				}
				final byte[] bytes = h.readBytes();
				postMessage("engine-worker: demo read " + path + " — " + bytes.length + " bytes");
				int end = Math.min(bytes.length, 80);
				for (int i = 0; i < Math.min(bytes.length, 200); i++) {
					if (bytes[i] == '\n' || bytes[i] == '\r') { end = i; break; }
				}
				postMessage("engine-worker: first line: "
						+ new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8));
				return;
			}
			catch (final Throwable t) {
				postMessage("engine-worker: demo " + path + " threw " + t);
			}
		}
		postMessage("engine-worker: demo read found no candidate (data source has "
				+ fallback.getListfile().size() + " entries)");
	}

	/**
	 * Construct the engine's {@link com.etheller.warsmash.parsers.w3x.War3Map}
	 * model class against the worker-side MPQ data source and parse a real
	 * map's {@code war3map.w3i}. This proves engine-asset model classes work
	 * in worker context — the path forward toward a full engine boot.
	 *
	 * <p>Picks Echo Isles (a stock TFT map known to live in War3x.mpq); reports
	 * the parsed map's display name + player slot count.
	 */
	private static void demoMapParse(final DataSource compound) {
		final String[] mapCandidates = {
				"Maps\\FrozenThrone\\(2)EchoIsles.w3x",
				"Maps/FrozenThrone/(2)EchoIsles.w3x",
				"Maps\\(2)EchoIsles.w3x",
		};
		String mapPath = null;
		for (final String c : mapCandidates) {
			if (compound.has(c)) { mapPath = c; break; }
		}
		if (mapPath == null) {
			postMessage("engine-worker: map demo — Echo Isles not in MPQ; skipping");
			return;
		}
		try {
			final com.etheller.warsmash.parsers.w3x.War3Map map =
					new com.etheller.warsmash.parsers.w3x.War3Map(compound, mapPath);
			final com.etheller.warsmash.parsers.w3x.w3i.War3MapW3i info = map.readMapInformation();
			postMessage("engine-worker: parsed map " + mapPath
					+ " — name=\"" + info.getName() + "\""
					+ ", players=" + info.getPlayers().size()
					+ ", tileset=" + info.getTileset()
					+ ", " + info.getCameraBounds()[2] + "x" + info.getCameraBounds()[3]);
		}
		catch (final Throwable t) {
			postMessage("engine-worker: map demo FAILED — " + t);
			t.printStackTrace();
		}
	}

	/**
	 * Builds a {@link Texture} without going through any image-decode path.
	 * Allocates a blank {@link Pixmap}, writes RGBA pixels via
	 * {@link Pixmap#drawPixel(int, int, int)}, then uploads it. If
	 * {@code new Pixmap(...)} reaches DOM-bound code in libGDX-TeaVM (e.g. via
	 * a backing {@code BufferedImage}), this will be the failure point.
	 */
	private static Texture buildProceduralTexture(final int width, final int height) {
		final Pixmap pixmap = new Pixmap(width, height, Format.RGBA8888);
		try {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					final float u = x / (float) width;
					final float v = y / (float) height;
					final int r = (int) (255 * 0.5f * (1f + Math.sin(u * 12.566f)));
					final int g = (int) (255 * 0.5f * (1f + Math.sin(v * 12.566f)));
					final int b = (int) (255 * 0.5f * (1f + Math.sin((u + v) * 6.283f)));
					final int rgba = ((r & 0xFF) << 24) | ((g & 0xFF) << 16) | ((b & 0xFF) << 8) | 0xFF;
					pixmap.drawPixel(x, y, rgba);
				}
			}
			return new Texture(pixmap);
		}
		finally {
			pixmap.dispose();
		}
	}

	// ----- JS bridges -----

	@JSBody(params = { "msg" }, script = "self.postMessage(msg);")
	public static native void postMessage(String msg);

	/**
	 * Hooks into the early message buffer set up by engine-worker-boot.js.
	 * Direct {@code self.addEventListener('message', ...)} won't catch the
	 * init message — main thread posts it before wasm instantiation finishes,
	 * so by the time TeaVM's main() runs the message task has already fired
	 * into a worker with no listener and been dropped.
	 */
	@JSBody(params = { "cb" },
			script = "self.__installMessageHandler(function(d) {"
					+ "  if (!d) return;"
					+ "  if (d.kind === 'init')    { cb(d); return; }"
					+ "  if (d.kind === 'pointer') { if (self.__onPointer) self.__onPointer(d.name, d.x|0, d.y|0, d.button|0); return; }"
					+ "  if (d.kind === 'key')     { if (self.__onKey)     self.__onKey(d.name, d.keycode|0, d.ch || ''); return; }"
					+ "  if (d.kind === 'scroll')  { if (self.__onScroll)  self.__onScroll(+d.dx, +d.dy); return; }"
					+ "  if (d.kind === 'resize')  { if (self.__onResize)  self.__onResize(d.cssWidth|0, d.cssHeight|0, d.pixelWidth|0, d.pixelHeight|0); return; }"
					+ "});")
	private static native void registerInitHandler(InitCallback cb);

	@org.teavm.jso.JSFunctor
	interface InitCallback extends JSObject {
		void accept(JSObject init);
	}

	/**
	 * Wires {@code self.__onPointer} / {@code __onKey} / {@code __onScroll}
	 * to forward into {@link WorkerInput}. The {@link #registerInitHandler}
	 * script above calls these globals for every incoming message; we install
	 * them once at startup. Done as JSO functor bridges (not @JSBody) so
	 * the worker scope holds direct references to the Java methods.
	 */
	private static void registerEventHandlers() {
		setOnPointer((name, x, y, button) -> input.postPointer(name, x, y, button));
		setOnKey((name, keycode, chStr) -> {
			final char ch = (chStr == null || chStr.isEmpty()) ? 0 : chStr.charAt(0);
			input.postKey(name, keycode, ch);
		});
		setOnScroll((dx, dy) -> input.postScroll(dx, dy));
	}

	@JSBody(params = { "fn" }, script = "self.__onPointer = fn;")
	private static native void setOnPointer(PointerHandler fn);

	@JSBody(params = { "fn" }, script = "self.__onKey = fn;")
	private static native void setOnKey(KeyHandler fn);

	@JSBody(params = { "fn" }, script = "self.__onScroll = fn;")
	private static native void setOnScroll(ScrollHandler fn);

	@org.teavm.jso.JSFunctor
	interface PointerHandler extends JSObject {
		void accept(String name, int x, int y, int button);
	}

	@org.teavm.jso.JSFunctor
	interface KeyHandler extends JSObject {
		void accept(String name, int keycode, String ch);
	}

	@org.teavm.jso.JSFunctor
	interface ScrollHandler extends JSObject {
		void accept(float dx, float dy);
	}

	/**
	 * Suspend main() until the init message arrives. Dummy String parameter
	 * because zero-arg {@code @Async} natives have been observed to trip
	 * "Cannot read properties of undefined (reading $jsException)" in TeaVM.
	 */
	@Async
	private static native JSObject waitForInit(String dummy);

	@SuppressWarnings("unused")
	private static void waitForInit(final String dummy, final AsyncCallback<JSObject> cb) {
		registerInitHandler(canvas -> cb.complete(canvas));
	}

	@JSBody(params = { "canvas" },
			script = "return canvas.getContext('webgl2', { antialias: false, alpha: false });")
	private static native JSObject getWebGL2Context(JSObject offscreenCanvas);

	@JSBody(params = { "canvas" }, script = "return canvas.width;")
	private static native int canvasWidth(JSObject canvas);

	@JSBody(params = { "canvas" }, script = "return canvas.height;")
	private static native int canvasHeight(JSObject canvas);

	@JSBody(params = { "init" }, script = "return init.canvas;")
	private static native JSObject initCanvas(JSObject init);

	@JSBody(params = { "init" }, script = "return init.cssWidth | 0;")
	private static native int initCssWidth(JSObject init);

	@JSBody(params = { "init" }, script = "return init.cssHeight | 0;")
	private static native int initCssHeight(JSObject init);

	@JSBody(params = { "cb" },
			script = "function tick(t) { cb(t); self.requestAnimationFrame(tick); }"
					+ "self.requestAnimationFrame(tick);")
	private static native void startRafLoop(FrameCallback cb);

	@org.teavm.jso.JSFunctor
	interface FrameCallback extends JSObject {
		void accept(double timestampMs);
	}
}
