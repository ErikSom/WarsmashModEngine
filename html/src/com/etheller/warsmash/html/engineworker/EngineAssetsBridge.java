package com.etheller.warsmash.html.engineworker;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Java bridge to the engine-internal asset cache populated by
 * {@code engine-worker-boot.js#w3FetchEngineAssets}. The asset list comes
 * from the gdx-teavm preload manifest (preload.txt) — same files that get
 * bundled into the main-thread {@code app.js}, here fetched as ArrayBuffers
 * and held in a JS Map keyed by relative path.
 *
 * <p>Used to seed an {@link com.etheller.warsmash.datasources.InMemoryDataSource}
 * layer in the worker's CompoundDataSource, so {@code Gdx.files.internal(...)}
 * resolves engine-bundled assets like {@code warsmash.ini} and
 * {@code abilityBehaviors/*.json} alongside MPQ contents.
 */
public final class EngineAssetsBridge {
	private EngineAssetsBridge() {
	}

	public static String[] paths() {
		final String joined = joinedPaths();
		return (joined == null || joined.isEmpty()) ? new String[0] : joined.split("\n");
	}

	@JSBody(script = "return self.__engineAssetPaths ? self.__engineAssetPaths.join('\\n') : '';")
	private static native String joinedPaths();

	public static byte[] read(final String path) {
		final Int8Array arr = readImpl(path);
		if (arr == null) {
			return null;
		}
		final int len = arr.getLength();
		final byte[] out = new byte[len];
		for (int i = 0; i < len; i++) {
			out[i] = arr.get(i);
		}
		return out;
	}

	@JSBody(params = { "path" }, script = "var a = self.__engineAssets && self.__engineAssets.get(path); return a || null;")
	private static native Int8Array readImpl(String path);
}
