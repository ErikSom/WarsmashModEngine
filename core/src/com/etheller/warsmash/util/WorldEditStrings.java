package com.etheller.warsmash.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.etheller.warsmash.datasources.DataSource;

public class WorldEditStrings implements StringBundle {
	private final Map<String, String> bundleUpper;
	private final Map<String, String> bundleExact;
	private final Map<String, String> gameBundleUpper;
	private final Map<String, String> gameBundleExact;

	public WorldEditStrings(final DataSource dataSource) {
		if (dataSource.has("UI\\WorldEditStrings.txt")) {
			final StringMaps bundleMaps = loadStringMap(dataSource, "UI\\WorldEditStrings.txt");
			this.bundleUpper = bundleMaps.uppercase;
			this.bundleExact = bundleMaps.exact;
		}
		else {
			this.bundleUpper = null;
			this.bundleExact = null;
		}
		final StringMaps gameBundleMaps = loadStringMap(dataSource, "UI\\WorldEditGameStrings.txt");
		this.gameBundleUpper = gameBundleMaps.uppercase;
		this.gameBundleExact = gameBundleMaps.exact;
	}

	@Override
	public String getString(String string) {
		while (string.toUpperCase(Locale.ROOT).startsWith("WESTRING")) {
			final String resolved = internalGetString(string);
			if (resolved == null) {
				return string;
			}
			string = resolved;
		}
		return string;
	}

	private String internalGetString(final String key) {
		final String uppercaseKey = key.toUpperCase(Locale.ROOT);
		if (this.bundleUpper != null) {
			final String string = this.bundleUpper.get(uppercaseKey);
			if (string != null) {
				return string;
			}
		}
		return this.gameBundleUpper.get(uppercaseKey);
	}

	@Override
	public String getStringCaseSensitive(final String key) {
		if (this.bundleExact != null) {
			final String string = this.bundleExact.get(key);
			if (string != null) {
				return string;
			}
		}
		final String gameString = this.gameBundleExact.get(key);
		if (gameString != null) {
			return gameString;
		}
		return this.gameBundleUpper.get(key.toUpperCase(Locale.ROOT));
	}

	private static StringMaps loadStringMap(final DataSource dataSource, final String path) {
		final Map<String, String> uppercase = new HashMap<>();
		final Map<String, String> exact = new HashMap<>();
		try (InputStream fis = dataSource.getResourceAsStream(path);
				InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
			final StringBuilder lineBuilder = new StringBuilder();
			final char[] buffer = new char[4096];
			int read;
			while ((read = reader.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					final char c = buffer[i];
					if (c == '\r') {
						continue;
					}
					if (c == '\n') {
						addLine(lineBuilder.toString(), exact, uppercase);
						lineBuilder.setLength(0);
					}
					else {
						lineBuilder.append(c);
					}
				}
			}
			if (lineBuilder.length() > 0) {
				addLine(lineBuilder.toString(), exact, uppercase);
			}
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
		return new StringMaps(exact, uppercase);
	}

	private static void addLine(final String rawLine, final Map<String, String> exact, final Map<String, String> upper) {
		String line = rawLine;
		if (!line.isEmpty() && (line.charAt(0) == '\uFEFF')) {
			line = line.substring(1);
		}
		final String trimmed = line.trim();
		if (trimmed.isEmpty() || trimmed.startsWith("//")) {
			return;
		}
		final int separatorIndex = line.indexOf('=');
		if (separatorIndex == -1) {
			return;
		}
		final String key = line.substring(0, separatorIndex).trim();
		if (key.isEmpty()) {
			return;
		}
		final String value = stripQuotes(line.substring(separatorIndex + 1).trim());
		exact.put(key, value);
		upper.put(key.toUpperCase(Locale.ROOT), value);
	}

	private static String stripQuotes(final String string) {
		if ((string.length() >= 2) && (string.charAt(0) == '"') && (string.charAt(string.length() - 1) == '"')) {
			return string.substring(1, string.length() - 1);
		}
		return string;
	}

	private static final class StringMaps {
		private final Map<String, String> exact;
		private final Map<String, String> uppercase;

		private StringMaps(final Map<String, String> exact, final Map<String, String> uppercase) {
			this.exact = exact;
			this.uppercase = uppercase;
		}
	}
}
