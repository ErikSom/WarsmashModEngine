package com.etheller.warsmash.parsers.fdf;

import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.units.Element;

/**
 * Resolves a logical font name (as written in {@code war3skins.txt} — e.g.
 * "MasterFont", "EscMenuTextFont", etc.) to a {@link FontGeneratorHolder} that
 * can produce {@link com.badlogic.gdx.graphics.g2d.BitmapFont}s at various
 * sizes.
 *
 * <p>Backend-pluggable so the web build (which can't link
 * libgdx-freetype's JNI) can install an implementation that returns pre-baked
 * / canvas-rendered fonts instead of reaching FreeType classes.
 *
 * <p>Call sites obtain an instance via
 * {@link DynamicFontGeneratorHolderFactory#create(DataSource, Element)}.
 */
public interface DynamicFontGeneratorHolder {
	FontGeneratorHolder getFontGenerator(String font);

	void dispose();
}
