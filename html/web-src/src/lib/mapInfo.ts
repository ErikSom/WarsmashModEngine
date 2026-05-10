/**
 * Map metadata extraction. Opens a Warcraft III .w3x/.w3m map file
 * (which is an MPQ archive), pulls out war3map.w3i, parses the binary
 * struct, and returns a typed MapInfo with everything the lobby UI
 * needs to drive slot/race/team configuration.
 *
 * Source-of-truth chain:
 *   Filesystem → MPQ archive → war3map.w3i bytes → W3i struct → MapInfo
 *
 * Uses mdx-m3-viewer's parsers (deep-imported to avoid pulling in the
 * WebGL viewer, fengari, gl-matrix, etc. — only the MPQ + w3i code +
 * pako get bundled).
 */

import MpqArchive from 'mdx-m3-viewer/dist/cjs/parsers/mpq/archive';
import War3MapW3i from 'mdx-m3-viewer/dist/cjs/parsers/w3x/w3i/file';
import War3MapWts from 'mdx-m3-viewer/dist/cjs/parsers/w3x/wts/file';
import TgaImage from 'mdx-m3-viewer/dist/cjs/parsers/tga/image';
import { BlpImage } from 'mdx-m3-viewer/dist/cjs/parsers/blp/image';
import { getDirByParts } from './opfs';

// w3i's `player.type` integer values. From the W3 map format spec.
export enum PlayerType {
  Human       = 1,
  Computer    = 2,
  Neutral     = 3,
  Rescuable   = 4,
}

// w3i's `player.race` integer values. 0 = the map allows the player to
// pick at lobby time; positive values = the slot is locked to that
// race (the host/joiner can't change it).
export enum PlayerRace {
  Selectable  = 0,  // map says "let the lobby pick"
  Human       = 1,
  Orc         = 2,
  Undead      = 3,
  NightElf    = 4,
  Demon       = 5,  // rarely used by stock maps
}

// w3i's `force.flags` bitfield. The map declares these per force; the
// lobby UI may render them but typically leaves them locked.
export const FORCE_FLAG_ALLIED          = 0x01;
export const FORCE_FLAG_ALLIED_VICTORY  = 0x02;
export const FORCE_FLAG_SHARED_VISION   = 0x04;
export const FORCE_FLAG_SHARED_CONTROL  = 0x10;
export const FORCE_FLAG_SHARED_ADV_CTRL = 0x20;

// w3i's top-level `flags` bitfield — selected bits we surface to the
// lobby UI as derived booleans. See WC3 community map-format docs
// for the full table.
export const W3I_FLAG_FIXED_PLAYER_SETTINGS = 0x0020;
export const W3I_FLAG_USE_CUSTOM_FORCES     = 0x0040;

/** A single player slot as declared by the map. The lobby UI uses
 *  these as defaults / constraints when wiring slots to peers. */
export interface MapPlayerSlot {
  /** Slot index as the map sees it (engine slot id). 0-based. */
  id: number;
  /** Display name from the map (e.g. "Player 1"). */
  name: string;
  /** Slot type — Human means a player should be here; Computer
   *  means an AI; Neutral/Rescuable are non-player slots. */
  type: PlayerType;
  /** Race the map locks this slot to, or `Selectable` (0) if the
   *  lobby may pick. */
  race: PlayerRace;
  /** True if the slot's start location is fixed by the map. When
   *  false, the lobby can shuffle starting positions. */
  fixedStartPosition: boolean;
  /** Map-space starting coordinates. */
  startLocation: { x: number; y: number };
}

/** A force / team as declared by the map. Player ids in this team
 *  are encoded as a bitmask in `playerMask` — bit i set means the
 *  slot with id `i` belongs to this force. */
export interface MapForce {
  name: string;
  flags: number;
  /** Bit i = slot id i is on this force. */
  playerMask: number;
  /** Convenience: flag predicates. */
  allied: boolean;
  alliedVictory: boolean;
  sharedVision: boolean;
  sharedControl: boolean;
  sharedAdvancedControl: boolean;
}

/** Parsed metadata from war3map.w3i. */
export interface MapInfo {
  /** Map name as declared in w3i (the editor "Name" field). */
  name: string;
  author: string;
  description: string;
  /** "1v1", "FFA", etc. — free-form string set by the map author. */
  recommendedPlayers: string;
  /** Tileset code: "L"=Lordaeron Summer, "X"=Outland, etc. Single char. */
  tileset: string;
  /** [width, height] in playable map units. */
  playableSize: [number, number];
  /** All player slots declared by the map, in slot-id order. Slots
   *  with `type === Neutral | Rescuable` aren't user-pickable but
   *  appear here for completeness. */
  players: MapPlayerSlot[];
  /** Just the slots a lobby player (human or AI) can occupy. */
  humanLikeSlots: MapPlayerSlot[];
  /** Forces / teams. May be empty for FFA-style maps. */
  forces: MapForce[];
  /** Build version of the WC3 editor that saved the map (e.g. 131). */
  buildVersion: number;
  /** Raw w3i flags bitfield. Most callers want the derived booleans
   *  below; this is here for completeness / future expansion. */
  flags: number;
  /** True when the map's "fixed player settings" flag is set. The
   *  lobby UI treats this as "the slot's race + team are locked to
   *  the map's declared values" — race + team pickers are disabled. */
  fixedPlayerSettings: boolean;
  /** True when the map declares custom forces (vs. an FFA / melee
   *  map with no team groupings). Drives whether the team picker
   *  appears at all. */
  useCustomForces: boolean;
  /** Data-URL preview image rendered from war3mapPreview.tga,
   *  war3mapPreview.blp, or (fallback) the in-game minimap
   *  war3mapMap.blp. Null when none of the three are present in
   *  the MPQ or all decoders failed. */
  previewUrl: string | null;
}

/** Raw error type for parser failures so callers can pattern-match. */
export class MapInfoError extends Error {
  constructor(public readonly stage: 'fetch' | 'mpq' | 'w3i', message: string, public readonly cause?: unknown) {
    super(`[${stage}] ${message}`);
    this.name = 'MapInfoError';
  }
}

/** Parse a map's metadata from raw .w3x/.w3m bytes. */
export function parseMapInfoFromBuffer(buffer: ArrayBuffer | Uint8Array): MapInfo {
  let archive: MpqArchive;
  try {
    archive = new MpqArchive();
    archive.load(buffer, /* readonly */ true);
  }
  catch (e) {
    throw new MapInfoError('mpq', 'failed to open MPQ archive', e);
  }

  const w3iFile = archive.get('war3map.w3i');
  if (!w3iFile) {
    throw new MapInfoError('mpq', 'war3map.w3i not found in archive');
  }
  let w3iBytes: Uint8Array;
  try {
    const bytes = w3iFile.bytes();
    if (!bytes) throw new Error('w3i bytes() returned null');
    w3iBytes = bytes;
  }
  catch (e) {
    throw new MapInfoError('mpq', 'failed to extract war3map.w3i', e);
  }

  let w3i: War3MapW3i;
  try {
    w3i = new War3MapW3i();
    w3i.load(w3iBytes);
  }
  catch (e) {
    throw new MapInfoError('w3i', 'failed to parse war3map.w3i', e);
  }

  // war3map.wts holds the actual user-facing text — w3i and other
  // map files store translatable strings as `TRIGSTR_NNN` tokens
  // that get resolved against this table. Best-effort: a few older
  // maps don't ship a wts file at all (their strings are inlined
  // directly), and we still want to return what we have.
  const wts = tryLoadWts(archive);
  const previewUrl = tryLoadPreview(archive);
  return mapInfoFromW3i(w3i, wts, previewUrl);
}

/** Pull a map preview / minimap image out of the MPQ. Tries:
 *   1. war3mapPreview.tga — typical for melee maps
 *   2. war3mapPreview.blp — alt format used by some custom maps
 *   3. war3mapMap.blp     — the in-game minimap, present in
 *                            essentially every map; great fallback
 *  Returns a PNG data URL, or null if none of the three could be
 *  decoded. Renders to a hidden canvas — main-thread only (we don't
 *  parse maps in workers anyway, so this is fine).
 *
 *  Logs per-file diagnostics under [mapInfo preview] so we can
 *  trace why a map ends up without a preview. The MPQ archive lists
 *  every file by name; mismatches usually mean a map ships files
 *  under non-canonical paths (e.g. nested directories) and we'd
 *  need to extend the lookup list. */
function tryLoadPreview(archive: MpqArchive): string | null {
  if (typeof document === 'undefined') return null;  // SSR safety
  type Decoder = (bytes: Uint8Array) => string | null;
  const candidates: Array<[string, Decoder]> = [
    ['war3mapPreview.tga', decodeTga],
    ['war3mapPreview.blp', decodeBlp],
    ['war3mapMap.blp',     decodeBlp],
  ];
  for (const [name, decoder] of candidates) {
    const bytes = readArchiveFile(archive, name);
    if (!bytes) {
      console.log('[mapInfo preview]', name, '— not in archive');
      continue;
    }
    const url = decoder(bytes);
    if (url) {
      console.log('[mapInfo preview]', name, '— decoded', `(${bytes.length}B → ${(url.length / 1024).toFixed(1)}KB data URL)`);
      return url;
    }
    console.warn('[mapInfo preview]', name, '— decode returned null');
  }
  // Last resort: dump the archive's file list so the user can see
  // what's actually in it (e.g. case-mismatched path or unusual
  // location). Once.
  try {
    const files = archive.getFileNames();
    const candidates = files.filter(n => /preview|map\.blp|map\.tga|minimap/i.test(n));
    if (candidates.length) {
      console.log('[mapInfo preview] no canonical preview found; archive files matching preview/map/minimap:', candidates);
    }
    else {
      console.log('[mapInfo preview] no canonical preview found; archive has', files.length, 'files');
    }
  } catch { /* ignore */ }
  return null;
}

function readArchiveFile(archive: MpqArchive, name: string): Uint8Array | null {
  const f = archive.get(name);
  if (!f) return null;
  try {
    const bytes = f.bytes();
    return bytes ?? null;
  }
  catch { return null; }
}

function decodeTga(bytes: Uint8Array): string | null {
  try {
    const img = new TgaImage();
    img.load(bytes);
    if (!img.data) return null;
    return imageDataToDataUrl(img.data);
  }
  catch (e) {
    console.warn('[mapInfo] TGA decode failed:', e);
    return null;
  }
}

function decodeBlp(bytes: Uint8Array): string | null {
  try {
    const img = new BlpImage();
    img.load(bytes);
    const data = img.getMipmap(0);
    if (!data) return null;
    return imageDataToDataUrl(data);
  }
  catch (e) {
    console.warn('[mapInfo] BLP decode failed:', e);
    return null;
  }
}

function imageDataToDataUrl(data: ImageData): string {
  const canvas = document.createElement('canvas');
  canvas.width = data.width;
  canvas.height = data.height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return '';
  ctx.putImageData(data, 0, 0);
  return canvas.toDataURL('image/png');
}

function tryLoadWts(archive: MpqArchive): War3MapWts | null {
  const wtsFile = archive.get('war3map.wts');
  if (!wtsFile) return null;
  let bytes: Uint8Array | null;
  try { bytes = wtsFile.bytes(); }
  catch { return null; }
  if (!bytes) return null;
  try {
    // wts is a text file; the parser wants a JS string. UTF-8 decode
    // with replacement characters on bad input — never throw out of
    // this resolver path.
    const text = new TextDecoder('utf-8', { fatal: false }).decode(bytes);
    const wts = new War3MapWts();
    wts.load(text);
    return wts;
  }
  catch (e) {
    console.warn('[mapInfo] war3map.wts present but failed to parse:', e);
    return null;
  }
}

/** Parse a map's metadata from an OPFS-relative path under /w3/Maps/.
 *  e.g. `FrozenThrone/(2)EchoIsles.w3x`. */
export async function loadMapInfoFromOpfsPath(relPathFromMapsDir: string): Promise<MapInfo> {
  const parts = relPathFromMapsDir.split('/').filter(Boolean);
  if (!parts.length) throw new MapInfoError('fetch', 'empty map path');
  const fileName = parts[parts.length - 1];
  const dirParts = ['Maps', ...parts.slice(0, -1)];
  let buffer: ArrayBuffer;
  try {
    const dir = await getDirByParts(dirParts, false);
    const fileHandle = await dir.getFileHandle(fileName);
    const file = await fileHandle.getFile();
    buffer = await file.arrayBuffer();
  }
  catch (e) {
    throw new MapInfoError('fetch', `failed to read /w3/${dirParts.join('/')}/${fileName}`, e);
  }
  return parseMapInfoFromBuffer(buffer);
}

/** Parse a map's metadata from a File (e.g. an `<input type=file>` pick). */
export async function loadMapInfoFromFile(file: File): Promise<MapInfo> {
  let buffer: ArrayBuffer;
  try { buffer = await file.arrayBuffer(); }
  catch (e) { throw new MapInfoError('fetch', `failed to read ${file.name}`, e); }
  return parseMapInfoFromBuffer(buffer);
}

// ---------------------------------------------------------------------
// w3i → MapInfo translation. Pulls just the fields we expose; ignores
// camera bounds, fog, prologue text, random unit/item tables, and
// everything else the engine itself reads. Add fields here as the
// lobby UI grows.
// ---------------------------------------------------------------------

function mapInfoFromW3i(w3i: War3MapW3i, wts: War3MapWts | null, previewUrl: string | null): MapInfo {
  const resolve = (s: string): string => resolveTrigstr(s, wts);

  const players: MapPlayerSlot[] = w3i.players.map(p => ({
    id: p.id,
    name: resolve(p.name),
    type: p.type as PlayerType,
    race: p.race as PlayerRace,
    fixedStartPosition: p.isFixedStartPosition !== 0,
    startLocation: { x: p.startLocation[0] ?? 0, y: p.startLocation[1] ?? 0 },
  }));

  const forces: MapForce[] = w3i.forces.map(f => ({
    name: resolve(f.name),
    flags: f.flags,
    playerMask: f.playerMasks,
    allied:                (f.flags & FORCE_FLAG_ALLIED)          !== 0,
    alliedVictory:         (f.flags & FORCE_FLAG_ALLIED_VICTORY)  !== 0,
    sharedVision:          (f.flags & FORCE_FLAG_SHARED_VISION)   !== 0,
    sharedControl:         (f.flags & FORCE_FLAG_SHARED_CONTROL)  !== 0,
    sharedAdvancedControl: (f.flags & FORCE_FLAG_SHARED_ADV_CTRL) !== 0,
  }));

  const humanLikeSlots = players.filter(p =>
    p.type === PlayerType.Human || p.type === PlayerType.Computer
  );

  return {
    name: resolve(w3i.name),
    author: resolve(w3i.author),
    description: resolve(w3i.description),
    recommendedPlayers: resolve(w3i.recommendedPlayers),
    tileset: w3i.tileset,
    playableSize: [w3i.playableSize[0] ?? 0, w3i.playableSize[1] ?? 0],
    players,
    humanLikeSlots,
    forces,
    buildVersion: w3i.getBuildVersion(),
    flags: w3i.flags,
    fixedPlayerSettings: (w3i.flags & W3I_FLAG_FIXED_PLAYER_SETTINGS) !== 0,
    useCustomForces:     (w3i.flags & W3I_FLAG_USE_CUSTOM_FORCES)     !== 0,
    previewUrl,
  };
}

/** Replace `TRIGSTR_NNN` tokens with the actual text from war3map.wts.
 *  Falls through to the input string when:
 *   - the input isn't a TRIGSTR token,
 *   - there's no wts file (older maps),
 *   - the table doesn't have an entry for that index. */
function resolveTrigstr(s: string, wts: War3MapWts | null): string {
  if (!s) return s;
  if (!wts) return s;
  // wts.getString accepts both numeric ids and the literal "TRIGSTR_NNN"
  // form, but only the latter exactly matches what w3i stores.
  if (s.startsWith('TRIGSTR_')) {
    const resolved = wts.getString(s);
    return resolved ?? s;
  }
  return s;
}

// ---------------------------------------------------------------------
// Display helpers — mostly for the upcoming lobby UI changes.
// ---------------------------------------------------------------------

export function playerTypeLabel(t: PlayerType): string {
  switch (t) {
    case PlayerType.Human:     return 'Human';
    case PlayerType.Computer:  return 'Computer';
    case PlayerType.Neutral:   return 'Neutral';
    case PlayerType.Rescuable: return 'Rescuable';
    default:                   return `Unknown (${t})`;
  }
}

export function raceLabel(r: PlayerRace): string {
  switch (r) {
    case PlayerRace.Selectable: return 'Random';
    case PlayerRace.Human:      return 'Human';
    case PlayerRace.Orc:        return 'Orc';
    case PlayerRace.Undead:     return 'Undead';
    case PlayerRace.NightElf:   return 'Night Elf';
    case PlayerRace.Demon:      return 'Demon';
    default:                    return `Race ${r}`;
  }
}

/** True if a force includes the slot with this id. */
export function forceContainsSlot(force: MapForce, slotId: number): boolean {
  return (force.playerMask & (1 << slotId)) !== 0;
}
