package com.etheller.warsmash.parsers.fdf.frames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.etheller.warsmash.parsers.fdf.GameUI;
import com.etheller.warsmash.parsers.fdf.datamodel.TextJustify;

public class SingleStringFrame extends AbstractRenderableFrame {
	private Color color;
	private String text = "Default string";
	/** Parsed spans for WC3 colour codes ({@code |cAARRGGBB}...{@code |r}). */
	private List<ColorSpan> spans = Collections.singletonList(new ColorSpan("Default string", null));
	private final TextJustify justifyH;
	private final TextJustify justifyV;
	private final BitmapFont frameFont;
	private Color fontShadowColor;
	private float fontShadowOffsetX;
	private float fontShadowOffsetY;
	private float alpha = 1.0f;

	public SingleStringFrame(final String name, final UIFrame parent, final Color color, final TextJustify justifyH,
			final TextJustify justifyV, final BitmapFont frameFont) {
		super(name, parent);
		if (color == null) {
			throw new IllegalArgumentException();
		}
		this.color = color;
		this.justifyH = justifyH;
		this.justifyV = justifyV;
		this.frameFont = frameFont;
		this.text = name;
		this.spans = parseSpans(name);
	}

	public void setText(final String text) {
		if (text == null) {
			throw new IllegalArgumentException();
		}
		this.text = text;
		this.spans = parseSpans(text);
	}

	public void setColor(final Color color) {
		if (color == null) {
			throw new IllegalArgumentException();
		}
		this.color = color;
	}

	public Color getColor() {
		return this.color;
	}

	public void setFontShadowColor(final Color fontShadowColor) {
		this.fontShadowColor = fontShadowColor;
	}

	public void setFontShadowOffsetX(final float fontShadowOffsetX) {
		this.fontShadowOffsetX = fontShadowOffsetX;
	}

	public void setFontShadowOffsetY(final float fontShadowOffsetY) {
		this.fontShadowOffsetY = fontShadowOffsetY;
	}

	@Override
	protected void internalRender(final SpriteBatch batch, final BitmapFont baseFont, final GlyphLayout glyphLayout) {
		// Sum visible-text widths across spans so justify ignores colour-code
		// markers (which the font would otherwise render as literal '|c...').
		float totalWidth = 0f;
		for (final ColorSpan span : this.spans) {
			if (span.text.isEmpty()) {
				continue;
			}
			glyphLayout.setText(this.frameFont, span.text);
			totalWidth += glyphLayout.width;
		}
		final float startX;
		switch (this.justifyH) {
		case CENTER:
			startX = this.renderBounds.x + ((this.renderBounds.width - totalWidth) / 2);
			break;
		case RIGHT:
			startX = (this.renderBounds.x + this.renderBounds.width) - totalWidth;
			break;
		case LEFT:
		default:
			startX = this.renderBounds.x;
			break;
		}
		final float y;
		switch (this.justifyV) {
		case MIDDLE:
			y = this.renderBounds.y + ((this.renderBounds.height + this.frameFont.getLineHeight()) / 2);
			break;
		case TOP:
			y = (this.renderBounds.y + this.renderBounds.height);
			break;
		case BOTTOM:
		default:
			y = this.renderBounds.y + this.frameFont.getLineHeight();
			break;
		}
		final float frameAlpha = this.color.a * this.alpha;
		if (this.fontShadowColor != null) {
			this.frameFont.setColor(this.fontShadowColor.r, this.fontShadowColor.g, this.fontShadowColor.b,
					this.fontShadowColor.a * this.alpha);
			float xs = startX + this.fontShadowOffsetX;
			final float ys = y + this.fontShadowOffsetY;
			for (final ColorSpan span : this.spans) {
				if (span.text.isEmpty()) {
					continue;
				}
				this.frameFont.draw(batch, span.text, xs, ys);
				glyphLayout.setText(this.frameFont, span.text);
				xs += glyphLayout.width;
			}
		}
		float x = startX;
		for (final ColorSpan span : this.spans) {
			if (span.text.isEmpty()) {
				continue;
			}
			final Color c = (span.color != null) ? span.color : this.color;
			// Span supplies RGB; alpha comes from the frame so |c00...|r
			// (alpha=00) markers used by older maps still render visibly.
			this.frameFont.setColor(c.r, c.g, c.b, frameAlpha);
			this.frameFont.draw(batch, span.text, x, y);
			glyphLayout.setText(this.frameFont, span.text);
			x += glyphLayout.width;
		}
	}

	/**
	 * Parse WC3 colour-code markers into a flat span list. {@code |cAARRGGBB}
	 * starts a new colour, {@code |r} resets to the frame default. Anything
	 * outside the markers becomes a default-colour span (color = null).
	 * Malformed markers (truncated, non-hex digits) fall through as literal
	 * text so we never lose characters.
	 */
	private static List<ColorSpan> parseSpans(final String text) {
		if (text == null) {
			// {@link AbstractRenderableFrame} constructor accepts a null name;
			// existing callers depend on the loose behaviour.
			return Collections.singletonList(new ColorSpan("", null));
		}
		final List<ColorSpan> out = new ArrayList<>();
		final int n = text.length();
		Color current = null;
		int spanStart = 0;
		int i = 0;
		while (i < n) {
			final char c = text.charAt(i);
			if ((c == '|') && ((i + 1) < n)) {
				final char next = text.charAt(i + 1);
				if ((next == 'c') && ((i + 10) <= n) && isHexRange(text, i + 2, 8)) {
					if (i > spanStart) {
						out.add(new ColorSpan(text.substring(spanStart, i), current));
					}
					final int rr = Integer.parseInt(text.substring(i + 4, i + 6), 16);
					final int gg = Integer.parseInt(text.substring(i + 6, i + 8), 16);
					final int bb = Integer.parseInt(text.substring(i + 8, i + 10), 16);
					current = new Color(rr / 255f, gg / 255f, bb / 255f, 1f);
					i += 10;
					spanStart = i;
					continue;
				}
				if ((next == 'r') || (next == 'R')) {
					if (i > spanStart) {
						out.add(new ColorSpan(text.substring(spanStart, i), current));
					}
					current = null;
					i += 2;
					spanStart = i;
					continue;
				}
			}
			i++;
		}
		if (spanStart < n) {
			out.add(new ColorSpan(text.substring(spanStart), current));
		}
		if (out.isEmpty()) {
			out.add(new ColorSpan("", null));
		}
		return out;
	}

	private static boolean isHexRange(final String s, final int from, final int len) {
		if ((from + len) > s.length()) {
			return false;
		}
		for (int i = from; i < (from + len); i++) {
			final char c = s.charAt(i);
			final boolean hex = ((c >= '0') && (c <= '9')) || ((c >= 'a') && (c <= 'f')) || ((c >= 'A') && (c <= 'F'));
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	private static final class ColorSpan {
		final String text;
		/** {@code null} → use the frame's default colour. */
		final Color color;

		ColorSpan(final String text, final Color color) {
			this.text = text;
			this.color = color;
		}
	}

	@Override
	protected void innerPositionBounds(final GameUI gameUI, final Viewport viewport) {
	}

	public void setAlpha(final float alpha) {
		this.alpha = alpha;

	}

}
