/**
 * Engine worker bootstrap — spawn the TeaVM-compiled engine in a Web
 * Worker, hand it an OffscreenCanvas, and forward input/audio between
 * the main thread and the worker.
 *
 * This is the single point where the page transitions from "DOM only"
 * to "engine running": before bootEngineWorker, the canvas is empty
 * and we're showing the asset uploader; after, the worker owns the
 * canvas and renders WC3.
 *
 * Multiplayer hooks: callers can pass an `onMenuReady` and `onMapReached`
 * callback. The page uses these to drop the splash overlay and to
 * notify the multiplayer-coordinator that it's safe to fire deferred
 * "start game" commands.
 */

import { handleAudioMessage, isAudioMessage, loadHowlerOnce } from './audioBridge';
import { acquireWakeLock } from './wakeLock';
import { versionedAsset } from './version';

export interface BootOptions {
  /** The page's <canvas> element. Must support transferControlToOffscreen. */
  canvas: HTMLCanvasElement;
  /** Called once the engine has emitted 'setScreen(WarsmashGdxMenuScreen)'. */
  onMenuReady?: () => void;
  /** Called once the engine has emitted 'setScreen(WarsmashGdxMapScreen)'. */
  onMapReached?: () => void;
  /** Fatal-error sink — typically wired to the asset overlay's error line. */
  onError?: (msg: string) => void;
  /** Receives every parsed engine→main message that ISN'T an audio
   *  event. Used by the multiplayer coordinator to pick up
   *  mp-rtc-send-to / mp-handler-ready / mp-desync-report etc. */
  onWorkerMessage?: (data: unknown) => void;
  /** Fires once the Worker has been instantiated — BEFORE we postMessage
   *  init, so the caller can attach external listeners (e.g. the lobby
   *  client's engine-worker bridge) and replay any pre-boot state to
   *  the worker. Critical for multiplayer: without this hook, the
   *  worker can boot and hit the multiplayer-start path before main
   *  has had a chance to forward mp-ready / mp-peer-connected events,
   *  and the engine sees an empty selfId. */
  onWorkerReady?: (worker: Worker) => void;
}

export interface EngineHandle {
  worker: Worker;
}

/** Spawn the engine worker and wire up input + audio + resize. */
export function bootEngineWorker(opts: BootOptions): EngineHandle {
  const { canvas, onMenuReady, onMapReached, onError, onWorkerMessage, onWorkerReady } = opts;

  if (typeof OffscreenCanvas === 'undefined' || typeof canvas.transferControlToOffscreen !== 'function') {
    onError?.('OffscreenCanvas is required but not supported by this browser.');
    throw new Error('OffscreenCanvas unsupported');
  }

  acquireWakeLock();

  // Pre-size at native CSS pixels (no DPR upscaling). HiDPI back-buffer
  // rendering needs the engine's font generator to scale by dpr too —
  // without it, buttons go 2× but text stays 15px and looks tiny.
  const cssW = canvas.clientWidth || window.innerWidth;
  const cssH = canvas.clientHeight || window.innerHeight;
  const offscreen = canvas.transferControlToOffscreen();
  offscreen.width = Math.max(1, Math.round(cssW));
  offscreen.height = Math.max(1, Math.round(cssH));

  const handle: EngineHandle = { worker: undefined as unknown as Worker };

  // Howler runs on the main thread. Worker postMessages audio.create
  // (with bytes) and audio.play (by id); main builds Howl instances
  // and drives playback. Loaded BEFORE worker spawn so the audio
  // message handler is ready when the worker starts emitting events.
  loadHowlerOnce(() => {
    const worker = new Worker(versionedAsset('engine-worker-boot.js'));
    handle.worker = worker;

    worker.addEventListener('message', (e) => {
      const m = e.data;
      if (isAudioMessage(m)) {
        handleAudioMessage(m);
        return;
      }
      // String log lines drive splash/menu/map state machines.
      if (typeof m === 'string') {
        // First frame the user actually wants to see: the engine has
        // installed WarsmashGdxMenuScreen. Drop the splash so the
        // chains-drop intro is visible from frame 0.
        //
        // EXCEPT in multiplayer: when the host has fired Start, we
        // keep the splash up THROUGH the menu transition so users
        // never see the WC3 main menu flash. Splash drops once the
        // engine reaches WarsmashGdxMapScreen (the actual game).
        if (m.indexOf('setScreen(WarsmashGdxMapScreen)') !== -1) {
          onMapReached?.();
        }
        else if (m.indexOf('setScreen(WarsmashGdxMenuScreen)') !== -1) {
          onMenuReady?.();
        }
        // Don't log the mass of engine console output to the page console;
        // the TeaVM build already mirrors System.out → console.log itself.
      }
      // Defer any structured message we don't recognise to the caller —
      // multiplayer coordinator picks up mp-rtc-send-to, mp-desync-*, etc.
      onWorkerMessage?.(m);
    });
    worker.addEventListener('error', (e) => {
      console.error('[engine error]', e.message || '(no message)', e);
      onError?.('Engine worker error: ' + (e.message || '(no message)'));
    });

    // Hand the worker to the caller BEFORE postMessage(init) so they
    // can install their own listeners + replay any prior state. The
    // multiplayer path depends on this — its lobby state has to land
    // in the worker before the engine processes mp-start-as-host /
    // mp-start-as-joiner.
    onWorkerReady?.(worker);

    worker.postMessage({
      kind: 'init',
      canvas: offscreen,
      cssWidth: cssW,
      cssHeight: cssH,
      dpr: 1,
    }, [offscreen]);

    wireInput(canvas, worker);
    wireResize(canvas, worker);
  });

  return handle;
}

function wireInput(canvas: HTMLCanvasElement, worker: Worker): void {
  function relayPointer(name: 'down' | 'up' | 'move', e: PointerEvent): void {
    const rect = canvas.getBoundingClientRect();
    // Translate browser pointer button → libGDX Input.Buttons.
    // Browser:  0=LEFT, 1=MIDDLE, 2=RIGHT
    // libGDX:   0=LEFT, 1=RIGHT, 2=MIDDLE
    let btn: number;
    switch (e.button) {
      case 0:  btn = 0; break;  // LEFT
      case 1:  btn = 2; break;  // MIDDLE
      case 2:  btn = 1; break;  // RIGHT
      default: btn = e.button;
    }
    worker.postMessage({
      kind: 'pointer', name,
      x: Math.round(e.clientX - rect.left),
      y: Math.round(e.clientY - rect.top),
      button: btn,
    });
  }
  canvas.addEventListener('pointerdown', (e) => { canvas.setPointerCapture(e.pointerId); relayPointer('down', e); });
  canvas.addEventListener('pointerup',     (e) => relayPointer('up', e));
  canvas.addEventListener('pointermove',   (e) => relayPointer('move', e));
  canvas.addEventListener('pointercancel', (e) => relayPointer('up', e));
  canvas.addEventListener('wheel', (e) => {
    worker.postMessage({ kind: 'scroll', dx: e.deltaX, dy: e.deltaY });
    e.preventDefault();
  }, { passive: false });
  window.addEventListener('keydown', (e) => {
    worker.postMessage({ kind: 'key', name: 'down', keycode: domKeyToGdx(e), ch: e.key.length === 1 ? e.key : '' });
  });
  window.addEventListener('keyup', (e) => {
    worker.postMessage({ kind: 'key', name: 'up', keycode: domKeyToGdx(e), ch: '' });
  });
}

function wireResize(canvas: HTMLCanvasElement, worker: Worker): void {
  // Debounced window-resize → propagate to worker. The CSS rules
  // already reflow the canvas DOM box (4:3 cap on 100vw/100vh);
  // the worker needs to resize the OffscreenCanvas back buffer +
  // tell libGDX so the engine re-projects. Debounce because resize
  // events fire continuously while dragging — we only care about
  // the final size.
  let resizeTimer: ReturnType<typeof setTimeout> | null = null;
  function postResize(): void {
    const cssW = canvas.clientWidth;
    const cssH = canvas.clientHeight;
    if (cssW <= 0 || cssH <= 0) return;
    worker.postMessage({
      kind: 'resize',
      cssWidth: cssW,
      cssHeight: cssH,
      pixelWidth: cssW,
      pixelHeight: cssH,
    });
  }
  window.addEventListener('resize', () => {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(postResize, 150);
  });
}

/** Map a DOM KeyboardEvent to libGDX Input.Keys constants. Just enough
 *  for menu/gameplay basics — extend as needed. */
function domKeyToGdx(e: KeyboardEvent): number {
  if (e.code && e.code.startsWith('Key'))   return 29 + (e.code.charCodeAt(3) - 65); // A=29
  if (e.code && e.code.startsWith('Digit')) return 7 + (e.code.charCodeAt(5) - 48);  // 0=7
  switch (e.code) {
    case 'Space':       return 62;
    case 'Enter':       return 66;
    case 'Escape':      return 131;
    case 'ArrowLeft':   return 21;
    case 'ArrowRight':  return 22;
    case 'ArrowUp':     return 19;
    case 'ArrowDown':   return 20;
    case 'ShiftLeft':
    case 'ShiftRight':  return 59;
    case 'ControlLeft':
    case 'ControlRight':return 129;
    case 'AltLeft':
    case 'AltRight':    return 57;
    case 'Tab':         return 61;
    case 'Backspace':   return 67;
    default: return -1;
  }
}
