package com.etheller.warsmash.html;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.etheller.warsmash.WarsmashGdxMapScreen;
import com.etheller.warsmash.WarsmashGdxMenuScreen;
import com.etheller.warsmash.datasources.InMemoryDataSource;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.etheller.warsmash.WarsmashGdxMultiScreenGame;
import com.etheller.warsmash.units.DataTable;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.StringBundle;
import com.etheller.warsmash.util.WarsmashConstants;

public class WebWarsmashGame extends WarsmashGdxMultiScreenGame {
	private final List<String> statusLines = new ArrayList<>();
	private SpriteBatch overlayBatch;
	private BitmapFont overlayFont;
	private float copyFlashSeconds;
	private int workerMessagesShown;
	private InMemoryDataSource preloadedSource;
	private long preloadStartMillis;
	private DataTable warsmashIni;
	private boolean bootRequested;
	private boolean bootAttempted;

	@Override
	public void create() {
		this.overlayBatch = new SpriteBatch();
		this.overlayFont = new BitmapFont();
		Gdx.input.setInputProcessor(new InputAdapter() {
			@Override
			public boolean touchDown(final int screenX, final int screenY, final int pointer, final int button) {
				copyStatusToClipboard();
				return true;
			}
		});

		status("Warsmash web boot (click to copy log)");
		try {
			WebExtensions.install();
			status("extensions installed");
		}
		catch (final Throwable t) {
			status("extensions ERROR: " + t.getMessage());
		}

		try (InputStream in = Gdx.files.internal("warsmash.ini").read()) {
			this.warsmashIni = new DataTable(StringBundle.EMPTY);
			this.warsmashIni.readTXT(in, true);
			status("warsmash.ini parsed, sections=" + this.warsmashIni.keySet().size());
		}
		catch (final Throwable t) {
			status("ini ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		if (this.warsmashIni != null) {
			try {
				final Element emulator = this.warsmashIni.get("Emulator");
				WarsmashConstants.loadConstants(emulator, this.warsmashIni);
				status("WarsmashConstants loaded; MAX_PLAYERS=" + WarsmashConstants.MAX_PLAYERS
						+ ", GAME_VERSION=" + WarsmashConstants.GAME_VERSION);
			}
			catch (final Throwable t) {
				status("loadConstants ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			}
		}

		try {
			super.create();
			status("Game.create() ok — no screen set");
		}
		catch (final Throwable t) {
			status("super.create ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		try {
			final int n = WebAssetIndex.count();
			final double bytes = WebAssetIndex.totalBytes();
			if (n < 0) {
				status("asset index: (none — upload skipped?)");
			}
			else {
				status("OPFS /w3 indexed: " + n + "  (" + (long) (bytes / 1048576) + " MB)");
			}
		}
		catch (final Throwable t) {
			status("asset index ERROR: " + t.getMessage());
		}

		tryReadExtractedSample();
		startFullPreload();
	}

	/**
	 * Async-list everything in /extracted, then stream all non-audio bytes into
	 * an InMemoryDataSource. Audio (.wav/.mp3/.ogg + Sound/ + Movies/) is
	 * skipped — our web AudioExtension is a no-op anyway, and loading hundreds
	 * of MB of sound bytes the engine will never play is wasteful.
	 */
	private void startFullPreload() {
		MainOpfsBridge.listExtracted(paths -> {
			if (paths.length == 0) {
				status("/extracted: empty (upload/extract first)");
				return;
			}
			final List<String> filtered = new ArrayList<>(paths.length);
			for (final String p : paths) {
				if (shouldPreload(p)) {
					filtered.add(p);
				}
			}
			status("preloading " + filtered.size() + "/" + paths.length
					+ " files (skipping audio/movies)");
			this.preloadStartMillis = System.currentTimeMillis();
			ExtractedPreloader.preload(filtered,
					(i, total, path, bytes) -> {
						if ((i % 1000 == 0) || (i == total)) {
							final long elapsed = System.currentTimeMillis() - this.preloadStartMillis;
							status("  preload " + i + "/" + total + " (" + (elapsed / 1000) + "s)");
						}
					},
					(source, missing) -> {
						final long elapsed = System.currentTimeMillis() - this.preloadStartMillis;
						this.preloadedSource = source;
						status("preload complete: " + source.getListfile().size() + " files, "
								+ missing + " missing, " + (elapsed / 1000) + "s total");
						// Don't boot from inside a JS promise callback — pick it up
						// in the libGDX render loop instead so we're on the same
						// thread/context the rest of the engine expects.
						this.bootRequested = true;
					});
		});
	}

	private void tryBootMenuScreen() {
		status("tryBootMenuScreen: entered");
		if (this.warsmashIni == null || this.preloadedSource == null) {
			status("  missing ini or DataSource — aborting");
			return;
		}
		try {
			status("  setting override DataSource");
			WarsmashGdxMapScreen.overrideDataSource = this.preloadedSource;
			status("  instantiating WarsmashGdxMenuScreen …");
			final WarsmashGdxMenuScreen screen = new WarsmashGdxMenuScreen(this.warsmashIni, this);
			status("  calling setScreen …");
			setScreen(screen);
			status("  setScreen ok — show() fires on next frame");
		}
		catch (final Throwable t) {
			status("  tryBootMenuScreen ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			t.printStackTrace();
		}
	}

	private static boolean shouldPreload(final String path) {
		final String lower = path.toLowerCase(Locale.ROOT);
		if (lower.startsWith("sound/") || lower.startsWith("movies/")) {
			return false;
		}
		return !(lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".ogg"));
	}


	/**
	 * Smoke test: async-read a single extracted file and surface size + first
	 * line on the overlay. We walk a small list of candidates in order; the
	 * first one that exists "wins". Calls chain via callback so we never sit
	 * on an @Async suspension point from a rAF context.
	 */
	private void tryReadExtractedSample() {
		final String[] candidates = {
				"Units/CampaignUnitStrings.txt",
				"Units/UnitData.slk",
				"UI/FrameDef/Global.fdf",
				".w3-ready",
		};
		tryCandidate(candidates, 0);
	}

	private void tryCandidate(final String[] candidates, final int idx) {
		if (idx >= candidates.length) {
			status("no sample extracted file found (did extraction run?)");
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
				status("  first line: " + truncate(preview, 80));
			}
		});
	}

	private static String firstLine(final byte[] data) {
		final int max = Math.min(data.length, 4096);
		int end = max;
		for (int i = 0; i < max; i++) {
			if (data[i] == '\n' || data[i] == '\r') {
				end = i;
				break;
			}
		}
		return new String(data, 0, end, StandardCharsets.UTF_8);
	}

	private static String truncate(final String s, final int max) {
		return (s.length() <= max) ? s : s.substring(0, max) + "…";
	}

	private void status(final String s) {
		this.statusLines.add(s);
		System.out.println("[web-boot] " + s);
	}

	private void copyStatusToClipboard() {
		final StringBuilder sb = new StringBuilder();
		for (final String line : this.statusLines) {
			sb.append(line).append('\n');
		}
		try {
			Gdx.app.getClipboard().setContents(sb.toString());
			this.copyFlashSeconds = 1.5f;
		}
		catch (final Throwable t) {
			System.out.println("[web-boot] clipboard copy failed: " + t.getMessage());
		}
	}

	private void drainWorkerLog() {
		final int total = WebAssetIndex.workerLogLength();
		while (this.workerMessagesShown < total) {
			this.statusLines.add("[w] " + WebAssetIndex.workerLogAt(this.workerMessagesShown));
			this.workerMessagesShown++;
		}
	}

	private boolean screenRenderCrashed = false;

	@Override
	public void render() {
		drainWorkerLog();
		if (this.bootRequested && !this.bootAttempted) {
			this.bootAttempted = true;
			tryBootMenuScreen();
		}
		if (!this.screenRenderCrashed) {
			try {
				super.render();
			}
			catch (final Throwable t) {
				this.screenRenderCrashed = true;
				status("screen.render ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			}
		}
		this.overlayBatch.begin();
		float y = Gdx.graphics.getHeight() - 20;
		for (final String line : this.statusLines) {
			this.overlayFont.draw(this.overlayBatch, line, 20, y);
			y -= 20;
		}
		if (this.copyFlashSeconds > 0) {
			this.copyFlashSeconds -= Gdx.graphics.getDeltaTime();
			this.overlayFont.draw(this.overlayBatch, "Copied to clipboard", 20, y - 10);
		}
		this.overlayBatch.end();
	}

	@Override
	public void dispose() {
		super.dispose();
		this.overlayBatch.dispose();
		this.overlayFont.dispose();
	}
}
