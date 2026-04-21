package com.etheller.warsmash.html;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.etheller.warsmash.units.DataTable;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.StringBundle;

public class WarsmashHtmlApp extends ApplicationAdapter {
	private static final String[] ABILITY_FILES = { "auras.json", "generalPassives.json", "humanHeroActives.json",
			"RaiseDead.json", "Transformations.json" };

	private SpriteBatch batch;
	private BitmapFont font;
	private final List<String> lines = new ArrayList<>();

	@Override
	public void create() {
		this.batch = new SpriteBatch();
		this.font = new BitmapFont();
		loadIni();
		loadAbilityJsons();
	}

	private void loadIni() {
		this.lines.add("Warsmash web boot");
		try (InputStream in = Gdx.files.internal("warsmash.ini").read()) {
			final DataTable ini = new DataTable(StringBundle.EMPTY);
			ini.readTXT(in, true);
			this.lines.add("warsmash.ini sections: " + ini.keySet().size());
			final Element emu = ini.get("Emulator");
			if (emu != null) {
				this.lines.add("  MaxPlayers=" + emu.getField("MaxPlayers") + "  GameVersion="
						+ emu.getField("GameVersion"));
			}
		}
		catch (final Throwable t) {
			this.lines.add("ini ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	private void loadAbilityJsons() {
		final JsonReader reader = new JsonReader();
		int totalAbilities = 0;
		for (final String name : ABILITY_FILES) {
			final String path = "abilityBehaviors/" + name;
			try {
				final JsonValue root = reader.parse(Gdx.files.internal(path));
				final JsonValue list = root.get("abilityList");
				final int count = (list == null) ? 0 : list.size;
				totalAbilities += count;
				this.lines.add("  " + name + ": " + count);
			}
			catch (final Throwable t) {
				this.lines.add("  " + name + " ERROR: " + t.getMessage());
			}
		}
		this.lines.add("ability entries total: " + totalAbilities);
	}

	@Override
	public void render() {
		Gdx.gl.glClearColor(0.05f, 0.1f, 0.15f, 1f);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		this.batch.begin();
		float y = Gdx.graphics.getHeight() - 20;
		for (final String line : this.lines) {
			this.font.draw(this.batch, line, 20, y);
			y -= 20;
		}
		this.batch.end();
	}

	@Override
	public void dispose() {
		this.batch.dispose();
		this.font.dispose();
	}
}
