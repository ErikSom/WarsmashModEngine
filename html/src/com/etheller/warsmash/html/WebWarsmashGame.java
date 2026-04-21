package com.etheller.warsmash.html;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

		DataTable ini = null;
		try (InputStream in = Gdx.files.internal("warsmash.ini").read()) {
			ini = new DataTable(StringBundle.EMPTY);
			ini.readTXT(in, true);
			status("warsmash.ini parsed, sections=" + ini.keySet().size());
		}
		catch (final Throwable t) {
			status("ini ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}

		if (ini != null) {
			try {
				final Element emulator = ini.get("Emulator");
				WarsmashConstants.loadConstants(emulator, ini);
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
				status("OPFS assets indexed: " + n + "  (" + (long) (bytes / 1048576) + " MB)");
				for (int i = 0; i < Math.min(5, n); i++) {
					status("  " + WebAssetIndex.pathAt(i));
				}
				if (n > 5) {
					status("  … " + (n - 5) + " more");
				}
			}
		}
		catch (final Throwable t) {
			status("asset index ERROR: " + t.getMessage());
		}
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

	@Override
	public void render() {
		super.render();
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
