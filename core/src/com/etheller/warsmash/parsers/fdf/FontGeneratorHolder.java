package com.etheller.warsmash.parsers.fdf;

import com.badlogic.gdx.graphics.g2d.BitmapFont;

/**
 * A per-font-name handle the engine uses to produce {@link BitmapFont}s at
 * various sizes. The interface was originally a concrete class wrapping
 * {@code FreeTypeFontGenerator}; it's now backend-pluggable so the web build
 * (which can't link to libgdx-freetype's JNI) can swap in a pre-baked /
 * canvas-based implementation without reaching FreeType classes.
 *
 * <p>Desktop implementation: {@link FreeTypeFontGeneratorHolder}. Web
 * implementation lives in the html module.
 */
public interface FontGeneratorHolder {
	BitmapFont generateFont(FontParameter parameter);

	void dispose();
}
