package com.etheller.warsmash.html;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.etheller.warsmash.WarsmashGdxMultiScreenGame;
import com.etheller.warsmash.datasources.CompoundDataSource;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.datasources.InMemoryDataSource;
import com.etheller.warsmash.units.DataTable;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.ImageUtils;
import com.etheller.warsmash.util.StringBundle;
import com.etheller.warsmash.util.WarsmashConstants;

public class WebWarsmashGame extends WarsmashGdxMultiScreenGame {
	private final List<String> statusLines = new ArrayList<>();
	private int workerMessagesShown;
	private InMemoryDataSource pendingMapSource;
	private String pendingMapPath;
	/** Captured during {@link #create()} so the Menu path can hand it to
	 *  {@link com.etheller.warsmash.WarsmashGdxMenuScreen} verbatim. */
	private DataTable warsmashIni;

	@Override
	public void create() {
		// Route stderr through stdout so DevTools stops attaching a (huge,
		// TeaVM-translated) JS stack to every System.err.println. The engine
		// uses System.err for ordinary status/warning text ("Loading TOC...",
		// "DestroyGroup called but..."), which makes the console freeze
		// formatting dozens of stacks on boot. Lose the err/out distinction —
		// gain a usable console.
		System.setErr(System.out);

		status("Warsmash web boot");

		ImageUtils.textureDecoder = new WebTextureDecoder();
		com.etheller.warsmash.util.Platform.urlOpener = WebPlatform::openUrl;
		// Keep FreeType entirely off the web reachability graph by installing the
		// web-only DynamicFontGeneratorHolder. Anything that constructs a GameUI
		// goes through the factory and lands on this stub rather than
		// FreeTypeDynamicFontGeneratorHolder.
		com.etheller.warsmash.parsers.fdf.DynamicFontGeneratorHolderFactory.install(
				WebDynamicFontGeneratorHolder::new);

		// WebGL2 is strict where desktop GL was lax; log GL errors instead of
		// hard-throwing so rendering progresses past the first WebGL-specific
		// issue and we can see what the engine tries to paint on subsequent
		// frames. Flip back to true once the rendering path is clean.
		com.etheller.warsmash.viewer5.handlers.w3x.environment.Terrain.glErrorFatal = false;
		com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer.glErrorFatal = false;
		try {
			WebExtensions.install();
			status("extensions installed");
		}
		catch (final Throwable t) {
			status("extensions ERROR: " + t.getMessage());
		}

		// Hand MenuUI the factory it uses to transition off the main menu onto
		// the actual in-game screen once MapLoader is done. Without this the
		// completion branch in MenuUI just clears the loading bar and leaves
		// the user stranded on the menu (MapScreenFactory.get() returns null
		// and the setScreen call is skipped).
		try {
			com.etheller.warsmash.MapScreenFactory.register(
					(viewer, screenManager, menuScreen, uiOrderListener) -> new com.etheller.warsmash.WarsmashGdxMapScreen(
							viewer, screenManager, menuScreen, uiOrderListener));
			status("map screen factory registered");
		}
		catch (final Throwable t) {
			status("map screen factory ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		// Decode-on-demand: BlpTexture.load uses this to upload a sync-decoded
		// thumbnail mip immediately, then the upgrader async-decodes mip 0
		// and re-uploads the texture once ready. Pairs with skipping JPEG BLP
		// decode in ExtractedPreloader so first-frame time isn't gated on the
		// canvas decode tail.
		try {
			com.etheller.warsmash.viewer5.handlers.blp.BlpAsyncUpgrader.register(new WebBlpAsyncUpgrader());
			status("BLP async upgrader registered (decode-on-demand)");
		}
		catch (final Throwable t) {
			status("BLP upgrader ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		try (InputStream in = Gdx.files.internal("warsmash.ini").read()) {
			final DataTable loadedIni = new DataTable(StringBundle.EMPTY);
			loadedIni.readTXT(in, true);
			final Element emulator = loadedIni.get("Emulator");
			WarsmashConstants.loadConstants(emulator, loadedIni);
			this.warsmashIni = loadedIni;
			status("warsmash.ini loaded");
		}
		catch (final Throwable t) {
			status("ini ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		try {
			super.create();
			setScreen(new WebMapBootScreen(this));
		}
		catch (final Throwable t) {
			status("create ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		try {
			final int n = WebAssetIndex.count();
			if (n < 0) {
				status("asset index: none");
			}
			else {
				status("uploaded asset index entries: " + n);
			}
		}
		catch (final Throwable t) {
			status("asset index ERROR: " + t.getMessage());
		}

		tryReadExtractedSample();
	}

	public void requestMapLaunch(final InMemoryDataSource source, final String mapPath) {
		this.pendingMapSource = source;
		this.pendingMapPath = mapPath;
	}

	public void status(final String s) {
		this.statusLines.add(s);
		System.out.println("[web-boot] " + s);
	}

	/** Read-only view used by {@link WebMapBootScreen} to paint a status tail. */
	public List<String> getStatusLines() {
		return this.statusLines;
	}

	@Override
	public void render() {
		drainWorkerLog();
		if ((this.pendingMapSource != null) && (this.pendingMapPath != null)) {
			final InMemoryDataSource source = this.pendingMapSource;
			final String mapPath = this.pendingMapPath;
			this.pendingMapSource = null;
			this.pendingMapPath = null;
			final DataSource launchDataSource = createLaunchDataSource(source);
			PreloadTuning.ensureInitialized();
			if (this.warsmashIni == null) {
				status("warsmash.ini missing — cannot launch menu screen");
			}
			else {
				// Use the three-arg ctor so we never reach DataSourceAssembly.parseDataSources
				// on the web graph. The preloaded OPFS datasource stays on top, while
				// bundled libGDX internal assets fill in Warsmash-specific resources.
				status("launching WarsmashGdxMenuScreen for " + mapPath);
				setScreen(new com.etheller.warsmash.WarsmashGdxMenuScreen(this.warsmashIni, this, launchDataSource));
			}
		}
		super.render();
	}

	private DataSource createLaunchDataSource(final InMemoryDataSource preloadedSource) {
		return new CompoundDataSource(Arrays.asList(new GdxInternalDataSource(), preloadedSource));
	}

	private void drainWorkerLog() {
		final int total = WebAssetIndex.workerLogLength();
		while (this.workerMessagesShown < total) {
			this.statusLines.add("[w] " + WebAssetIndex.workerLogAt(this.workerMessagesShown));
			this.workerMessagesShown++;
		}
	}

	private void tryReadExtractedSample() {
		final String[] candidates = {
				"Maps/FrozenThrone/(2)EchoIsles.w3x",
				"Units/CampaignUnitStrings.txt",
				".w3-ready",
		};
		tryCandidate(candidates, 0);
	}

	private void tryCandidate(final String[] candidates, final int idx) {
		if (idx >= candidates.length) {
			return;
		}
		final String path = candidates[idx];
		MainOpfsBridge.readExtracted(path, data -> {
			if (data == null) {
				tryCandidate(candidates, idx + 1);
				return;
			}
			status("read /extracted/" + path + ": " + data.length + " bytes");
			final String preview = firstLine(data);
			if (!preview.isEmpty()) {
				status("first line: " + truncate(preview, 80));
			}
		});
	}

	private static String firstLine(final byte[] data) {
		final int max = Math.min(data.length, 4096);
		int end = max;
		for (int i = 0; i < max; i++) {
			if ((data[i] == '\n') || (data[i] == '\r')) {
				end = i;
				break;
			}
		}
		return new String(data, 0, end, StandardCharsets.UTF_8);
	}

	private static String truncate(final String s, final int max) {
		return (s.length() <= max) ? s : s.substring(0, max) + "...";
	}

	@Override
	public void dispose() {
		super.dispose();
	}
}
