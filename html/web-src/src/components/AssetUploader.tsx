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
  type IndexSummary,
} from '../lib/assetStaging';
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
  const [statusMsg, setStatusMsg] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [progressPct, setProgressPct] = useState(0);
  const [staging, setStaging] = useState(false);

  useEffect(() => {
    installEngineWorkerGlobals();
    if (!('storage' in navigator) || !navigator.storage.getDirectory) {
      setErrorMsg('OPFS is not supported in this browser.');
      return;
    }
    if (hasStagedAssets()) {
      const idx = readIndex();
      const s = summarizeIndex(idx);
      setSummary(s);
      setStatusMsg(buildNextStep(s));
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
      {statusMsg && <div class="boot-status" style={{ marginTop: '6px' }}>{statusMsg}</div>}
      {errorMsg  && <div class="boot-error"  style={{ marginTop: '6px' }}>{errorMsg}</div>}
    </section>
  );
}

function msg(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
