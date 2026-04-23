package com.etheller.warsmash.html;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.parsers.fdf.DynamicFontGeneratorHolder;
import com.etheller.warsmash.parsers.fdf.FontGeneratorHolder;
import com.etheller.warsmash.parsers.fdf.FontParameter;
import com.etheller.warsmash.units.Element;

/**
 * Web stub for {@link DynamicFontGeneratorHolder}. Returns libgdx's built-in
 * default BitmapFont (the Liberation-Sans-derived {@code lsans-15} atlas that
 * already ships with the web build's copied assets) for every font name and
 * size.
 *
 * <p>This is the bare-minimum implementation that unblocks {@code MenuUI}
 * construction on web — the text won't look like classic WC3 yet, but every
 * layout and click target becomes reachable. Proper pre-baked BitmapFonts
 * matching the skin's FRIZQT__/MORPHEUS/etc. TTFs come in a follow-up.
 *
 * <p>Crucially, this file contains zero {@code gdx.graphics.g2d.freetype.*}
 * references, so installing it on web keeps FreeType entirely off the
 * reachability graph.
 */
final class WebDynamicFontGeneratorHolder implements DynamicFontGeneratorHolder {
	private final DataSource dataSource;
	private final Element skin;
	private final Map<String, FontGeneratorHolder> fontNameToGenerator = new HashMap<>();

	WebDynamicFontGeneratorHolder(final DataSource dataSource, final Element skin) {
		this.dataSource = dataSource;
		this.skin = skin;
	}

	@Override
	public FontGeneratorHolder getFontGenerator(final String font) {
		FontGeneratorHolder holder = this.fontNameToGenerator.get(font);
		if (holder == null) {
			holder = new StubHolder();
			this.fontNameToGenerator.put(font, holder);
		}
		return holder;
	}

	@Override
	public void dispose() {
		for (final FontGeneratorHolder holder : this.fontNameToGenerator.values()) {
			holder.dispose();
		}
		this.fontNameToGenerator.clear();
	}

	/**
	 * Placeholder: uses libgdx's built-in default BitmapFont for every request.
	 * {@code new BitmapFont()} loads {@code com/badlogic/gdx/utils/lsans-15.fnt},
	 * which is already in the web build's classpath / copied-assets set. The
	 * {@code parameter.size} is currently ignored — libgdx's default BitmapFont
	 * is fixed-size; swap in per-size pre-baked fonts here once we ship them.
	 */
	private static final class StubHolder implements FontGeneratorHolder {
		private BitmapFont defaultFont;

		@Override
		public BitmapFont generateFont(final FontParameter parameter) {
			if (this.defaultFont == null) {
				this.defaultFont = new BitmapFont();
			}
			return this.defaultFont;
		}

		@Override
		public void dispose() {
			if (this.defaultFont != null) {
				this.defaultFont.dispose();
				this.defaultFont = null;
			}
		}
	}
}
