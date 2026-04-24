package com.etheller.warsmash.datasources;

/**
 * Pluggable indirection that guarantees a given map's bytes are reachable
 * from a {@link DataSource} before Warsmash's strictly-synchronous
 * {@code War3Map} / {@code War3MapViewer.beginLoadingMapFromDataSource}
 * pipeline runs.
 *
 * <p>On desktop, maps are live on disk; the default implementation is a
 * pass-through that invokes {@code onReady} immediately.
 *
 * <p>On the web backend, the {@link DataSource} is an in-memory snapshot
 * built from OPFS — listing the full set of available maps requires a
 * cheap path index, but materializing the MPQ bytes for any one map is an
 * async read against OPFS. The web launcher installs an implementation
 * that async-fetches the selected map's bytes into the shared
 * {@link InMemoryDataSource} via {@link InMemoryDataSource#put(String, byte[])},
 * then invokes {@code onReady}. The invariant the ensurer provides is
 * exactly what the downstream sync code assumes:
 * {@code dataSource.has(mapKey)} and {@code dataSource.read(mapKey)} both
 * return real bytes by the time {@code onReady} fires.
 *
 * <p>Same static-plugin shape as
 * {@link com.etheller.warsmash.util.Platform},
 * {@link com.etheller.warsmash.parsers.fdf.DynamicFontGeneratorHolderFactory},
 * and {@link com.etheller.warsmash.networking.NetworkPlatform}: call
 * {@link #install(Ensurer)} once at startup from the backend launcher.
 */
public final class MapBytesEnsurer {
	public interface Ensurer {
		/**
		 * Ensure {@code dataSource} can synchronously serve the bytes of
		 * {@code mapKey} before invoking {@code onReady}. Implementations
		 * must always invoke {@code onReady} (even on failure); downstream
		 * code already handles the "bytes missing" case by throwing.
		 */
		void ensure(DataSource dataSource, String mapKey, Runnable onReady);
	}

	private static Ensurer ensurer = (ds, key, cb) -> cb.run();

	public static void install(final Ensurer e) {
		if (e != null) {
			ensurer = e;
		}
	}

	public static Ensurer get() {
		return ensurer;
	}

	private MapBytesEnsurer() {
	}
}
