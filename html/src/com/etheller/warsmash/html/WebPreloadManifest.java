package com.etheller.warsmash.html;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import com.etheller.warsmash.parsers.w3x.w3i.War3MapW3i;
import com.etheller.warsmash.parsers.w3x.w3i.War3MapW3iFlags;
import com.etheller.warsmash.util.WarsmashConstants;

final class WebPreloadManifest {
	private static final String[] CORE_PREFIXES = {
			"abilities/",
			"buildings/",
			"doodads/",
			"environment/",
			"music/",
			"objects/",
			"replaceabletextures/",
			"scripts/",
			"sharedmodels/",
			"sound/",
			"splats/",
			"terrainart/",
			"textures/",
			"ui/",
			"units/",
			"widgets/",
	};

	private WebPreloadManifest() {
	}

	public static List<String> build(final List<String> extractedPaths, final String selectedMapPath,
			final War3MapW3i mapInfo) {
		final LinkedHashSet<String> manifest = new LinkedHashSet<>();
		final String selectedMapPathLower = normalize(selectedMapPath);
		final String tilesetFile = Character.toLowerCase(mapInfo.getTileset()) + ".mpq";
		final String tilesetPrefix = tilesetFile + "/";
		final String tilesetModPrefix = "_tilesets/" + Character.toLowerCase(mapInfo.getTileset()) + ".w3mod/";
		int gameDataSet = mapInfo.getGameDataSet();
		if (gameDataSet <= 0) {
			gameDataSet = mapInfo.hasFlag(War3MapW3iFlags.MELEE_MAP) ? 2 : 1;
		}
		final String dataSetPrefix = ((gameDataSet == 1) ? "custom_v" : "melee_v")
				+ WarsmashConstants.GAME_VERSION + "/";

		for (final String path : extractedPaths) {
			final String lowerPath = normalize(path);
			if (lowerPath.equals(selectedMapPathLower)) {
				manifest.add(path);
				continue;
			}
			if (isAudioOrVideo(lowerPath) || isMap(lowerPath)) {
				continue;
			}
			if (lowerPath.equals(tilesetFile)
					|| lowerPath.startsWith(tilesetPrefix)
					|| lowerPath.startsWith(tilesetModPrefix)
					|| lowerPath.startsWith(dataSetPrefix)
					|| startsWithAny(lowerPath, CORE_PREFIXES)) {
				manifest.add(path);
			}
		}

		if (!manifest.contains(selectedMapPath)) {
			manifest.add(selectedMapPath);
		}
		return new ArrayList<>(manifest);
	}

	private static boolean startsWithAny(final String path, final String[] prefixes) {
		for (final String prefix : prefixes) {
			if (path.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isAudioOrVideo(final String lowerPath) {
		// Movies are large and the engine has no video playback path, so we
		// drop them from the preload to save tens of MB. Sound effects and
		// music are kept — see WebExtensions.audio for the playback wiring
		// that consumes them.
		return lowerPath.startsWith("movies/")
				|| lowerPath.endsWith(".avi")
				|| lowerPath.endsWith(".mp4")
				|| lowerPath.endsWith(".mpg")
				|| lowerPath.endsWith(".mpeg");
	}

	private static boolean isMap(final String lowerPath) {
		return lowerPath.endsWith(".w3m") || lowerPath.endsWith(".w3x");
	}

	private static String normalize(final String path) {
		return path.replace('\\', '/').toLowerCase(Locale.ROOT);
	}
}
