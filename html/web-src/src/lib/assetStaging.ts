/**
 * Asset staging — write the user's uploaded WC3 install (or loose map
 * files) into OPFS, maintaining a localStorage-backed index. The engine
 * worker reads /w3/ directly; the index lets us show "X files staged,
 * Y MB" without re-walking OPFS on every page load.
 *
 * One staging session is atomic from the user's perspective: progress
 * goes to 100% or the function throws. Replacement vs. additive is
 * controlled by the `replaceExisting` option — a fresh folder pick
 * wipes /w3 first; a loose drop appends to whatever's there.
 */

import { clearOpfs, ensureDir, getW3Root, sanitizeFileName, stagedPathForFile } from './opfs';
import { acquireWakeLock } from './wakeLock';

export const READY_KEY = 'w3AssetsReady';
export const COUNT_KEY = 'w3AssetsCount';
export const INDEX_KEY = 'w3AssetsIndex';

export interface IndexEntry { p: string; s: number; }

export interface IndexSummary {
  fileCount: number;
  mpqCount: number;
  mapCount: number;
  totalBytes: number;
}

export function readIndex(): IndexEntry[] {
  try {
    return JSON.parse(localStorage.getItem(INDEX_KEY) || '[]');
  }
  catch {
    return [];
  }
}

export function writeIndex(index: IndexEntry[]): void {
  if (index.length) {
    localStorage.setItem(READY_KEY, '1');
    localStorage.setItem(COUNT_KEY, String(index.length));
    localStorage.setItem(INDEX_KEY, JSON.stringify(index));
  }
  else {
    localStorage.removeItem(READY_KEY);
    localStorage.removeItem(COUNT_KEY);
    localStorage.removeItem(INDEX_KEY);
  }
}

export function clearIndex(): void {
  localStorage.removeItem(READY_KEY);
  localStorage.removeItem(COUNT_KEY);
  localStorage.removeItem(INDEX_KEY);
}

export function upsertIndexEntry(index: IndexEntry[], path: string, size: number): void {
  for (let i = 0; i < index.length; i++) {
    if (index[i].p === path) {
      index[i].s = size;
      return;
    }
  }
  index.push({ p: path, s: size });
}

export function summarizeIndex(index: IndexEntry[]): IndexSummary {
  let totalBytes = 0;
  let mpqCount = 0;
  let mapCount = 0;
  for (const entry of index) {
    totalBytes += Number(entry.s || 0);
    const lower = String(entry.p || '').toLowerCase();
    if (lower.endsWith('.mpq')) mpqCount++;
    if (lower.endsWith('.w3x') || lower.endsWith('.w3m')) mapCount++;
  }
  return { fileCount: index.length, mpqCount, mapCount, totalBytes };
}

export function formatSummary(summary: IndexSummary): string {
  const totalMb = (summary.totalBytes / 1048576).toFixed(1);
  return `${summary.fileCount} staged files, ${summary.mpqCount} MPQs, ${summary.mapCount} maps, ${totalMb} MB total.`;
}

export function buildNextStep(summary: IndexSummary): string {
  if (summary.mapCount > 0) {
    return 'Press "Play game" to boot the engine.';
  }
  return 'No map files are staged yet. Re-select your Warcraft III folder so a stock map (e.g. Echo Isles) is included.';
}

export function hasStagedAssets(): boolean {
  return localStorage.getItem(READY_KEY) === '1';
}

export interface StageProgress {
  done: number;
  total: number;
  bytes: number;
  totalBytes: number;
  currentName: string;
}

export interface StageOptions {
  mode: 'directory' | 'files';
  /** When true (or when no prior session has made the index dirty),
   *  wipes /w3 + the localStorage index before writing. */
  replaceExisting?: boolean;
  onProgress?: (p: StageProgress) => void;
  /** Whether the calling page has already started a staging session.
   *  When true, the default replace=false (additive); when false, the
   *  first call wipes. */
  sessionDirty?: boolean;
}

export interface StageResult {
  index: IndexEntry[];
  summary: IndexSummary;
  staged: number;
  totalBytes: number;
  /** Wall-clock seconds spent in the OPFS write loop. */
  durationSeconds: number;
}

/** Stage a batch of File objects (typically from `<input type=file>`)
 *  into OPFS. Wipes /w3 first if this is a fresh upload session.
 *  Throws on any I/O error; the caller is responsible for surfacing
 *  it to the user. */
export async function stageFiles(files: Iterable<File>, options: StageOptions): Promise<StageResult> {
  acquireWakeLock();
  const replacing = options.replaceExisting || !options.sessionDirty;
  if (replacing) {
    await clearOpfs();
    clearIndex();
  }
  const w3 = await getW3Root(true);
  const index = replacing ? [] : readIndex();

  const list = Array.from(files);
  let bytes = 0;
  let totalBytes = 0;
  for (const f of list) totalBytes += f.size;
  let done = 0;
  const started = performance.now();

  for (const f of list) {
    const stagedPath = stagedPathForFile(f, options.mode);
    const relParts = stagedPath.split('/').filter(Boolean);
    const fileName = relParts[relParts.length - 1];
    const dir = await ensureDir(w3, relParts.slice(0, -1));
    const handle = await dir.getFileHandle(fileName, { create: true });
    const writable = await handle.createWritable();
    await writable.write(f);
    await writable.close();
    bytes += f.size;
    done++;
    upsertIndexEntry(index, relParts.join('/'), f.size);
    options.onProgress?.({ done, total: list.length, bytes, totalBytes, currentName: f.name });
  }
  const durationSeconds = (performance.now() - started) / 1000;
  writeIndex(index);
  return {
    index,
    summary: summarizeIndex(index),
    staged: list.length,
    totalBytes,
    durationSeconds,
  };
}

// Suppress 'unused' on sanitizeFileName in case we want it elsewhere.
export { sanitizeFileName };
