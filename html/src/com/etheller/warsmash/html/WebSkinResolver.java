package com.etheller.warsmash.html;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.Texture;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.units.DataTable;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.ImageUtils;
import com.etheller.warsmash.util.StringBundle;
import com.etheller.warsmash.util.WarsmashConstants;
import com.etheller.warsmash.viewer5.handlers.w3x.rendersim.ability.AbilityDataUI.SkinResolver;

final class WebSkinResolver implements SkinResolver {
	private static final String DEFAULT_DISABLED_PREFIX = "ReplaceableTextures\\CommandButtonsDisabled\\DIS";

	private final DataSource dataSource;
	private final DataTable skinData;
	private final Element skin;
	private final Map<String, Texture> pathToTexture = new HashMap<>();

	WebSkinResolver(final DataSource dataSource, final String skinName) {
		this.dataSource = dataSource;
		this.skinData = loadSkinData(dataSource);
		final Element defaultSkin = this.skinData.get("Default");
		Element userSkin = (skinName == null) ? null : this.skinData.get(skinName);
		if (userSkin == null) {
			userSkin = defaultSkin;
		}
		final Element customSkin = this.skinData.get("CustomSkin");
		if ((defaultSkin != null) && (userSkin != null)) {
			for (final String key : defaultSkin.keySet()) {
				if (!userSkin.hasField(key)) {
					userSkin.setField(key, defaultSkin.getField(key));
				}
			}
		}
		if ((customSkin != null) && (userSkin != null)) {
			for (final String key : customSkin.keySet()) {
				userSkin.setField(key, customSkin.getField(key));
			}
		}
		this.skin = userSkin;
	}

	@Override
	public String getSkinField(String file) {
		if (file == null) {
			throw new NullPointerException("file is null");
		}
		if ((this.skin != null) && this.skin.hasField(file)) {
			return this.skin.getField(file);
		}
		final String fieldVersioned = file + "_V" + WarsmashConstants.GAME_VERSION;
		if ((this.skin != null) && this.skin.hasField(fieldVersioned)) {
			return this.skin.getField(fieldVersioned);
		}
		if ("CommandButtonDisabledArtPath".equals(file)) {
			return DEFAULT_DISABLED_PREFIX;
		}
		return file;
	}

	@Override
	public String trySkinField(String file) {
		if (file == null) {
			throw new NullPointerException("file is null");
		}
		if ((this.skin != null) && this.skin.hasField(file)) {
			return this.skin.getField(file);
		}
		final String fieldVersioned = file + "_V" + WarsmashConstants.GAME_VERSION;
		if ((this.skin != null) && this.skin.hasField(fieldVersioned)) {
			return this.skin.getField(fieldVersioned);
		}
		return file;
	}

	@Override
	public Texture loadTexture(String path) {
		if ((path == null) || path.isEmpty()) {
			return null;
		}
		Texture texture = this.pathToTexture.get(path);
		if (texture == null) {
			final String originalPath = path;
			final int lastDotIndex = path.lastIndexOf('.');
			if (lastDotIndex == -1) {
				path = path + ".blp";
			}
			else {
				path = path.substring(0, lastDotIndex) + ".blp";
			}
			try {
				texture = ImageUtils.getAnyExtensionTexture(this.dataSource, path);
				this.pathToTexture.put(path, texture);
				this.pathToTexture.put(originalPath, texture);
			}
			catch (final Exception exc) {
				return null;
			}
		}
		return texture;
	}

	@Override
	public DataTable getSkinData() {
		return this.skinData;
	}

	private static DataTable loadSkinData(final DataSource dataSource) {
		final DataTable skinsTable = new DataTable(StringBundle.EMPTY);
		readTxtIntoTable(dataSource, skinsTable, "UI\\war3skins.txt");
		readTxtIntoTable(dataSource, skinsTable, "Units\\CommandFunc.txt");
		readTxtIntoTable(dataSource, skinsTable, "Units\\CommandStrings.txt");
		if (dataSource.has("war3mapSkin.txt")) {
			readTxtIntoTable(dataSource, skinsTable, "war3mapSkin.txt");
		}
		return skinsTable;
	}

	private static void readTxtIntoTable(final DataSource dataSource, final DataTable table, final String path) {
		try (InputStream stream = dataSource.getResourceAsStream(path)) {
			if (stream != null) {
				table.readTXT(stream, true);
			}
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
