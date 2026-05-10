/**
 * WC3 player colors. The engine identifies a player's color by an
 * integer 0..11 (12 base colors); some custom expansions add more
 * but we stop at the canonical TFT set. Hex values are taken from
 * the WC3 client's colour table — they show up in chat, on minimap,
 * and as team-coloured tints on units.
 *
 * Default mapping for a fresh slot is `slotIndex → colorId` (slot 0
 * = Red, slot 1 = Blue, etc.). Players can change away from the
 * default in the lobby; uniqueness is NOT currently enforced (WC3
 * itself blocks duplicates in melee, but we leave that policy to
 * the host for now).
 */

export interface PlayerColor {
  id: number;
  name: string;
  hex: string;
}

export const PLAYER_COLORS: PlayerColor[] = [
  { id: 0,  name: 'Red',         hex: '#ff0303' },
  { id: 1,  name: 'Blue',        hex: '#0042ff' },
  { id: 2,  name: 'Teal',        hex: '#1ce6b9' },
  { id: 3,  name: 'Purple',      hex: '#540081' },
  { id: 4,  name: 'Yellow',      hex: '#fffc01' },
  { id: 5,  name: 'Orange',      hex: '#fe8a0e' },
  { id: 6,  name: 'Green',       hex: '#20c000' },
  { id: 7,  name: 'Pink',        hex: '#e55bb0' },
  { id: 8,  name: 'Gray',        hex: '#959697' },
  { id: 9,  name: 'Light Blue',  hex: '#7ebff1' },
  { id: 10, name: 'Dark Green',  hex: '#106246' },
  { id: 11, name: 'Brown',       hex: '#4e2a04' },
];

export function colorById(id: number): PlayerColor {
  return PLAYER_COLORS[id] ?? PLAYER_COLORS[0];
}

/** Default color for a freshly-created slot — matches WC3's "slot N
 *  starts as the Nth colour" convention. Wraps around for slots
 *  beyond 11 (very rare custom maps). */
export function defaultColorForSlot(slotIndex: number): number {
  return ((slotIndex % PLAYER_COLORS.length) + PLAYER_COLORS.length) % PLAYER_COLORS.length;
}

/** Standard WC3 handicap percentages. 100% = full HP/damage; lower
 *  values weaken a player for matchmaking handicap. */
export const HANDICAP_VALUES = [50, 60, 70, 80, 90, 100] as const;
export type Handicap = typeof HANDICAP_VALUES[number];
export const DEFAULT_HANDICAP: Handicap = 100;
