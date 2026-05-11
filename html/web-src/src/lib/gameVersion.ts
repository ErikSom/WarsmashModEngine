/**
 * Game-version detection for matchmaking. Two-stage:
 *
 *   1. {@link detectGameVersion} — synchronous, classifies the install
 *      into RoC / TFT / Reforged / unknown from index basenames alone.
 *      Free to call on every render, no IO.
 *
 *   2. {@link resolveExactBuild} — async. For Classic, opens
 *      war3patch.mpq via MpqArchive, extracts game.dll, scans its
 *      VS_FIXEDFILEINFO resource for the exact "1.27.1.7085"-style
 *      build the WC3 menu draws. For Reforged, reads `.build.info`
 *      and parses the `Version` column. Returns null if no patch
 *      manifest/MPQ is staged (e.g. an ancient unpatched install or
 *      partial directory pick).
 *
 * We split sync vs async so the UI can show *something* immediately
 * and refine it once the file IO + binary parsing completes. Build
 * matching is meant to gate compatible peers in the lobby — we'd
 * rather show "TFT (build pending)" than block the lobby UI on disk
 * IO and a few MB of MPQ decompression.
 */
import MpqArchive from 'mdx-m3-viewer/dist/cjs/parsers/mpq/archive';
import type { IndexEntry } from './assetStaging';
import { getW3Root } from './opfs';

/** localStorage key for the resolved-version cache. */
const CACHE_KEY = 'w3GameVersionCache';

/** Bump when the cache shape or detection algorithm changes meaningfully
 *  — old entries silently dropped on read. */
const CACHE_VERSION = 1;

export type GameEdition = 'roc' | 'tft' | 'reforged' | 'unknown';

export interface GameVersionInfo {
  edition: GameEdition;
  /** Short human-readable edition label, e.g. "TFT (Classic 1.27–1.31)". */
  label: string;
  /** Basenames we matched on. Surfaced in the UI tooltip so a bug report
   *  shows the fingerprints we keyed off. */
  evidence: string[];
}

export interface ExactBuild {
  /** Dotted "major.minor.build.revision" — what the WC3 menu displays. */
  version: string;
  major: number;
  minor: number;
  build: number;
  revision: number;
  /** Where we read it from — useful when diagnosing why the answer
   *  came out the way it did, especially across edition variants. */
  source: 'build.info' | 'war3patch.mpq:game.dll' | 'game.dll'
        | 'Warcraft III.exe' | 'Frozen Throne.exe' | 'War3.exe';
}

/**
 * On-disk shape stored in localStorage. The fingerprint binds the
 * cached value to the index it was computed from — if any of the
 * relevant install files change size or path, the fingerprint shifts
 * and we treat the cache as stale rather than risking a wrong build
 * after the user re-stages a patched install on top.
 */
interface CachedGameVersion {
  cacheVersion: number;
  indexFingerprint: string;
  version: GameVersionInfo;
  build: ExactBuild | null;
}

/**
 * Cheap deterministic fingerprint over the index — sensitive to any
 * file path or size change that could plausibly affect version
 * detection. Uses a 32-bit FNV-1a over the sorted entry list so the
 * result is short, stable across reloads, and order-independent. We
 * deliberately include EVERY index entry rather than only the version-
 * relevant files, because the cheapest way to be sure we didn't miss
 * a relevant change is to invalidate on any change at all.
 */
function fingerprintIndex(index: IndexEntry[]): string {
  let h = 0x811c9dc5; // FNV-1a 32-bit offset basis
  const entries = index.map(e => `${e.p}:${e.s}`).sort();
  for (const e of entries) {
    for (let i = 0; i < e.length; i++) {
      h ^= e.charCodeAt(i);
      h = Math.imul(h, 0x01000193);
    }
  }
  // Encode length too so an empty index doesn't collide with a single
  // empty-string entry, etc.
  return entries.length.toString(16) + ':' + (h >>> 0).toString(16);
}

/** Read the cached resolution, returning null on any of:
 *   - no entry at all
 *   - schema-version mismatch (silent drop)
 *   - JSON parse failure (corruption)
 *   - fingerprint mismatch against the supplied index (stale) */
export function readCachedGameVersion(
  index: IndexEntry[],
): { version: GameVersionInfo; build: ExactBuild | null } | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as CachedGameVersion;
    if (parsed?.cacheVersion !== CACHE_VERSION) return null;
    if (parsed.indexFingerprint !== fingerprintIndex(index)) return null;
    return { version: parsed.version, build: parsed.build };
  }
  catch {
    return null;
  }
}

export function writeCachedGameVersion(
  index: IndexEntry[],
  version: GameVersionInfo,
  build: ExactBuild | null,
): void {
  try {
    const payload: CachedGameVersion = {
      cacheVersion: CACHE_VERSION,
      indexFingerprint: fingerprintIndex(index),
      version,
      build,
    };
    localStorage.setItem(CACHE_KEY, JSON.stringify(payload));
  }
  catch {
    // Quota exceeded / private mode — non-fatal, we'll just re-detect
    // next time. Don't log; it's not actionable for users.
  }
}

export function clearCachedGameVersion(): void {
  try { localStorage.removeItem(CACHE_KEY); }
  catch { /* no-op */ }
}

/**
 * Strict compat check used by both the LobbyBrowser pre-flight and
 * the lobbyClient post-join guard. Returns null when join is allowed
 * (exact build match), or a short reason string when it must be
 * blocked. The reason is suitable for surfacing as a UI error.
 *
 * Mirror of {@link describeVersionCompat} in LobbyBrowser but centralised
 * here so the in-flight check and the post-join leave-and-reject path
 * never diverge — if WC3 wouldn't connect, neither path should.
 */
export function checkVersionCompat(
  hostVersion: { edition?: string; build?: string | null } | null | undefined,
  myBuild: string | null,
): string | null {
  const hostBuild = hostVersion?.build ?? null;
  if (!hostBuild) {
    return 'The host did not publish an exact game build — can\'t verify compatibility.';
  }
  if (!myBuild) {
    return 'Your install\'s exact build is unresolved — stage your full Warcraft III folder on the Assets page.';
  }
  if (hostBuild !== myBuild) {
    return `Host is on build ${hostBuild}; you are on ${myBuild}. Warcraft III refuses cross-build connections.`;
  }
  return null;
}

const CLASSIC_BASE_MPQ      = 'war3.mpq';
const CLASSIC_PATCH_MPQ     = 'war3patch.mpq';
const CLASSIC_TFT_MPQ       = 'war3x.mpq';
const CLASSIC_TFT_LOCAL_MPQ = 'war3xlocal.mpq';
const REFORGED_BUILD_INFO   = '.build.info';

/** Reforged ships its data under `Data/data/data.NNN` + a `.idx` table. */
const REFORGED_DATA_DIR_RE = /(^|\/)data\/data\/(data\.\d{3}|[a-f0-9]{2}\.idx)$/i;

/**
 * Synchronous coarse classification — RoC / TFT / Reforged. Cheap.
 */
export function detectGameVersion(index: IndexEntry[]): GameVersionInfo {
  const basenames = new Set<string>();
  let hasReforgedDataLayout = false;

  for (const entry of index) {
    const p = String(entry.p || '').toLowerCase();
    if (!p) continue;
    const slash = p.lastIndexOf('/');
    basenames.add(slash >= 0 ? p.slice(slash + 1) : p);
    if (REFORGED_DATA_DIR_RE.test(p)) {
      hasReforgedDataLayout = true;
    }
  }

  // Reforged signals are checked first — a Reforged install often still
  // carries some legacy-named files but the CASC layout is decisive.
  if (basenames.has(REFORGED_BUILD_INFO) || hasReforgedDataLayout) {
    const evidence: string[] = [];
    if (basenames.has(REFORGED_BUILD_INFO)) evidence.push(REFORGED_BUILD_INFO);
    if (hasReforgedDataLayout) evidence.push('Data/data/*');
    return { edition: 'reforged', label: 'Reforged (1.32+)', evidence };
  }

  const hasBase  = basenames.has(CLASSIC_BASE_MPQ);
  const hasPatch = basenames.has(CLASSIC_PATCH_MPQ);
  const hasTft   = basenames.has(CLASSIC_TFT_MPQ);
  const hasLocal = basenames.has(CLASSIC_TFT_LOCAL_MPQ);

  if (hasTft || hasLocal) {
    const evidence: string[] = [];
    if (hasBase)  evidence.push(CLASSIC_BASE_MPQ);
    if (hasPatch) evidence.push(CLASSIC_PATCH_MPQ);
    if (hasTft)   evidence.push(CLASSIC_TFT_MPQ);
    if (hasLocal) evidence.push(CLASSIC_TFT_LOCAL_MPQ);
    return { edition: 'tft', label: 'TFT (Classic 1.27–1.31)', evidence };
  }

  if (hasBase) {
    const evidence: string[] = [CLASSIC_BASE_MPQ];
    if (hasPatch) evidence.push(CLASSIC_PATCH_MPQ);
    return { edition: 'roc', label: 'Reign of Chaos (base only)', evidence };
  }

  return { edition: 'unknown', label: 'Unknown install', evidence: [] };
}

/**
 * Resolve the exact build number for matchmaking. Returns null when
 * nothing parseable is staged (no patch MPQ, no build.info, no loose
 * game.dll). Caller falls back to {@link detectGameVersion} for the
 * coarse edition in that case.
 *
 * Per-edition strategy:
 *   - Reforged: read `.build.info` as text. Last `|`-separated column
 *     is `Version` (we don't trust column ORDER across patches, so we
 *     parse the header row and look up by name).
 *   - Classic: find war3patch.mpq in the index, load it via
 *     MpqArchive, extract game.dll, scan for VS_FIXEDFILEINFO. Falls
 *     back to a loose `game.dll` at the install root (some installers
 *     drop it there in addition to the MPQ).
 */
export async function resolveExactBuild(
  index: IndexEntry[],
  edition: GameEdition,
): Promise<ExactBuild | null> {
  if (edition === 'reforged') {
    return await resolveReforgedBuild(index);
  }
  if (edition === 'roc' || edition === 'tft') {
    return await resolveClassicBuild(index);
  }
  return null;
}

/**
 * Look up a staged path by case-insensitive basename match.
 * Returns the canonical path stored in the index (preserving case)
 * so OPFS lookup succeeds — OPFS itself is case-sensitive.
 */
function findByBasename(index: IndexEntry[], basename: string): string | null {
  const wanted = basename.toLowerCase();
  for (const entry of index) {
    const p = String(entry.p || '');
    const slash = p.lastIndexOf('/');
    const base = slash >= 0 ? p.slice(slash + 1) : p;
    if (base.toLowerCase() === wanted) return p;
  }
  return null;
}

/** Read a staged file from OPFS by its index-relative path. */
async function readStagedFile(relPath: string): Promise<Uint8Array> {
  const root = await getW3Root(false);
  const parts = relPath.split('/').filter(p => p.length > 0);
  if (parts.length === 0) throw new Error('empty path');
  const filename = parts.pop()!;
  let dir = root;
  for (const p of parts) {
    dir = await dir.getDirectoryHandle(p);
  }
  const handle = await dir.getFileHandle(filename);
  const file = await handle.getFile();
  return new Uint8Array(await file.arrayBuffer());
}

async function resolveReforgedBuild(index: IndexEntry[]): Promise<ExactBuild | null> {
  const path = findByBasename(index, REFORGED_BUILD_INFO);
  if (!path) return null;
  const bytes = await readStagedFile(path);
  // `.build.info` is small (a few KB) UTF-8 text. Format is
  // pipe-separated columns with a header row using `Name!TYPE:WIDTH`.
  // Example header:
  //   Branch!STRING:0|Active!DEC:1|...|Version!STRING:0
  // We parse by name rather than position because the column order has
  // shifted across Reforged updates.
  const text = new TextDecoder('utf-8').decode(bytes);
  const lines = text.split(/\r?\n/).filter(l => l.length > 0);
  if (lines.length < 2) return null;
  const headers = lines[0]!.split('|').map(h => h.split('!')[0]!.trim().toLowerCase());
  const versionCol = headers.indexOf('version');
  if (versionCol < 0) return null;
  // Prefer the row marked Active=1 if present; else the first row.
  const activeCol = headers.indexOf('active');
  let row: string[] | null = null;
  for (let i = 1; i < lines.length; i++) {
    const cols = lines[i]!.split('|');
    if (activeCol < 0 || cols[activeCol]?.trim() === '1') { row = cols; break; }
  }
  if (!row) row = lines[1]!.split('|');
  const version = (row[versionCol] || '').trim();
  return parseDottedVersion(version, 'build.info');
}

async function resolveClassicBuild(index: IndexEntry[]): Promise<ExactBuild | null> {
  // Try the most authoritative source first. The order matters because
  // some installs have ALL of these and they don't always agree on the
  // build (e.g. a stale Frozen Throne.exe shim alongside an up-to-date
  // Warcraft III.exe). Order of preference:
  //
  //   1. war3patch.mpq:game.dll  — Battle.net auto-patched install,
  //                                 patches layered over the base MPQ
  //   2. Warcraft III.exe        — self-patched install (patches merged
  //                                 into War3.mpq, no separate patch
  //                                 MPQ). This is the post-1.28 layout.
  //   3. game.dll (loose)        — some installers / manual rebuilds
  //   4. Frozen Throne.exe       — TFT launcher; often a thin shim, but
  //                                 usable as a last resort
  //   5. War3.exe                — ancient pre-TFT executable name
  //
  // The PE scanner is the same regardless of whether the bytes come
  // from a DLL or an EXE — both are PE files with VS_FIXEDFILEINFO.
  const attempts: Array<() => Promise<ExactBuild | null>> = [
    () => tryMpqGameDll(index, CLASSIC_PATCH_MPQ, 'war3patch.mpq:game.dll'),
    () => tryLooseFile(index, 'Warcraft III.exe', 'Warcraft III.exe'),
    () => tryLooseFile(index, 'game.dll', 'game.dll'),
    () => tryLooseFile(index, 'Frozen Throne.exe', 'Frozen Throne.exe'),
    () => tryLooseFile(index, 'War3.exe', 'War3.exe'),
  ];
  for (const attempt of attempts) {
    try {
      const result = await attempt();
      if (result) return result;
    }
    catch (err) {
      // eslint-disable-next-line no-console
      console.warn('[gameVersion] resolveClassicBuild attempt failed:', err);
    }
  }
  return null;
}

async function tryMpqGameDll(
  index: IndexEntry[],
  mpqBasename: string,
  source: ExactBuild['source'],
): Promise<ExactBuild | null> {
  const path = findByBasename(index, mpqBasename);
  if (!path) return null;
  const mpqBytes = await readStagedFile(path);
  const archive = new MpqArchive();
  archive.load(mpqBytes, true);
  const dllFile = archive.get('game.dll');
  if (!dllFile) return null;
  const dllBytes = new Uint8Array(dllFile.arrayBuffer());
  const found = findFixedFileInfo(dllBytes);
  return found ? { ...found, source } : null;
}

async function tryLooseFile(
  index: IndexEntry[],
  basename: string,
  source: ExactBuild['source'],
): Promise<ExactBuild | null> {
  const path = findByBasename(index, basename);
  if (!path) return null;
  const bytes = await readStagedFile(path);
  const found = findFixedFileInfo(bytes);
  return found ? { ...found, source } : null;
}

function parseDottedVersion(version: string, source: ExactBuild['source']): ExactBuild | null {
  const parts = version.split('.').map(n => parseInt(n, 10));
  if (parts.length < 2 || parts.some(n => !Number.isFinite(n))) return null;
  const [major, minor, build = 0, revision = 0] = parts;
  return {
    version: `${major}.${minor}.${build}.${revision}`,
    major: major!,
    minor: minor!,
    build: build!,
    revision: revision!,
    source,
  };
}

/**
 * Scan a PE binary for the VS_FIXEDFILEINFO record. We skip the formal
 * PE→resource-directory walk and key on the structure's signature
 * (0xFEEF04BD) instead — much shorter code, and the signature is
 * unique enough that 32-bit collisions on a multi-MB DLL are
 * vanishingly rare. We also sanity-check the parsed version fields
 * (major < 100, minor < 1000) so a coincidental signature match in a
 * data section can't masquerade as a real version.
 *
 * Reference layout (Microsoft `VS_FIXEDFILEINFO`):
 *   DWORD dwSignature       = 0xFEEF04BD
 *   DWORD dwStrucVersion
 *   DWORD dwFileVersionMS   = (major   << 16) | minor
 *   DWORD dwFileVersionLS   = (build   << 16) | revision
 *   DWORD dwProductVersionMS
 *   DWORD dwProductVersionLS
 *   ...
 */
function findFixedFileInfo(bytes: Uint8Array): Omit<ExactBuild, 'source'> | null {
  if (bytes.byteLength < 32) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const SIG = 0xFEEF04BD;
  // VS_FIXEDFILEINFO is always 4-byte aligned within the .rsrc section.
  // 4-byte stride is plenty fast (a few hundred MB/s in V8) and removes
  // false positives from mid-DWORD bytes that happen to spell the sig.
  const end = bytes.byteLength - 24;
  for (let off = 0; off <= end; off += 4) {
    if (view.getUint32(off, true) !== SIG) continue;
    const ms = view.getUint32(off + 8, true);
    const ls = view.getUint32(off + 12, true);
    const major = (ms >>> 16) & 0xFFFF;
    const minor = ms & 0xFFFF;
    const build = (ls >>> 16) & 0xFFFF;
    const revision = ls & 0xFFFF;
    if (major < 100 && minor < 1000) {
      return {
        version: `${major}.${minor}.${build}.${revision}`,
        major, minor, build, revision,
      };
    }
  }
  return null;
}

/**
 * Single combined formatter for chips/logs. Renders the edition label
 * plus the exact build when known: "TFT 1.27.1.7085", falling back to
 * just the edition.
 */
export function formatGameVersion(info: GameVersionInfo, build: ExactBuild | null): string {
  if (build) {
    return `${editionShortLabel(info.edition)} ${build.version}`;
  }
  return info.label;
}

function editionShortLabel(edition: GameEdition): string {
  switch (edition) {
    case 'roc': return 'RoC';
    case 'tft': return 'TFT';
    case 'reforged': return 'Reforged';
    case 'unknown': return 'Unknown';
  }
}
