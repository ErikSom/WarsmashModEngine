/**
 * Stand-alone asset uploader — renders the WC3 folder picker, the
 * staging-progress bar, and an inline summary of what's currently
 * staged. Used by both /assets (dedicated management page) and could
 * be embedded by /play in a future refactor.
 *
 * Lighter-weight than EnginePage — does no engine boot, no canvas, no
 * splash. Just OPFS staging + summary readout. The "Manage maps"
 * modal is rendered alongside via MapsManager.
 */
import { useEffect, useState } from 'preact/hooks';
import {
  buildNextStep, formatSummary, hasStagedAssets,
  readIndex, stageFiles, summarizeIndex,
  type IndexEntry, type IndexSummary,
} from '../lib/assetStaging';
import {
  clearCachedGameVersion, detectGameVersion, formatGameVersion,
  readCachedGameVersion, resolveExactBuild, writeCachedGameVersion,
  type ExactBuild, type GameVersionInfo,
} from '../lib/gameVersion';
import { installEngineWorkerGlobals } from '../lib/opfs';
import { acquireWakeLock } from '../lib/wakeLock';

interface Props {
  /** Heading shown at the top of the uploader. */
  title?: string;
  /** Optional subhead description. */
  description?: string;
}

export default function AssetUploader({
  title = 'Warcraft III install',
  description = 'Pick your Warcraft III folder and the files will be staged into private browser storage (OPFS). The cached install is reused on every visit; you can re-pick to replace it.',
}: Props) {
  const [summary, setSummary] = useState<IndexSummary>({ fileCount: 0, mpqCount: 0, mapCount: 0, totalBytes: 0 });
  const [version, setVersion] = useState<GameVersionInfo | null>(null);
  const [build, setBuild] = useState<ExactBuild | null>(null);
  const [buildResolving, setBuildResolving] = useState(false);
  const [statusMsg, setStatusMsg] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [progressPct, setProgressPct] = useState(0);
  const [staging, setStaging] = useState(false);

  /**
   * Kick off exact-build resolution against the current index. Cache-
   * first: if a previous detection matches the current index by
   * fingerprint, we use it immediately and skip the MPQ + PE parse.
   * On cache miss we run the parse (a few hundred ms for a full WC3
   * install) and write the result back. `cancelled` guards against
   * the user re-staging mid-resolve.
   */
  function refreshDetection(idx: IndexEntry[]): () => void {
    const cached = readCachedGameVersion(idx);
    if (cached) {
      setVersion(cached.version);
      setBuild(cached.build);
      setBuildResolving(false);
      // eslint-disable-next-line no-console
      console.log('[asset-uploader] game version (cached):', formatGameVersion(cached.version, cached.build));
      return () => { /* nothing to cancel */ };
    }
    const v = detectGameVersion(idx);
    setVersion(v);
    setBuild(null);
    setBuildResolving(true);
    let cancelled = false;
    (async () => {
      try {
        const exact = await resolveExactBuild(idx, v.edition);
        if (cancelled) return;
        setBuild(exact);
        writeCachedGameVersion(idx, v, exact);
        // eslint-disable-next-line no-console
        console.log('[asset-uploader] game version:', formatGameVersion(v, exact));
      }
      catch (err) {
        // eslint-disable-next-line no-console
        console.warn('[asset-uploader] exact-build resolution failed:', err);
      }
      finally {
        if (!cancelled) setBuildResolving(false);
      }
    })();
    return () => { cancelled = true; };
  }

  useEffect(() => {
    installEngineWorkerGlobals();
    if (!('storage' in navigator) || !navigator.storage.getDirectory) {
      setErrorMsg('OPFS is not supported in this browser.');
      return;
    }
    if (hasStagedAssets()) {
      const idx = readIndex();
      setSummary(summarizeIndex(idx));
      setStatusMsg(buildNextStep(summarizeIndex(idx)));
      return refreshDetection(idx);
    }
  }, []);

  async function onPick(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const files = input.files;
    if (!files || !files.length) return;
    setErrorMsg('');
    setStaging(true);
    setProgressPct(0);
    setStatusMsg('');
    // Drop the cached version *before* staging starts. The new files
    // are about to land, so any cached result is necessarily stale; a
    // mid-stage error leaving us with stale cache would be worse than
    // a cache miss next mount.
    clearCachedGameVersion();
    let success = false;
    try {
      const result = await stageFiles(Array.from(files), {
        mode: 'directory',
        replaceExisting: true,
        onProgress: (p) => {
          const pct = p.totalBytes ? (p.bytes / p.totalBytes) * 100 : 100;
          setProgressPct(pct);
          const mb = (p.bytes / 1048576).toFixed(1);
          const totalMb = (p.totalBytes / 1048576).toFixed(1);
          setStatusMsg(`${p.done}/${p.total}  ${mb} / ${totalMb} MB  (${p.currentName})`);
        },
      });
      acquireWakeLock();
      setSummary(result.summary);
      refreshDetection(result.index);
      setStatusMsg(
        `Staged ${result.staged} files (${(result.totalBytes / 1048576).toFixed(1)} MB) `
        + `in ${result.durationSeconds.toFixed(1)}s. ${buildNextStep(result.summary)}`
      );
      success = true;
    }
    catch (err) {
      setErrorMsg('Staging failed: ' + msg(err));
    }
    finally {
      if (!success) {
        const idx = readIndex();
        setSummary(summarizeIndex(idx));
        refreshDetection(idx);
      }
      setStaging(false);
      input.value = '';
    }
  }

  return (
    <section class="mp-section">
      <div class="mp-section-header">
        <h2>{title}</h2>
      </div>
      <p class="mp-section-hint">{description}</p>

      <div class="row" style={{ marginTop: '8px' }}>
        <label
          class={`fakebtn ${summary.fileCount > 0 ? 'secondary' : 'primary'}`}
          for="dir-input"
          style={{ display: 'inline-block' }}
        >
          {summary.fileCount > 0 ? 'Re-pick Warcraft III folder' : 'Select Warcraft III folder'}
        </label>
        <input
          id="dir-input"
          type="file"
          /* @ts-expect-error — non-standard but supported in Chromium */
          webkitdirectory
          multiple
          style={{ display: 'none' }}
          disabled={staging}
          onChange={onPick}
        />
      </div>

      {staging && (
        <div class="progress" style={{ marginTop: '12px' }}>
          <div class="progress-bar" style={{ width: progressPct.toFixed(1) + '%' }} />
        </div>
      )}

      {summary.fileCount > 0 && (
        <div class="boot-summary" style={{ marginTop: '12px' }}>{formatSummary(summary)}</div>
      )}
      {version && summary.fileCount > 0 && (
        <div
          class={`boot-version boot-version-${version.edition}`}
          style={{ marginTop: '6px' }}
          title={version.evidence.length ? `Matched: ${version.evidence.join(', ')}` : undefined}
        >
          Detected: <strong>{formatGameVersion(version, build)}</strong>
          {!build && buildResolving && version.edition !== 'unknown' && (
            <span class="mp-section-hint"> (reading patch MPQ…)</span>
          )}
          {!build && !buildResolving && (version.edition === 'roc' || version.edition === 'tft') && (
            <span class="mp-section-hint"> (no patch MPQ or Warcraft III.exe — exact build unknown)</span>
          )}
          {build && (
            <span class="mp-section-hint"> &middot; from {build.source}</span>
          )}
        </div>
      )}
      {statusMsg && <div class="boot-status" style={{ marginTop: '6px' }}>{statusMsg}</div>}
      {errorMsg  && <div class="boot-error"  style={{ marginTop: '6px' }}>{errorMsg}</div>}
    </section>
  );
}

function msg(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
