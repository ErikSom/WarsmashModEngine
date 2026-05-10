/**
 * Process-wide cache for parsed MapInfo. Parsing a map's MPQ + w3i +
 * wts isn't free (~10-100ms depending on map size), and the lobby UI
 * touches the same map repeatedly: once when listed in the picker,
 * again when it's the selected map, again on every joiner's
 * peerConnected for re-broadcast. Caching by relPath keeps it cheap.
 *
 * Failures cache too — a corrupt or non-MPQ file should fail fast
 * the second time around rather than re-parsing on every retry.
 */

import { loadMapInfoFromOpfsPath, type MapInfo } from './mapInfo';

interface CacheEntry {
  /** Resolves with MapInfo on success, rejects with the original error
   *  on failure. We keep the same Promise object for both cases so
   *  parallel callers all observe the same state. */
  promise: Promise<MapInfo>;
}

const cache = new Map<string, CacheEntry>();

/** Get parsed MapInfo for an OPFS-relative map path. Memoized; the
 *  first caller for a given path triggers parsing, subsequent callers
 *  share the same Promise (and resolved value).
 *
 *  Pass the path relative to /w3/Maps/ (e.g.
 *  `FrozenThrone/(2)EchoIsles.w3x`), NOT the full engine path. */
export function getMapInfo(relPathFromMapsDir: string): Promise<MapInfo> {
  const existing = cache.get(relPathFromMapsDir);
  if (existing) return existing.promise;
  const promise = loadMapInfoFromOpfsPath(relPathFromMapsDir);
  cache.set(relPathFromMapsDir, { promise });
  return promise;
}

/** Same as getMapInfo but tolerates the engine-style "Maps/X/Y.w3x"
 *  path that the lobby state stores in `selectedMap`. */
export function getMapInfoForFullPath(fullPath: string): Promise<MapInfo> {
  const relPath = fullPath.startsWith('Maps/') ? fullPath.slice('Maps/'.length) : fullPath;
  return getMapInfo(relPath);
}

/** Clear a single entry — useful if the user re-uploads a map under
 *  the same path and we want the next read to pick up the new bytes. */
export function invalidateMapInfo(relPathFromMapsDir: string): void {
  cache.delete(relPathFromMapsDir);
}

/** Wipe the whole cache. Currently unused but exposed for tests /
 *  debug overlays. */
export function clearMapInfoCache(): void {
  cache.clear();
}
