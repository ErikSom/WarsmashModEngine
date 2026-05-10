/**
 * EnginePage — the orchestrator for the /play route. Auto-boots the
 * engine when assets are staged, so clicking "Play" on the landing
 * page drops the user straight into the game with no intermediate
 * "Play game" click. Only first-time visitors (no install staged)
 * see an upload prompt; once they pick their folder, staging runs
 * and the engine boots immediately on completion.
 *
 * Boot state machine:
 *   loading   — initial OPFS read
 *   no-assets — first-time setup card with folder picker
 *   staging   — upload progress bar
 *   running   — engine alive (canvas + splash visible underneath)
 *
 * Map management (the previous "Manage maps" button) lives at
 * /assets now; /play is purely "I want to play right now".
 *
 * Multiplayer integration: a couple of globals are exposed so the
 * /multiplayer page can drive engine boot remotely:
 *   window.__startEngine()   — kick off the engine, equivalent to
 *                              what the auto-boot path does
 *   window.__engineStarted() — has the engine been booted?
 *   window.__attachWorker(w) — multiplayer page hooks the engine
 *                              worker for postMessage routing
 *
 * The desync overlay is rendered here too so it can sit above the
 * canvas without a separate root.
 */
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import {
  formatSummary, hasStagedAssets,
  readIndex, stageFiles, summarizeIndex, writeIndex,
  type IndexSummary,
} from '../lib/assetStaging';
import { bootEngineWorker, type EngineHandle } from '../lib/engineBoot';
import {
  attachEngineWorker, consumeStartPayload, getLobbyState,
  type StartPayload,
} from '../lib/lobbyClient';
import { clearOpfs, installEngineWorkerGlobals } from '../lib/opfs';
import { acquireWakeLock, installWakeLockReacquire } from '../lib/wakeLock';
import DesyncOverlay, { type DesyncReportPayload } from './DesyncOverlay';

type BootState = 'loading' | 'no-assets' | 'staging' | 'running';

declare global {
  interface Window {
    __startEngine?: () => void;
    __engineStarted?: () => boolean;
    __attachWorker?: (w: Worker) => void;
  }
}

interface Props {
  /** id of the canvas element rendered by the host Astro page. */
  canvasId?: string;
  /** id of the splash element rendered by the host Astro page. */
  splashId?: string;
  /** id of the canvas wrapper element (toggles between display:none/flex). */
  canvasWrapId?: string;
}

export default function EnginePage({
  canvasId = 'canvas',
  splashId = 'splash',
  canvasWrapId = 'canvas-wrap',
}: Props) {
  const [boot, setBoot] = useState<BootState>('loading');
  const [summary, setSummary] = useState<IndexSummary>({ fileCount: 0, mpqCount: 0, mapCount: 0, totalBytes: 0 });
  const [statusMsg, setStatusMsg] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [progressPct, setProgressPct] = useState(0);
  const [desync, setDesync] = useState<DesyncReportPayload | null>(null);
  const engineHandle = useRef<EngineHandle | null>(null);
  const sessionDirty = useRef(false);
  /** Multiplayer start payload waiting to be forwarded to the engine
   *  worker once it reaches the menu screen. Populated either via
   *  consumeStartPayload (post-nav from /multiplayer) or via the lobby
   *  module's onStartFromHost firing while /play is already loaded. */
  const queuedMpStart = useRef<StartPayload | null>(null);
  /** Guards onPlay against being called twice (e.g. from auto-boot
   *  AND from a manual click). */
  const bootInFlight = useRef(false);

  // Splash control — imperative because the splash element lives in
  // the Astro server-rendered tree, not inside this component.
  const showSplash = useCallback(() => {
    const el = document.getElementById(splashId);
    if (el) el.style.display = 'flex';
  }, [splashId]);
  const hideSplash = useCallback(() => {
    const el = document.getElementById(splashId);
    if (el) el.style.display = 'none';
  }, [splashId]);

  // ---- Init: install globals, purge legacy state, auto-boot if ready ----
  useEffect(() => {
    let cancelled = false;
    console.log('[EnginePage] hydrated, beginning init.');
    installEngineWorkerGlobals();
    installWakeLockReacquire(() => sessionDirty.current || boot === 'running');

    (async () => {
      try {
        if (!('storage' in navigator) || !navigator.storage.getDirectory) {
          setErrorMsg('OPFS is not supported in this browser. Try a Chromium-based browser (Chrome, Edge, Opera).');
          setBoot('no-assets');
          return;
        }
        await purgeLegacyExtractedDir();
        if (cancelled) return;

        if (hasStagedAssets()) {
          const idx = readIndex();
          const s = summarizeIndex(idx);
          setSummary(s);

          // Stash any pending multiplayer payload BEFORE auto-booting
          // so the engine worker picks it up on its first menu-ready
          // event (otherwise we'd race with consumeStartPayload).
          const mpPayload = consumeStartPayload();
          if (mpPayload) {
            queuedMpStart.current = mpPayload;
            (window as any).__mpPendingStart = true;
          }

          if (s.mapCount > 0) {
            console.log('[EnginePage] staged assets present, auto-booting engine.');
            await tryBootEngine();
          }
          else {
            // Edge case: assets staged but no .w3x/.w3m. User must
            // re-pick a folder that includes a stock map.
            setStatusMsg('No map files staged yet. Re-pick your Warcraft III folder so a stock map is included.');
            setBoot('no-assets');
          }
        }
        else {
          setBoot('no-assets');
        }
      }
      catch (err) {
        console.error('[EnginePage] init failed:', err);
        setErrorMsg('Init failed: ' + msg(err));
        setBoot('no-assets');
      }
    })();
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- Expose engine-control globals for /multiplayer to call ----
  useEffect(() => {
    window.__startEngine = () => { void tryBootEngine(); };
    window.__engineStarted = () => boot === 'running';
    return () => {
      delete window.__startEngine;
      delete window.__engineStarted;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boot]);

  // ---- File pick → staging → auto-boot on completion ----
  async function onDirectoryPick(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const files = input.files;
    if (!files || !files.length) return;
    await runStaging(Array.from(files), 'directory', /*replace*/ true);
    input.value = '';
  }

  async function runStaging(files: File[], mode: 'directory' | 'files', replace: boolean) {
    setErrorMsg('');
    setBoot('staging');
    setProgressPct(0);
    setStatusMsg('');
    let success = false;
    try {
      const result = await stageFiles(files, {
        mode,
        replaceExisting: replace,
        sessionDirty: sessionDirty.current,
        onProgress: (p) => {
          const pct = p.totalBytes ? (p.bytes / p.totalBytes) * 100 : 100;
          setProgressPct(pct);
          const mb = (p.bytes / 1048576).toFixed(1);
          const totalMb = (p.totalBytes / 1048576).toFixed(1);
          setStatusMsg(`${p.done}/${p.total}  ${mb} / ${totalMb} MB  (${p.currentName})`);
        },
      });
      sessionDirty.current = true;
      setSummary(result.summary);
      setStatusMsg(`Staged ${result.staged} files (${(result.totalBytes / 1048576).toFixed(1)} MB) in ${result.durationSeconds.toFixed(1)}s.`);
      success = true;
    }
    catch (err) {
      setErrorMsg('Staging failed: ' + msg(err));
    }
    finally {
      if (!success && replace) sessionDirty.current = false;
      const idx = readIndex();
      const s = summarizeIndex(idx);
      setSummary(s);
      if (success && s.mapCount > 0) {
        // Auto-boot now that we have what we need.
        await tryBootEngine();
      }
      else if (success && s.mapCount === 0) {
        setStatusMsg('No map files were included. Re-pick your folder so a stock map (e.g. Echo Isles) is present.');
        setBoot('no-assets');
      }
      else {
        setBoot('no-assets');
      }
    }
  }

  // ---- Engine boot ----
  async function tryBootEngine() {
    if (bootInFlight.current) return;
    if (boot === 'running') return;
    bootInFlight.current = true;
    console.log('[EnginePage] tryBootEngine starting.');

    setBoot('running');
    setErrorMsg('');
    acquireWakeLock();

    const wrap = document.getElementById(canvasWrapId);
    if (wrap) wrap.style.display = 'flex';
    showSplash();

    const canvas = document.getElementById(canvasId);
    if (!(canvas instanceof HTMLCanvasElement)) {
      setErrorMsg(`Canvas element #${canvasId} not found in DOM.`);
      setBoot('no-assets');
      hideSplash();
      if (wrap) wrap.style.display = 'none';
      bootInFlight.current = false;
      return;
    }
    if (typeof OffscreenCanvas === 'undefined' || typeof canvas.transferControlToOffscreen !== 'function') {
      setErrorMsg('OffscreenCanvas is required but not supported by this browser.');
      setBoot('no-assets');
      hideSplash();
      if (wrap) wrap.style.display = 'none';
      bootInFlight.current = false;
      return;
    }

    try {
      const handle = bootEngineWorker({
        canvas,
        // Synchronous hand-off the moment the Worker is constructed.
        // We MUST attach the lobby bridge here (not after the
        // bootEngineWorker call returns) because handle.worker is
        // populated asynchronously inside the lib's loadHowlerOnce
        // callback, and the engine reaches the multiplayer-start
        // path almost instantly. Without this hook the worker boots
        // before main has replayed mp-ready / mp-peer-connected and
        // Java sees no selfId.
        onWorkerReady: (worker) => {
          window.__attachWorker?.(worker);
          if (getLobbyState().lobbyCode) {
            attachEngineWorker(worker);
          }
        },
        onMenuReady: () => {
          // Multiplayer: if a queued start is waiting, fire it now —
          // the engine has built MenuUI and is ready to receive
          // mp-start-as-{host,joiner}. The engine then transitions
          // through WarsmashGdxMenuScreen → WarsmashGdxMapScreen on
          // its own, masked by the splash overlay.
          if (queuedMpStart.current && handle.worker) {
            handle.worker.postMessage(queuedMpStart.current);
            queuedMpStart.current = null;
          }
          // Splash stays up if a multiplayer start is pending — drops
          // once the engine reaches the actual map. Single-player
          // flow drops it here.
          if (!(window as any).__mpPendingStart) {
            hideSplash();
          }
        },
        onMapReached: () => {
          (window as any).__mpInMap = true;
          (window as any).__mpPendingStart = false;
          hideSplash();
        },
        onError: (m) => setErrorMsg(m),
        onWorkerMessage: (data) => {
          if (!data || typeof data !== 'object') return;
          const d = data as { kind?: string };
          if (d.kind === 'mp-desync-report') {
            setDesync({
              turn: (d as any).turn,
              lobbyCode: (window as any).pokiBridgeCurrentLobby?.() ?? undefined,
              selfPeerId: (window as any).pokiBridgeSelfId?.() ?? undefined,
            });
          }
          else if (d.kind === 'mp-desync-combined-report') {
            setDesync(prev => ({
              ...(prev ?? {}),
              turn: (d as any).turn,
              combinedReport: (d as any).combinedReport,
              lobbyCode: prev?.lobbyCode ?? (window as any).pokiBridgeCurrentLobby?.() ?? undefined,
              selfPeerId: prev?.selfPeerId ?? (window as any).pokiBridgeSelfId?.() ?? undefined,
            }));
          }
        },
      });
      // handle.worker is still undefined here — populated inside the
      // loadHowlerOnce callback in the lib. The onWorkerReady hook
      // above is the right attachment point; we just retain the
      // handle object for any later cleanup logic.
      engineHandle.current = handle;
    }
    catch (err) {
      console.error('[EnginePage] Engine boot failed:', err);
      setErrorMsg('Engine boot failed: ' + msg(err));
      setBoot('no-assets');
      hideSplash();
      if (wrap) wrap.style.display = 'none';
      bootInFlight.current = false;
    }
  }

  const showOverlay = boot !== 'running';

  return (
    <>
      {showOverlay && (
        <div class="boot-overlay">
          <a class="boot-overlay-back" href="../">← Back to home</a>
          <div class="boot-overlay-card">
            {boot === 'loading' && <p class="boot-status">Reading staged install…</p>}

            {(boot === 'no-assets' || boot === 'staging') && (
              <>
                <h1>{summary.fileCount > 0 ? 'Update install' : 'First-time setup'}</h1>
                <p>
                  The engine boots from a Warcraft III install staged in private browser
                  storage (OPFS). Pick your folder once — after that, the engine loads
                  directly on every visit.
                </p>
                <div class="row">
                  <label
                    class={`fakebtn ${summary.fileCount > 0 ? 'secondary' : 'primary'}`}
                    for="dir-input"
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
                    disabled={boot === 'staging'}
                    onChange={onDirectoryPick}
                  />
                  <a class="btn secondary" href="../assets/">Manage assets</a>
                </div>
                {boot === 'staging' && (
                  <div class="progress">
                    <div class="progress-bar" style={{ width: progressPct.toFixed(1) + '%' }} />
                  </div>
                )}
                {summary.fileCount > 0 && (
                  <div class="boot-summary">{formatSummary(summary)}</div>
                )}
                {statusMsg && <div class="boot-status">{statusMsg}</div>}
                {errorMsg  && <div class="boot-error">{errorMsg}</div>}
              </>
            )}
          </div>
        </div>
      )}

      <DesyncOverlay
        payload={desync}
        onReload={() => location.reload()}
      />
    </>
  );
}

/** One-time legacy cleanup: the presence of OPFS /extracted/ marks an
 *  install staged by the previous main-thread engine. The old /w3
 *  layout from that era doesn't always boot cleanly under the engine-
 *  worker (esp. on iOS Safari). Wipe and have the user re-pick.
 *  Remove this in a release or two once most users have migrated. */
async function purgeLegacyExtractedDir(): Promise<void> {
  try {
    const root = await navigator.storage.getDirectory();
    let hasLegacy = false;
    try { await root.getDirectoryHandle('extracted'); hasLegacy = true; }
    catch { /* not present */ }
    if (!hasLegacy) return;
    await clearOpfs();
    writeIndex([]);
  }
  catch (e) {
    console.warn('legacy cleanup failed:', e);
  }
}

function msg(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
