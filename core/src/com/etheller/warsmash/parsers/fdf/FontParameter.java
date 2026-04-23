package com.etheller.warsmash.parsers.fdf;

/**
 * Platform-agnostic font request parameters, kept deliberately free of any
 * {@code com.badlogic.gdx.graphics.g2d.freetype.*} reference so both the
 * desktop (FreeType-backed) and the web (pre-baked BitmapFont / HTML canvas
 * backed) backends can consume the same type.
 *
 * <p>Only the fields the engine actually uses today are exposed. Extend when
 * MenuUI / MeleeUI / FDF start asking for borders / shadows / specific glyph
 * sets.
 */
public final class FontParameter {
	/** Pixel size the caller wants. 0 is treated as "use a platform default" by the backends. */
	public int size;

	public FontParameter() {
	}

	public FontParameter(final int size) {
		this.size = size;
	}
}
