package com.etheller.warsmash.parsers.fdf;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.utils.IntMap;

/**
 * Desktop-backing {@link FontGeneratorHolder}: wraps a
 * {@link FreeTypeFontGenerator} and translates {@link FontParameter} to
 * {@link FreeTypeFontParameter} internally. Must not be reachable from the web
 * (TeaVM) build's static reachability graph — the FreeType class has native
 * methods that can't be transpiled.
 */
public class FreeTypeFontGeneratorHolder implements FontGeneratorHolder {
	private final FreeTypeFontGenerator generator;
	private final IntMap<BitmapFont> sizeToFont;

	public FreeTypeFontGeneratorHolder(final FreeTypeFontGenerator generator) {
		this.generator = generator;
		this.sizeToFont = new IntMap<>();
	}

	@Override
	public BitmapFont generateFont(final FontParameter parameter) {
		BitmapFont font = this.sizeToFont.get(parameter.size);
		if (font == null) {
			final FreeTypeFontParameter freetypeParam = new FreeTypeFontParameter();
			freetypeParam.size = parameter.size;
			// enable incremental to support non-ascii characters like chinese when
			// free-type fonts contain the char.
			freetypeParam.incremental = true;
			font = this.generator.generateFont(freetypeParam);
			this.sizeToFont.put(parameter.size, font);
		}
		return font;
	}

	@Override
	public void dispose() {
		this.generator.dispose();
		// TODO maybe dispose the fonts
	}
}
