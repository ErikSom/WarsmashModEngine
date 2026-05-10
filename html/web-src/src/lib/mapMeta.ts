/**
 * Lightweight map metadata helpers — extracts what we can from the
 * filename alone. Real WC3 map metadata lives inside an MPQ-archived
 * `war3map.w3i` and parsing it from JS would mean either:
 *   1. Booting the engine worker before showing the lobby (heavy);
 *   2. Pulling in a JS MPQ parser (~20kb dep + manual w3i schema);
 *
 * For now we use the WC3 community convention `(N)MapName.w3x` to
 * infer player count. It catches every stock Blizzard map and most
 * uploaded ladder maps. Custom maps that don't follow the convention
 * fall back to an 8-player default — the host can then pick a different
 * map if they want a different cap.
 *
 * Future: when the engine exposes a "parse map metadata only" entry
 * point, we can replace the heuristic with real values (player names,
 * starting positions, recommended races).
 */

/** Engine cap for the standard WC3 / TFT player count. */
export const DEFAULT_MAX_PLAYERS = 8;

/** Engine ceiling for any map (we'd reject more than this). */
export const HARD_CAP_PLAYERS = 12;

/** Extract player count from a filename like `(2)EchoIsles.w3x`.
 *  Returns DEFAULT_MAX_PLAYERS when no leading `(N)` is present. */
export function extractMapPlayerCount(pathOrName: string): number {
  const base = pathOrName.split('/').pop() ?? pathOrName;
  const m = base.match(/^\((\d{1,2})\)/);
  if (!m) return DEFAULT_MAX_PLAYERS;
  const n = parseInt(m[1], 10);
  if (Number.isNaN(n) || n < 2 || n > HARD_CAP_PLAYERS) return DEFAULT_MAX_PLAYERS;
  return n;
}

/** Drop the `Maps/` prefix and `.w3x`/`.w3m` suffix for a friendly
 *  display label. Caller is responsible for HTML-escaping if needed. */
export function shortMapLabel(path: string): string {
  return path
    .replace(/^Maps\//, '')
    .replace(/\.(w3x|w3m)$/i, '');
}

/** Human-friendly map name from a path: strips both directory and
 *  the leading `(N)` so we get `EchoIsles` from `(2)EchoIsles.w3x`. */
export function bareMapName(path: string): string {
  const base = (path.split('/').pop() ?? path).replace(/\.(w3x|w3m)$/i, '');
  return base.replace(/^\(\d{1,2}\)/, '');
}

/** Engine path for a map listed under /w3/Maps/. listAllMaps()'s
 *  `relPath` is relative to /w3/Maps/; the engine wants it relative
 *  to /w3/, so we prepend `Maps/`. Idempotent if the caller already
 *  has the full path. */
export function fullMapPath(relPathFromMapsDir: string): string {
  if (relPathFromMapsDir.startsWith('Maps/')) return relPathFromMapsDir;
  return 'Maps/' + relPathFromMapsDir;
}
