package com.etheller.warsmash.html.network;

/**
 * Single source of truth for the Poki Netlib game ID under which all
 * Warsmash web-build lobbies are namespaced. Generated locally — not
 * registered server-side, just a routing key. Lobby codes assigned by the
 * netlib signaling server are scoped per game id, so two engines using
 * different ids cannot accidentally land in the same lobby.
 *
 * <p>Mirrors the constant in {@code html/web-src/multiplayer-test.ts}; if
 * one changes the other must too. (We keep separate copies because the JS
 * side runs without TeaVM-bridged constants and cross-importing TS into
 * Java is more trouble than the dup is worth.)
 */
public final class WarsmashWebGameId {
	/** Stable UUID; do NOT change without rotating both this and the TS copy. */
	public static final String UUID = "a664aaff-9291-4c9c-9f26-47b56eb7914a";

	private WarsmashWebGameId() {
	}
}
