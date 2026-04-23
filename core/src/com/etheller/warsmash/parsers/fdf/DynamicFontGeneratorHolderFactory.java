package com.etheller.warsmash.parsers.fdf;

import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.units.Element;

/**
 * Pluggable factory for {@link DynamicFontGeneratorHolder}. Each backend
 * installs its implementation at startup:
 *
 * <ul>
 *   <li>Desktop ({@code DesktopLauncher.main}): installs a factory that
 *       produces {@link FreeTypeDynamicFontGeneratorHolder} instances —
 *       the real FreeType-backed path.</li>
 *   <li>Web ({@code WebWarsmashGame.create}): installs a factory that
 *       produces the web stub returning pre-baked BitmapFonts so nothing on
 *       the reachability graph from MenuUI ever touches FreeType classes.</li>
 * </ul>
 *
 * <p>If nothing installs a factory, {@link #create(DataSource, Element)}
 * throws — the engine cannot render any UI text without it.
 */
public final class DynamicFontGeneratorHolderFactory {
	public interface Factory {
		DynamicFontGeneratorHolder create(DataSource dataSource, Element skin);
	}

	private static Factory installedFactory;

	private DynamicFontGeneratorHolderFactory() {
	}

	public static void install(final Factory factory) {
		installedFactory = factory;
	}

	public static DynamicFontGeneratorHolder create(final DataSource dataSource, final Element skin) {
		final Factory factory = installedFactory;
		if (factory == null) {
			throw new IllegalStateException(
					"DynamicFontGeneratorHolderFactory has not been installed by the current backend. "
							+ "Desktop should install FreeTypeDynamicFontGeneratorHolder; web should install "
							+ "its stub in WebWarsmashGame.create().");
		}
		return factory.create(dataSource, skin);
	}
}
