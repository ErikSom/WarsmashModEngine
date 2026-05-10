/**
 * OPFS (Origin-Private File System) helpers for the staged Warcraft III
 * install + uploaded maps. The engine-worker reads from these locations
 * at boot:
 *   /w3/                      — staged WC3 install (MPQs, maps, etc.)
 *   /w3/Maps/                 — stock maps + uploaded user maps
 *   /w3/Maps/Upload/          — user-uploaded .w3x / .w3m files
 *   /extracted/               — legacy extraction output (purged on first
 *                               run; will be re-removed in a release or two)
 *
 * Two of these helpers (w3MainListExtractedAsync, w3MainReadExtractedAsync)
 * are also exposed on `window` because engine-worker-boot.js — a hand-
 * written non-TeaVM file — calls them by name. Importing this module
 * is enough to install the globals (idempotent — guarded by a flag).
 */

export const ROOT_DIR = 'w3';
export const UPLOAD_DIR_PARTS = ['Maps', 'Upload'];
export const CUSTOM_MAP_PREFIX = 'Maps/Upload/';

export interface MapEntry {
  name: string;
  dirParts: string[];
  /** path relative to /w3/Maps/, with forward slashes */
  relPath: string;
  isUpload: boolean;
}

export async function getW3Root(create = false): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle(ROOT_DIR, { create });
}

/** Walk parent → parent.<a> → parent.<a>.<b> → ..., creating each
 *  directory if missing. Returns the deepest handle. */
export async function ensureDir(parent: FileSystemDirectoryHandle, parts: string[]): Promise<FileSystemDirectoryHandle> {
  let dir = parent;
  for (const p of parts) dir = await dir.getDirectoryHandle(p, { create: true });
  return dir;
}

/** Wipe everything we own — /w3 (staged install) + /extracted (legacy)
 *  + the localStorage index. Best-effort: missing entries are ignored. */
export async function clearOpfs(): Promise<void> {
  const root = await navigator.storage.getDirectory();
  for (const name of [ROOT_DIR, 'extracted']) {
    try { await root.removeEntry(name, { recursive: true }); }
    catch { /* may not exist */ }
  }
}

async function getMapsHostRoot(create = false): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle('w3', { create });
}

/** Resolve /w3/<...parts> with optional creation. */
export async function getDirByParts(parts: string[], create = false): Promise<FileSystemDirectoryHandle> {
  let dir = await getMapsHostRoot(create);
  for (const p of parts) {
    dir = await dir.getDirectoryHandle(p, { create });
  }
  return dir;
}

/** Recursively list every .w3x/.w3m under /w3/Maps/. Returns [] if the
 *  directory doesn't exist (engine not yet staged). Sorted: uploaded
 *  maps first, then stock alphabetically. */
export async function listAllMaps(): Promise<MapEntry[]> {
  let mapsRoot: FileSystemDirectoryHandle;
  try {
    mapsRoot = await getDirByParts(['Maps'], false);
  }
  catch {
    return [];
  }
  const out: MapEntry[] = [];
  async function walk(dir: FileSystemDirectoryHandle, parts: string[]): Promise<void> {
    // @ts-expect-error — entries() is real on FileSystemDirectoryHandle but TS lib lags.
    for await (const [name, handle] of dir.entries()) {
      if (handle.kind === 'directory') {
        await walk(handle as FileSystemDirectoryHandle, parts.concat(name));
      }
      else {
        const lower = name.toLowerCase();
        if (lower.endsWith('.w3x') || lower.endsWith('.w3m')) {
          const isUpload = parts[0] === UPLOAD_DIR_PARTS[1];
          out.push({
            name,
            dirParts: parts,
            relPath: parts.concat(name).join('/'),
            isUpload,
          });
        }
      }
    }
  }
  await walk(mapsRoot, []);
  out.sort((a, b) => {
    if (a.isUpload !== b.isUpload) return a.isUpload ? -1 : 1;
    return a.relPath.localeCompare(b.relPath);
  });
  return out;
}

/** Save user-uploaded .w3x/.w3m files under /w3/Maps/Upload/. Returns
 *  the count of successfully written files (skips other extensions). */
export async function addUploadedMaps(files: Iterable<File>): Promise<number> {
  const dir = await getDirByParts(['Maps'].concat(UPLOAD_DIR_PARTS.slice(1)), true);
  let written = 0;
  for (const f of files) {
    const lower = f.name.toLowerCase();
    if (!lower.endsWith('.w3x') && !lower.endsWith('.w3m')) continue;
    const safe = sanitizeFileName(f.name);
    const handle = await dir.getFileHandle(safe, { create: true });
    const writable = await handle.createWritable();
    await writable.write(f);
    await writable.close();
    written++;
  }
  return written;
}

export async function removeMapByEntry(entry: MapEntry): Promise<void> {
  const dir = await getDirByParts(['Maps'].concat(entry.dirParts), false);
  await dir.removeEntry(entry.name);
}

export function sanitizeFileName(name: string | undefined | null): string {
  return String(name || '').replace(/[\\/]/g, '_');
}

/** Resolve where in OPFS a given uploaded File should land. For a
 *  directory-mode pick (webkitdirectory), we strip the topmost path
 *  component (typically "Warcraft III/") and stage the remainder
 *  verbatim. For loose .w3x/.w3m drops, prefix with Maps/Upload/. */
export function stagedPathForFile(file: File, mode: 'directory' | 'files'): string {
  if (mode === 'directory') {
    const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
    const parts = rel.split('/').filter(Boolean);
    const relParts = (parts.length > 1) ? parts.slice(1) : parts;
    return relParts.join('/');
  }
  const fileName = sanitizeFileName(file.name);
  const lower = fileName.toLowerCase();
  if (lower.endsWith('.w3x') || lower.endsWith('.w3m')) {
    return CUSTOM_MAP_PREFIX + fileName;
  }
  return fileName;
}

// ---------------------------------------------------------------------
// Globals consumed by engine-worker-boot.js (a hand-written, non-TeaVM
// file). Installed on import; idempotent.
// ---------------------------------------------------------------------

declare global {
  interface Window {
    w3MainListExtractedAsync?: () => Promise<string>;
    w3MainReadExtractedAsync?: (relPath: string) => Promise<Int8Array | null>;
  }
}

let installed = false;
export function installEngineWorkerGlobals(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;

  // Async: recursively list OPFS /extracted. Returns newline-joined
  // forward-slash paths, or "" if /extracted doesn't exist.
  window.w3MainListExtractedAsync = async () => {
    const storage = await navigator.storage.getDirectory();
    let root: FileSystemDirectoryHandle;
    try { root = await storage.getDirectoryHandle('extracted'); }
    catch { return ''; }
    const out: string[] = [];
    async function walk(dir: FileSystemDirectoryHandle, prefix: string): Promise<void> {
      // @ts-expect-error — entries() is real on FileSystemDirectoryHandle.
      for await (const [name, handle] of dir.entries()) {
        const p = prefix + name;
        if (handle.kind === 'directory') {
          await walk(handle as FileSystemDirectoryHandle, p + '/');
        }
        else {
          out.push(p);
        }
      }
    }
    await walk(root, '');
    return out.join('\n');
  };

  // Async: read a file under OPFS /extracted as Int8Array, null on miss.
  window.w3MainReadExtractedAsync = async (relPath: string) => {
    try {
      const storage = await navigator.storage.getDirectory();
      let dir = await storage.getDirectoryHandle('extracted');
      const parts = String(relPath).split('/').filter(Boolean);
      for (let i = 0; i < parts.length - 1; i++) {
        dir = await dir.getDirectoryHandle(parts[i]);
      }
      const fh = await dir.getFileHandle(parts[parts.length - 1]);
      const file = await fh.getFile();
      const ab = await file.arrayBuffer();
      return new Int8Array(ab);
    }
    catch {
      return null;
    }
  };
}
