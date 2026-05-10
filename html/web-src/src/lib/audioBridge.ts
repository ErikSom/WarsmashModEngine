/**
 * Howler audio bridge. The engine runs in a Web Worker and can't
 * directly drive audio output (no AudioContext from a worker without
 * cross-origin headaches), so it postMessages
 *   { kind: 'audio.create', id, name, bytes }
 *   { kind: 'audio.play',   id, vol?, pitch?, pan?, loop? }
 *   { kind: 'audio.stop' | 'audio.volume' | 'audio.loop', ... }
 * to the main thread. We build Howl instances and drive playback here.
 *
 * Howler is loaded lazily via a <script> tag (it's a UMD library and
 * doesn't tree-shake well into a module pipeline). Loading is deferred
 * until the first audio message arrives, so the menu page doesn't pay
 * for the script unless the engine actually starts producing sound.
 */

import { versionedAsset } from './version';

declare const Howl: unknown;

interface AudioCreateMsg { kind: 'audio.create'; id: number; name: string; bytes: ArrayBuffer; }
interface AudioPlayMsg   { kind: 'audio.play';   id: number; vol?: number; pitch?: number; pan?: number; loop?: boolean; }
interface AudioStopMsg   { kind: 'audio.stop';   id: number; }
interface AudioVolumeMsg { kind: 'audio.volume'; id: number; vol: number; }
interface AudioLoopMsg   { kind: 'audio.loop';   id: number; loop: boolean; }
type AudioMsg = AudioCreateMsg | AudioPlayMsg | AudioStopMsg | AudioVolumeMsg | AudioLoopMsg;

interface HowlInstance {
  play(): number;
  stop(): void;
  state(): string;
  volume(v?: number, id?: number): void;
  rate(r: number, id?: number): void;
  stereo(p: number, id?: number): void;
  loop(l: boolean, id?: number): void;
}

interface AudioEntry {
  howl: HowlInstance | null;
  pendingPlays: AudioPlayMsg[];
}

const sounds = new Map<number, AudioEntry>();
let howlerLoading: Promise<void> | null = null;

export function loadHowlerOnce(then: () => void): void {
  if (typeof (globalThis as any).Howl === 'function') { then(); return; }
  if (howlerLoading) { howlerLoading.then(then); return; }
  howlerLoading = new Promise<void>((resolve) => {
    const s = document.createElement('script');
    s.src = versionedAsset('scripts/howler.js');
    s.onload = () => resolve();
    s.onerror = () => { console.warn('howler.js failed to load — audio disabled'); resolve(); };
    document.body.appendChild(s);
  });
  howlerLoading.then(then);
}

export function handleAudioMessage(m: AudioMsg): void {
  if (typeof (globalThis as any).Howl !== 'function') return;
  switch (m.kind) {
    case 'audio.create': handleAudioCreate(m); break;
    case 'audio.play':   handleAudioPlay(m); break;
    case 'audio.stop':   handleAudioStop(m); break;
    case 'audio.volume': handleAudioVolume(m); break;
    case 'audio.loop':   handleAudioLoop(m); break;
  }
}

function handleAudioCreate(m: AudioCreateMsg): void {
  const blob = new Blob([m.bytes], { type: guessAudioMime(m.name) });
  const url = URL.createObjectURL(blob);
  const entry: AudioEntry = { howl: null, pendingPlays: [] };
  sounds.set(m.id, entry);
  entry.howl = new (globalThis as any).Howl({
    src: [url],
    format: [guessAudioFormat(m.name)],
    loop: false,
    onload: () => {
      for (const p of entry.pendingPlays) playOnHowl(entry, p);
      entry.pendingPlays = [];
    },
    onloaderror: (_: unknown, err: unknown) => console.warn('howl load error', m.name, err),
  });
}

function handleAudioPlay(m: AudioPlayMsg): void {
  const entry = sounds.get(m.id);
  if (!entry) return;
  if (entry.howl && entry.howl.state() === 'loaded') {
    playOnHowl(entry, m);
  }
  else {
    entry.pendingPlays.push(m);
  }
}

function playOnHowl(entry: AudioEntry, m: AudioPlayMsg): void {
  if (!entry.howl) return;
  const id = entry.howl.play();
  if (m.vol != null) entry.howl.volume(m.vol, id);
  if (m.pitch && m.pitch !== 1) entry.howl.rate(m.pitch, id);
  if (m.pan != null) entry.howl.stereo(m.pan, id);
  if (m.loop) entry.howl.loop(true, id);
}

function handleAudioStop(m: AudioStopMsg): void {
  const entry = sounds.get(m.id);
  if (entry && entry.howl) entry.howl.stop();
}

function handleAudioVolume(m: AudioVolumeMsg): void {
  const entry = sounds.get(m.id);
  if (entry && entry.howl) entry.howl.volume(m.vol);
}

function handleAudioLoop(m: AudioLoopMsg): void {
  const entry = sounds.get(m.id);
  if (entry && entry.howl) entry.howl.loop(!!m.loop);
}

function guessAudioMime(name: string): string {
  const lower = String(name || '').toLowerCase();
  if (lower.endsWith('.mp3')) return 'audio/mpeg';
  if (lower.endsWith('.wav')) return 'audio/wav';
  if (lower.endsWith('.ogg')) return 'audio/ogg';
  return 'application/octet-stream';
}

function guessAudioFormat(name: string): string {
  const lower = String(name || '').toLowerCase();
  if (lower.endsWith('.mp3')) return 'mp3';
  if (lower.endsWith('.wav')) return 'wav';
  if (lower.endsWith('.ogg')) return 'ogg';
  return 'mp3';
}

export function isAudioMessage(m: unknown): m is AudioMsg {
  return !!m && typeof m === 'object' && 'kind' in m
    && typeof (m as { kind: unknown }).kind === 'string'
    && (m as { kind: string }).kind.indexOf('audio.') === 0;
}
