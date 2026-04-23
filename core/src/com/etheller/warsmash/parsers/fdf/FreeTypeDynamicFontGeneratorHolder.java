package com.etheller.warsmash.parsers.fdf;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.DataSourceFileHandle;

/**
 * Desktop-backing {@link DynamicFontGeneratorHolder}: reads TTF files out of
 * the data source and hands them to {@link FreeTypeFontGenerator}. Must not be
 * reachable from the web (TeaVM) build — FreeType's native methods can't be
 * transpiled.
 */
public class FreeTypeDynamicFontGeneratorHolder implements DynamicFontGeneratorHolder {
	private final DataSource dataSource;
	private final Element skin;
	private final Map<String, FontGeneratorHolder> fontNameToGenerator;

	public FreeTypeDynamicFontGeneratorHolder(final DataSource dataSource, final Element skin) {
		this.dataSource = dataSource;
		this.skin = skin;
		this.fontNameToGenerator = new HashMap<>();
	}

	@Override
	public FontGeneratorHolder getFontGenerator(final String font) {
		FontGeneratorHolder fontGenerator = this.fontNameToGenerator.get(font);
		if (fontGenerator == null) {
			final String fontName = this.skin.getField(font);
			if (fontName == null) {
				throw new IllegalStateException("No such font: " + font);
			}
			if (!this.dataSource.has(fontName)) {
				throw new IllegalStateException("No such font file: " + fontName + " (for \"" + font + "\")");
			}
			fontGenerator = new FreeTypeFontGeneratorHolder(
					new FreeTypeFontGenerator(new DataSourceFileHandle(this.dataSource, fontName)));
			this.fontNameToGenerator.put(font, fontGenerator);
		}
		return fontGenerator;
	}

	@Override
	public void dispose() {
		for (final FontGeneratorHolder generator : this.fontNameToGenerator.values()) {
			generator.dispose();
		}
		this.fontNameToGenerator.clear();
	}
}
