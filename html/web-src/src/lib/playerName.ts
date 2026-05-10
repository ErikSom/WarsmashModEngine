/**
 * Player-name persistence. Stored in localStorage under a stable key
 * so refreshes / new tabs / different days all read the same value.
 *
 * The first visit to /multiplayer prompts for a name; once stored,
 * it threads through the lobby (peer list, control messages) and into
 * the multiplayer game-start payload so it ends up beside each slot
 * on the in-game player list.
 *
 * Validation is lightweight on purpose: trim, length cap, swap any
 * control characters for spaces. Anti-impersonation / profanity is
 * out-of-scope — the lobby is hosted P2P with no global registry.
 */

const KEY = 'warsmash.playerName';
const MAX_LEN = 24;

export function getPlayerName(): string {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? sanitize(raw) : '';
  }
  catch {
    return '';
  }
}

export function setPlayerName(name: string): string {
  const clean = sanitize(name);
  try {
    if (clean) localStorage.setItem(KEY, clean);
    else       localStorage.removeItem(KEY);
  }
  catch { /* private mode / quota */ }
  return clean;
}

export function clearPlayerName(): void {
  try { localStorage.removeItem(KEY); }
  catch { /* ignore */ }
}

export function sanitize(input: string): string {
  // Strip control chars (0x00-0x1F + 0x7F), collapse repeated whitespace,
  // trim, then cap. We keep emoji + extended unicode untouched — those
  // render fine in Preact and on the in-game player list.
  // eslint-disable-next-line no-control-regex
  return String(input ?? '')
    .replace(/[\x00-\x1F\x7F]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_LEN);
}

export function isValidName(name: string): boolean {
  const clean = sanitize(name);
  return clean.length >= 2;
}
