package com.etheller.warsmash;

import com.badlogic.gdx.Screen;
import com.etheller.warsmash.viewer5.handlers.w3x.War3MapViewer;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.players.CPlayerUnitOrderListener;

/**
 * Pluggable factory that creates the map-gameplay screen. Introduced so
 * MenuUI can build a map screen without a direct
 * {@code new WarsmashGdxMapScreen(...)} reference — which would drag
 * WarsmashGdxMapScreen and its transitive subtree (MeleeUI, Terrain, TgaFile,
 * BufferedImage...) into the TeaVM web build's reachable graph.
 *
 * <p>The desktop launcher registers a real factory. The web build currently
 * registers nothing; {@link #get()} returns null and MenuUI.startMap is a
 * no-op on web.
 */
public interface MapScreenFactory {
	Screen create(War3MapViewer mapViewer, WarsmashGdxMultiScreenGame screenManager,
			WarsmashGdxMenuScreen menuScreen, CPlayerUnitOrderListener uiOrderListener);

	/** Single-slot registry. */
	MapScreenFactory[] REGISTRY = new MapScreenFactory[1];

	static void register(final MapScreenFactory factory) {
		REGISTRY[0] = factory;
	}

	static MapScreenFactory get() {
		return REGISTRY[0];
	}
}
