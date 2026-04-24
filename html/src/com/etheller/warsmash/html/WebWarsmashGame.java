package com.etheller.warsmash.html;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.etheller.warsmash.WarsmashGdxMultiScreenGame;
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

	@Override
	public void render() {
		drainWorkerLog();
		if ((this.pendingMapSource != null) && (this.pendingMapPath != null)) {
			final InMemoryDataSource source = this.pendingMapSource;
			final String mapPath = this.pendingMapPath;
			this.pendingMapSource = null;
			this.pendingMapPath = null;
			PreloadTuning.ensureInitialized();
			if (PreloadTuning.menuMode && (this.warsmashIni != null)) {
				// Use the three-arg ctor so we never reach DataSourceAssembly.parseDataSources
				// on the web graph — the preloaded in-memory source is passed in directly,
				// which keeps CASC / MPQ / java.nio.file.* off the TeaVM reachability tree.
				status("launching WarsmashGdxMenuScreen (menu-mode) for " + mapPath);
				setScreen(new com.etheller.warsmash.WarsmashGdxMenuScreen(this.warsmashIni, this, source));
			}
			else {
				setScreen(new WebMapViewScreen(this, source, mapPath));
			}
		}
		super.render();
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
