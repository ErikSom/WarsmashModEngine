/**
 * Preact hook bundling the cache-first read + async-detect-on-miss flow
 * shared by LobbyRoom, LobbyBrowser, and AssetUploader. The hook
 * resolves to the same `{ version, build }` pair those components were
 * managing by hand; centralised so they don't drift.
 *
 * Behaviour:
 *   - Cache hit  → returns immediately, `resolving === false`.
 *   - Cache miss → returns the coarse edition right away while the
 *                  async exact-build resolution runs; flips `build`
 *                  + `resolving` when it completes and writes the
 *                  cache for next mount.
 *   - No staged install → unknown edition, `resolving === false`.
 *
 * AssetUploader doesn't use this directly because it needs to drive
 * detection imperatively (clear cache before staging, refresh after).
 */
import { useEffect, useMemo, useState } from 'preact/hooks';
import { readIndex } from './assetStaging';
import {
  detectGameVersion, formatGameVersion, readCachedGameVersion,
  resolveExactBuild, writeCachedGameVersion,
  type ExactBuild, type GameVersionInfo,
} from './gameVersion';

export interface UseGameVersionResult {
  version: GameVersionInfo;
  build: ExactBuild | null;
  /** True while the async exact-build resolution is in flight. */
  resolving: boolean;
}

export function useGameVersion(logPrefix = '[useGameVersion]'): UseGameVersionResult {
  // Read the index *once* at mount. The staged install doesn't change
  // while we're sitting on a page, and re-reading localStorage on
  // every render would invalidate `useMemo`s downstream.
  const stagedIndex = useMemo(() => readIndex(), []);
  const cached = useMemo(() => readCachedGameVersion(stagedIndex), [stagedIndex]);

  const [version, setVersion] = useState<GameVersionInfo>(
    () => cached?.version ?? detectGameVersion(stagedIndex),
  );
  const [build, setBuild] = useState<ExactBuild | null>(cached?.build ?? null);
  const [resolving, setResolving] = useState<boolean>(!cached);

  useEffect(() => {
    if (cached) {
      // eslint-disable-next-line no-console
      console.log(`${logPrefix} game version (cached):`, formatGameVersion(cached.version, cached.build));
      return;
    }
    let cancelled = false;
    setResolving(true);
    (async () => {
      try {
        const v = detectGameVersion(stagedIndex);
        if (!cancelled) setVersion(v);
        const b = await resolveExactBuild(stagedIndex, v.edition);
        if (cancelled) return;
        setBuild(b);
        writeCachedGameVersion(stagedIndex, v, b);
        // eslint-disable-next-line no-console
        console.log(`${logPrefix} game version:`, formatGameVersion(v, b));
      }
      catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`${logPrefix} exact-build resolution failed:`, err);
      }
      finally {
        if (!cancelled) setResolving(false);
      }
    })();
    return () => { cancelled = true; };
  }, [stagedIndex, cached, logPrefix]);

  return { version, build, resolving };
}
