/**
 * Lobby-state singleton — bridges the imperative netlib callback
 * surface to a reactive store the Preact pages can subscribe to.
 *
 * Module-level state (`state`, `subscribers`) lives for the lifetime
 * of the JS context, so as long as we use Astro view-transitions to
 * navigate (no page reload), the WebRTC mesh + lobby info survive
 * the trip from /multiplayer → /multiplayer/:code → /play.
 *
 * This module also OWNS the bridge-init handshake: importing it
 * triggers `pokiBridgeInit` once with the Warsmash game id and our
 * own callbacks, which fan events out to subscribers + maintain the
 * `state` snapshot. Components don't talk to the bridge directly for
 * lifecycle — they call methods on this module (createLobby, join,
 * leave, list).
 *
 * Engine-traffic forwarding: when the engine worker is attached
 * (via attachEngineWorker), unreliable-channel messages relay into
 * it; mp-rtc-send-to / mp-rtc-broadcast / mp-close from the worker
 * relay back out to netlib.
 */

import {
  pokiBridgeBroadcastBytes, pokiBridgeBroadcastString, pokiBridgeClose,
  pokiBridgeCreateLobby, pokiBridgeInit, pokiBridgeJoinLobby,
  pokiBridgeLeaveLobby, pokiBridgeListLobbies, pokiBridgeSendBytesTo,
  pokiBridgeSendStringTo, pokiBridgeSetLobbySettings,
  type PublicLobbyEntry,
} from './poki-bridge';
import { extractMapPlayerCount } from './mapMeta';
import {
  forceContainsSlot, PlayerRace, PlayerType,
  type MapForce, type MapInfo, type MapPlayerSlot,
} from './mapInfo';
import { getMapInfoForFullPath } from './mapInfoCache';
import { getPlayerName } from './playerName';
import { DEFAULT_HANDICAP, defaultColorForSlot } from './playerColors';

// Stable UUID — must match WarsmashWebGameId.UUID on the Java side
// so the JS-driven lobby and the engine's understanding of "which
// game id is this" don't drift.
export const WARSMASH_GAME_ID = 'a664aaff-9291-4c9c-9f26-47b56eb7914a';

export interface LobbyPlayer {
  /** netlib peer id. Globally unique within a game id. */
  peerId: string;
  /** Display name from the peer's localStorage; "Anonymous" until
   *  they introduce themselves over the reliable channel. */
  name: string;
}

/** Status of a slot independent of who occupies it.
 *   - `open`   = anyone may claim (default)
 *   - `closed` = host has locked the slot; nobody may claim
 *  AI/Computer slot fillers aren't supported by our engine port,
 *  so they're not in this enum. */
export type LobbySlotType = 'open' | 'closed';

export interface LobbySlot {
  /** Slot id from the map's w3i (matches mapInfo.players[i].id when
   *  mapInfo is loaded; otherwise just an index). 0-based. */
  index: number;
  /** Open vs. closed. Only the host can flip this. */
  type: LobbySlotType;
  /** netlib peer id of the player in this slot, or null if open. */
  occupant: string | null;
  /** Current race assignment. Starts at the map's declared race for
   *  this slot — `Selectable` (0) when the map says "let the lobby
   *  pick", positive when the map suggests/locks a specific race.
   *  Editable unless mapInfo.fixedPlayerSettings is true. */
  race: PlayerRace;
  /** WC3 player-color id 0..11. See lib/playerColors. Uniqueness
   *  enforced host-side — attempts to claim an in-use color are
   *  rejected silently. */
  color: number;
  /** Force/team index into mapInfo.forces, or -1 when the map has
   *  no forces declared (FFA). Editable unless
   *  mapInfo.fixedPlayerSettings is true. */
  team: number;
  /** Handicap percentage 50/60/70/80/90/100. Default 100. */
  handicap: number;
}

export interface LobbyState {
  /** netlib has connected to signaling and emitted 'ready'. */
  ready: boolean;
  /** The local peer id, or '' before ready. */
  selfId: string;
  /** Lobby code we're currently in, or null if not in a lobby. */
  lobbyCode: string | null;
  /** netlib peer id of the current lobby leader (host), or '' if
   *  unknown/none. Single source of truth for "who is host" — UIs
   *  derive `player.peerId === state.leaderId` on each row.
   *
   *  We track this separately (not as a per-player flag) because
   *  netlib's 'leader' event can fire BEFORE the host's peer
   *  connection completes, so when 'peerConnected' adds the host
   *  to the players list there's nothing to stamp `isHost: true`
   *  onto. Storing leaderId once and resolving at render time
   *  removes the race entirely. */
  leaderId: string;
  /** Is this client the host (lobby leader)? Derived; convenient. */
  isHost: boolean;
  /** All players in the current lobby, including self. */
  players: LobbyPlayer[];
  /** Map path the host has chosen for the next start. */
  selectedMap: string;
  /** Slot count — derived from the parsed mapInfo.humanLikeSlots
   *  when available, falling back to the filename heuristic before
   *  the map is parsed. */
  maxPlayers: number;
  /** Slot assignments — host-authoritative. Length === maxPlayers.
   *  Joiners receive these via the 'lobby-state' control message
   *  whenever the host updates them. */
  slots: LobbySlot[];
  /** Parsed map metadata (name, author, slot table, forces). Null
   *  until the parser finishes — both host and joiners load this
   *  asynchronously from their own OPFS, keyed by the engine path. */
  mapInfo: MapInfo | null;
  /** Last error surfaced from the bridge (empty if none). */
  lastError: string;
  /** True iff the bridge had an init failure (netlib library missing,
   *  WebRTC unavailable, etc.). */
  bridgeFailed: boolean;
}

// ---- Module-level state + subscription primitives ---------------------

const DEFAULT_MAP = 'Maps/FrozenThrone/(2)EchoIsles.w3x';

const DEFAULT_MAX_PLAYERS = extractMapPlayerCount(DEFAULT_MAP);

let state: LobbyState = {
  ready: false,
  selfId: '',
  lobbyCode: null,
  leaderId: '',
  isHost: false,
  players: [],
  selectedMap: DEFAULT_MAP,
  maxPlayers: DEFAULT_MAX_PLAYERS,
  slots: emptySlots(DEFAULT_MAX_PLAYERS),
  mapInfo: null,
  lastError: '',
  bridgeFailed: false,
};

/** Synthetic slot table for when we haven't parsed mapInfo yet —
 *  e.g. while the map is still loading, or for joiners whose OPFS
 *  doesn't have the map. UI components look up the matching
 *  mapInfo.players[slot.index] for display name + default race;
 *  if no mapInfo, they fall back to "Slot N". */
function emptySlots(n: number): LobbySlot[] {
  const out: LobbySlot[] = [];
  for (let i = 0; i < n; i++) {
    out.push({
      index: i,
      type: 'open',
      occupant: null,
      race: PlayerRace.Selectable,
      color: defaultColorForSlot(i),
      team: -1,
      handicap: DEFAULT_HANDICAP,
    });
  }
  return out;
}

/** Build a fresh slot table from a map's parsed player list. Each
 *  slot inherits its default race + team from the map; color
 *  defaults to the slot's index in WC3's standard palette. */
function slotsFromMapInfo(mapInfo: MapInfo): LobbySlot[] {
  return mapInfo.humanLikeSlots.map((p: MapPlayerSlot) => ({
    index: p.id,
    type: 'open' as LobbySlotType,
    occupant: null,
    race: p.race,
    color: defaultColorForSlot(p.id),
    team: teamForSlot(p.id, mapInfo.forces),
    handicap: DEFAULT_HANDICAP,
  }));
}

/** Find which force a slot belongs to. Returns the index into the
 *  forces array, or -1 when no force claims the slot. */
function teamForSlot(slotId: number, forces: MapForce[]): number {
  for (let i = 0; i < forces.length; i++) {
    if (forceContainsSlot(forces[i], slotId)) return i;
  }
  return -1;
}

/** True when a peer can claim or be auto-placed into this slot.
 *  Closed slots are explicitly off-limits; for UMS / fixed-settings
 *  maps, slots the map declared as Computer are also off-limits
 *  (they're predetermined AI). For melee maps every map slot is
 *  joinable (host overrides the map's slot-type defaults). */
export function isSlotJoinable(slot: LobbySlot, mapInfo: MapInfo | null): boolean {
  if (slot.type === 'closed') return false;
  if (mapInfo && mapInfo.fixedPlayerSettings) {
    const mapSlot = mapInfo.players.find(p => p.id === slot.index);
    if (mapSlot && mapSlot.type === PlayerType.Computer) return false;
  }
  return true;
}

type Listener = (s: LobbyState) => void;
const subscribers = new Set<Listener>();

function setState(patch: Partial<LobbyState>): void {
  state = { ...state, ...patch };
  for (const fn of subscribers) {
    try { fn(state); }
    catch (e) { console.error('[lobbyClient] subscriber threw:', e); }
  }
}

export function getLobbyState(): LobbyState {
  return state;
}

export function subscribeLobbyState(fn: Listener): () => void {
  subscribers.add(fn);
  return () => subscribers.delete(fn);
}

// ---- Engine-worker forwarding (re-exposed for /play to call) ---------

let engineWorker: Worker | null = null;
let engineMessageListener: ((e: MessageEvent) => void) | null = null;

export function attachEngineWorker(worker: Worker): void {
  if (engineWorker === worker) return;
  detachEngineWorker();
  engineWorker = worker;

  // Replay current netlib state to the worker — it might have missed
  // events that fired before it spawned. The worker's mp-handler-ready
  // handshake re-replays for guaranteed delivery once handlers are up.
  replayStateToWorker();

  engineMessageListener = (e) => {
    const d = e.data;
    if (!d || typeof d !== 'object' || typeof d.kind !== 'string') return;
    switch (d.kind) {
      case 'mp-rtc-send-to':
        pokiBridgeSendBytesTo(d.peerId, d.channel, d.bytes);
        break;
      case 'mp-rtc-broadcast':
        pokiBridgeBroadcastBytes(d.channel, d.bytes);
        break;
      case 'mp-close':
        pokiBridgeClose();
        break;
      case 'mp-handler-ready':
        replayStateToWorker();
        break;
    }
  };
  worker.addEventListener('message', engineMessageListener);
}

export function detachEngineWorker(): void {
  if (engineWorker && engineMessageListener) {
    engineWorker.removeEventListener('message', engineMessageListener);
  }
  engineWorker = null;
  engineMessageListener = null;
}

function postToWorker(msg: object): void {
  if (engineWorker) engineWorker.postMessage(msg);
}

function replayStateToWorker(): void {
  if (!engineWorker) return;
  postToWorker({
    kind: 'mp-state',
    selfId: state.selfId,
    currentLobby: state.lobbyCode || '',
    currentLeader: state.leaderId,
    peerIds: state.players.map(p => p.peerId).filter(id => id !== state.selfId),
  });
  if (state.selfId) postToWorker({ kind: 'mp-ready', selfId: state.selfId });
  if (state.lobbyCode) postToWorker({ kind: 'mp-lobby', code: state.lobbyCode });
  for (const p of state.players) {
    if (p.peerId !== state.selfId) {
      postToWorker({ kind: 'mp-peer-connected', peerId: p.peerId });
    }
  }
}

// ---- Bridge initialization (run once on first import) ----------------

let bridgeInitialised = false;

function ensureBridgeInit(): void {
  if (bridgeInitialised) return;
  bridgeInitialised = true;

  const ok = pokiBridgeInit(WARSMASH_GAME_ID, {
    ready: (selfId) => {
      // Diagnostic: if the server is using session cookies, this id
      // will be the SAME across page reloads in the same browser
      // (Poki's signaling cookie is scoped to netlib.poki.io and the
      // browser auto-sends it with every WebSocket handshake to that
      // domain). Reload twice and compare to confirm.
      console.log('[lobbyClient] ready as peer', selfId);
      setState({ ready: true, selfId, lastError: '' });
      postToWorker({ kind: 'mp-ready', selfId });
    },
    signalingreconnected: (selfId) => {
      // Netlib's auto-reconnect succeeded on a transient WebSocket
      // drop. Same peer id as before. The server re-sends 'joined'
      // for the lobby we were in, which fires the 'lobby' callback
      // below — no extra work needed here beyond flipping ready in
      // case the drop happened before initial 'ready' fired.
      console.log('[lobbyClient] signaling reconnected as peer', selfId);
      setState({ ready: true, selfId, lastError: '' });
      postToWorker({ kind: 'mp-ready', selfId });
    },
    lobby: (code, leaderId) => {
      // The 'joined' packet carries the lobby leader inline, so we
      // know up front whether WE created (we're the leader) or
      // joined someone else's lobby. Setting leaderId/isHost here
      // means slot rendering can be correct on the very first paint
      // — no race with the separate 'leader' event firing later.
      const isCreator = leaderId !== '' && leaderId === state.selfId;
      const selfPlayer: LobbyPlayer = {
        peerId: state.selfId,
        name: getPlayerName() || 'Anonymous',
      };
      // Host: seed self into slot 0 of the current slot table (which
      // createLobby already shaped from mapInfo). Joiners: don't
      // touch slots — the host's 'lobby-state' broadcast will
      // replace them shortly.
      const seededSlots = isCreator
        ? slotsWithOccupant(state.slots, 0, state.selfId)
        : state.slots;
      setState({
        lobbyCode: code,
        leaderId,
        isHost: isCreator,
        players: [selfPlayer],
        slots: seededSlots,
        lastError: '',
      });
      postToWorker({ kind: 'mp-lobby', code });
      announceSelf();
      if (isCreator) broadcastLobbyState();
    },
    left: () => {
      setState({
        lobbyCode: null,
        leaderId: '',
        isHost: false,
        players: [],
        slots: emptySlots(state.maxPlayers),
        mapInfo: null,
      });
      postToWorker({ kind: 'mp-left' });
    },
    peerConnected: (peerId) => {
      const next = state.players.slice();
      if (!next.some(p => p.peerId === peerId)) {
        next.push({ peerId, name: 'Anonymous' });
        setState({ players: next });
      }
      // Host: place the newcomer in the first joinable open slot
      // (joiners wait for the host's broadcast — they don't manage
      // slots). Computer-typed slots in fixed-settings maps are
      // skipped because the map declared them as predetermined AI.
      if (state.isHost) {
        const updated = assignToFirstOpenSlot(state.slots, peerId, state.mapInfo);
        if (updated !== state.slots) {
          setState({ slots: updated });
          broadcastLobbyState();
        }
      }
      postToWorker({ kind: 'mp-peer-connected', peerId });
      announceSelfTo(peerId);
      // Send the new peer the current lobby snapshot directly (in
      // addition to the broadcast above) so they don't miss it if
      // they connected mid-broadcast.
      if (state.isHost) sendLobbyStateTo(peerId);
    },
    peerDisconnected: (peerId, reason) => {
      const nextPlayers = state.players.filter(p => p.peerId !== peerId);
      const nextSlots = state.isHost ? slotsWithoutOccupant(state.slots, peerId) : state.slots;
      setState({ players: nextPlayers, slots: nextSlots });
      if (state.isHost) broadcastLobbyState();
      postToWorker({ kind: 'mp-peer-disconnected', peerId, reason: reason || '' });
    },
    leader: (id) => {
      // Single source of truth for "who is host". Components match
      // this against each player's peerId at render time.
      setState({ leaderId: id, isHost: id === state.selfId });
      postToWorker({ kind: 'mp-leader', leaderId: id });
    },
    message: (peerId, channel, bytes, isString, stringValue) => {
      if (channel === 'reliable') {
        handleControlMessage(peerId, isString ? stringValue : decodeUtf8(bytes));
        return;
      }
      // Unreliable channel — engine traffic. Forward to the worker if
      // attached; drop otherwise (no engine to feed yet).
      postToWorker({
        kind: 'mp-rtc-msg',
        peerId, channel, bytes, isString, stringValue,
      });
    },
    error: (kind, message) => {
      setState({ lastError: kind + ': ' + message });
      postToWorker({ kind: 'mp-error', errKind: kind, message: message || '' });
    },
  });

  if (!ok) {
    setState({ bridgeFailed: true, lastError: 'pokiBridgeInit returned false — netlib library load issue?' });
  }
}

// Run the init at module load. Safe in browser; no-op on SSR.
if (typeof window !== 'undefined') {
  ensureBridgeInit();
  installCleanupHandlers();
}

/**
 * Best-effort cleanup so we don't leave orphan lobbies on Poki's
 * signaling server when the user closes the tab or hard-reloads.
 * pokiBridgeClose() is synchronous (calls net.close which fires the
 * WebSocket close frame inline), so it actually completes during
 * beforeunload — unlike net.leave() which is async and gets cut off.
 *
 * 'pagehide' is the modern preferred event (fires for bfcache and
 * hard nav), 'beforeunload' is the classic fallback. We listen on
 * both because browser support varies and the redundancy is cheap.
 *
 * Astro's view-transitions soft-nav DOESN'T fire either of these —
 * so navigation between /multiplayer → /play → /multiplayer keeps
 * the bridge alive. Only an actual page tear-down triggers cleanup.
 */
function installCleanupHandlers(): void {
  const cleanup = () => {
    try { pokiBridgeClose(); }
    catch { /* ignore — page is dying anyway */ }
  };
  window.addEventListener('pagehide', cleanup);
  window.addEventListener('beforeunload', cleanup);
}

// ---- Reliable-channel control protocol -------------------------------
//
// We use simple JSON messages over the reliable channel for lobby
// coordination. The wire format mirrors what TeaVM's engine code on
// the worker side expects; here we only handle messages that affect
// pre-game state (player names, map updates, start handshake). Engine
// lockstep traffic uses the unreliable channel and is forwarded to
// the worker untouched.

interface IntroduceMsg   { type: 'introduce'; name: string; }
interface LobbyStateMsg  { type: 'lobby-state'; mapPath: string; maxPlayers: number; slots: LobbySlot[]; }
interface ClaimSlotMsg   { type: 'claim-slot'; slotIndex: number; }
/** Snapshot of every slot's config the host ships to joiners as part
 *  of `start-as-joiner` so the engine on each peer can apply the
 *  same race/color/team/handicap. The host's own engine receives the
 *  same data via the buildHostStartPayload path. */
interface SlotConfigEntry {
  index: number;
  type: LobbySlotType;
  race: PlayerRace;
  color: number;
  team: number;
  handicap: number;
}
interface UpdateSlotMsg  {
  type: 'update-slot';
  slotIndex: number;
  /** All fields are optional — the host applies the present ones
   *  and ignores the absent. Race/color/team/handicap are validated
   *  against the slot's permissions; `slotType` is host-only and
   *  silently dropped from non-host requests. */
  race?: PlayerRace;
  color?: number;
  team?: number;
  handicap?: number;
  slotType?: LobbySlotType;
}
interface KickedMsg      { type: 'kicked'; reason: string; }
interface StartGameMsg   {
  type: 'start-as-joiner';
  mapPath: string;
  hostPeerId: string;
  mySessionToken: string;
  mySlot: string;
  sessionTokenToSlot: Record<string, number>;
  /** Full slot config table — joiners apply this verbatim to their
   *  engine so race/color/team/handicap match the host's lobby UI. */
  slotConfigs: SlotConfigEntry[];
}
type ControlMsg = IntroduceMsg | LobbyStateMsg | ClaimSlotMsg | UpdateSlotMsg | KickedMsg | StartGameMsg;

/** Fields a slot occupant or host may change on a slot. `slotType`
 *  is host-only — non-host callers passing it are silently rejected. */
export interface SlotUpdate {
  race?: PlayerRace;
  color?: number;
  team?: number;
  handicap?: number;
  slotType?: LobbySlotType;
}

function announceSelf(): void {
  const name = getPlayerName() || 'Anonymous';
  const msg: IntroduceMsg = { type: 'introduce', name };
  pokiBridgeBroadcastString('reliable', JSON.stringify(msg));
}

function announceSelfTo(peerId: string): void {
  const name = getPlayerName() || 'Anonymous';
  const msg: IntroduceMsg = { type: 'introduce', name };
  pokiBridgeSendStringTo(peerId, 'reliable', JSON.stringify(msg));
}

/** Host: broadcast the current lobby snapshot (map + slots) to all
 *  joiners. They'll replace their local state with the contents. */
function broadcastLobbyState(): void {
  if (!state.isHost) return;
  const msg: LobbyStateMsg = {
    type: 'lobby-state',
    mapPath: state.selectedMap,
    maxPlayers: state.maxPlayers,
    slots: state.slots,
  };
  pokiBridgeBroadcastString('reliable', JSON.stringify(msg));
}

/** Host: send the lobby snapshot to a single peer (used right after
 *  peerConnected so newcomers don't miss the broadcast). */
function sendLobbyStateTo(peerId: string): void {
  if (!state.isHost) return;
  const msg: LobbyStateMsg = {
    type: 'lobby-state',
    mapPath: state.selectedMap,
    maxPlayers: state.maxPlayers,
    slots: state.slots,
  };
  pokiBridgeSendStringTo(peerId, 'reliable', JSON.stringify(msg));
}

function handleControlMessage(peerId: string, payload: string): void {
  let msg: ControlMsg;
  try { msg = JSON.parse(payload) as ControlMsg; }
  catch (e) {
    console.warn('[lobbyClient] control msg parse failed from', peerId, e);
    return;
  }
  switch (msg.type) {
    case 'introduce': {
      const next = state.players.map(p => p.peerId === peerId ? { ...p, name: msg.name } : p);
      setState({ players: next });
      break;
    }
    case 'lobby-state': {
      // Joiner-side: replace local lobby snapshot with what the host
      // broadcasts. We trust the leader peer only (anyone else
      // sending this is ignored).
      if (peerId !== state.leaderId) {
        console.warn('[lobbyClient] ignored lobby-state from non-leader', peerId);
        return;
      }
      const incomingMapPath = msg.mapPath;
      const mapChanged = state.selectedMap !== incomingMapPath;
      setState({
        selectedMap: incomingMapPath,
        maxPlayers: msg.maxPlayers,
        slots: msg.slots,
        // Drop stale mapInfo when the map path changes — the async
        // load below replaces it. Keeps state.mapInfo from pointing
        // at the previous map's data while the new parse is in flight.
        mapInfo: mapChanged ? null : state.mapInfo,
      });
      if (mapChanged) {
        // Best-effort: parse the same map file on the joiner's
        // local OPFS so they see real names/races/forces. Falls back
        // silently when the joiner doesn't have the map staged.
        getMapInfoForFullPath(incomingMapPath)
          .then((info) => {
            if (state.selectedMap === incomingMapPath) {
              setState({ mapInfo: info });
            }
          })
          .catch((e) => {
            console.warn('[lobbyClient] joiner mapInfo load failed for', incomingMapPath, e);
          });
      }
      break;
    }
    case 'claim-slot': {
      // Host-side only: a joiner is asking to move into slotIndex.
      // We swap them in if the slot is open AND not closed; ignore
      // otherwise.
      if (!state.isHost) return;
      const target = msg.slotIndex;
      if (target < 0 || target >= state.maxPlayers) return;
      const slots = state.slots;
      const targetSlot = slots[target];
      if (!targetSlot || targetSlot.occupant !== null) return;
      if (!isSlotJoinable(targetSlot, state.mapInfo)) return;
      // Move peer from wherever they are now into the target slot.
      const moved = slotsWithoutOccupant(slots, peerId);
      moved[target] = { ...moved[target], occupant: peerId };
      setState({ slots: moved });
      broadcastLobbyState();
      break;
    }
    case 'update-slot': {
      // Host-side only: a joiner is asking to change one of their
      // own slot's fields. Validate that they actually own the slot
      // they're targeting before applying. Strip slotType — that's
      // host-only.
      if (!state.isHost) return;
      const slot = state.slots.find(s => s.index === msg.slotIndex);
      if (!slot || slot.occupant !== peerId) return;
      applySlotUpdate(msg.slotIndex, {
        race: msg.race, color: msg.color, team: msg.team, handicap: msg.handicap,
        // Deliberately NOT msg.slotType — joiners can't open/close slots.
      });
      break;
    }
    case 'kicked': {
      // Joiner-side: host has kicked us. Leave gracefully; UI will
      // navigate back to the browser via the 'left' callback.
      if (peerId !== state.leaderId) return;
      console.log('[lobbyClient] kicked by host:', msg.reason);
      pokiBridgeLeaveLobby();
      break;
    }
    case 'start-as-joiner': {
      pendingStartFromHost = msg;
      for (const fn of startListeners) try { fn(msg); } catch (e) { console.error(e); }
      break;
    }
  }
}

let pendingStartFromHost: StartGameMsg | null = null;
const startListeners = new Set<(m: StartGameMsg) => void>();

export function getPendingStartFromHost(): StartGameMsg | null {
  return pendingStartFromHost;
}

/** Fires when a 'start-as-joiner' control message arrives. Used by the
 *  lobby room to navigate to /play once the host hits Start. */
export function onStartFromHost(fn: (m: StartGameMsg) => void): () => void {
  startListeners.add(fn);
  return () => startListeners.delete(fn);
}

// ---- Public API for components ---------------------------------------

export interface CreateLobbyOptions {
  maxPlayers?: number;
  /** Host's initial map pick — saved in customData so the lobby
   *  browser can show "Hosting: Echo Isles" without a separate
   *  control message. */
  mapPath?: string;
  password?: string;
}

export async function createLobby(opts: CreateLobbyOptions = {}): Promise<string> {
  const playerName = getPlayerName() || 'Anonymous';
  const mapPath = opts.mapPath ?? DEFAULT_MAP;

  // Pre-load mapInfo so the lobby is announced with the right slot
  // count + customData from the start. Falls through to the filename
  // heuristic if the parse fails (e.g. corrupt map file).
  let mapInfo: MapInfo | null = null;
  try { mapInfo = await getMapInfoForFullPath(mapPath); }
  catch (e) {
    console.warn('[lobbyClient] mapInfo load failed for default map; using filename heuristic', e);
  }
  const maxFromMap = mapInfo?.humanLikeSlots.length ?? extractMapPlayerCount(mapPath);

  const settings = {
    public: !opts.password,
    maxPlayers: opts.maxPlayers ?? maxFromMap,
    password: opts.password,
    customData: { mapPath, hostName: playerName },
  };

  // Build the initial slot table from mapInfo (when available) so the
  // first frame after createLobby resolves shows the real slot ids
  // and the host already seated. UMS maps where slot 0 is a forced
  // Computer slot need the host placed into the first JOINABLE slot
  // instead — without the skip, the host would visibly occupy a
  // map-locked AI slot which would then refuse all edits.
  const initialSlots = mapInfo ? slotsFromMapInfo(mapInfo) : emptySlots(settings.maxPlayers);
  const seatIdx = initialSlots.findIndex(s => isSlotJoinable(s, mapInfo));
  if (seatIdx >= 0) {
    initialSlots[seatIdx] = { ...initialSlots[seatIdx], occupant: state.selfId };
  }

  return new Promise<string>((resolve, reject) => {
    pokiBridgeCreateLobby(
      (code) => {
        setState({
          leaderId: state.selfId,
          isHost: true,
          selectedMap: mapPath,
          maxPlayers: settings.maxPlayers,
          slots: initialSlots,
          mapInfo,
        });
        resolve(code);
      },
      (reason) => reject(new Error(reason)),
      settings,
    );
  });
}

export async function joinLobby(code: string, password?: string): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    pokiBridgeJoinLobby(
      code,
      () => {
        // leaderId comes from the 'leader' event (fired by netlib
        // right after 'lobby' lands the joined packet). Don't pre-
        // empt it.
        setState({ isHost: false });
        resolve();
      },
      (reason) => reject(new Error(reason)),
      password,
    );
  });
}

export function leaveLobby(): void {
  pokiBridgeLeaveLobby();
}

export async function listPublicLobbies(): Promise<PublicLobbyEntry[]> {
  return new Promise<PublicLobbyEntry[]>((resolve, reject) => {
    pokiBridgeListLobbies(
      (entries) => resolve(entries),
      (reason) => reject(new Error(reason)),
    );
  });
}

/** Result of planning a map change — shape needed by the
 *  confirm-kick UI and also fed back into applyMapChange(). */
export interface MapChangePlan {
  mapPath: string;
  mapInfo: MapInfo;
  /** New slot layout, with surviving occupants placed in slot order
   *  (host first, then by previous slot index). */
  newSlots: LobbySlot[];
  /** Peer ids that would lose their seat. */
  surplus: string[];
}

/** Host-only: load + parse the target map, then compute a plan for
 *  the slot rejig (who survives, who gets kicked). Doesn't mutate
 *  state. Throws when mapInfo load fails — the UI surfaces that. */
export async function planMapChange(mapPath: string): Promise<MapChangePlan> {
  const mapInfo = await getMapInfoForFullPath(mapPath);
  const newMaxPlayers = mapInfo.humanLikeSlots.length;

  // Repack current occupants into the new slot table. Host always
  // survives (they're the one picking the map); other survivors are
  // kept in their current slot order.
  const occupied = state.slots.filter(s => s.occupant != null);
  const hostFirst = occupied.slice().sort((a, b) => {
    if (a.occupant === state.leaderId) return -1;
    if (b.occupant === state.leaderId) return 1;
    return a.index - b.index;
  });
  const survivors = hostFirst.slice(0, newMaxPlayers).map(s => s.occupant!);
  const surplus = hostFirst.slice(newMaxPlayers).map(s => s.occupant!);

  const newSlots = slotsFromMapInfo(mapInfo);
  survivors.forEach((peerId, i) => {
    if (i < newSlots.length) newSlots[i] = { ...newSlots[i], occupant: peerId };
  });

  return { mapPath, mapInfo, newSlots, surplus };
}

/** Host-only: apply a previously-computed map-change plan. Kicks
 *  surplus peers, updates state, broadcasts the new lobby snapshot,
 *  and republishes maxPlayers + customData to Poki's signaling so
 *  the lobby browser sees the cap. */
export function applyMapChange(plan: MapChangePlan): void {
  if (!state.isHost) return;

  // Send kick messages BEFORE mutating state so the messages go out
  // over still-live RTC channels. Each kicked peer leaves on their
  // own end; their disconnection then arrives via peerDisconnected,
  // which is a no-op for slots we've already cleared here.
  for (const peerId of plan.surplus) {
    const msg: KickedMsg = { type: 'kicked', reason: 'map changed to a smaller player count' };
    pokiBridgeSendStringTo(peerId, 'reliable', JSON.stringify(msg));
  }

  setState({
    selectedMap: plan.mapPath,
    maxPlayers: plan.newSlots.length,
    slots: plan.newSlots,
    mapInfo: plan.mapInfo,
  });
  broadcastLobbyState();
  pokiBridgeSetLobbySettings(
    {
      maxPlayers: plan.newSlots.length,
      customData: { mapPath: plan.mapPath, hostName: getPlayerName() || 'Anonymous' },
    },
    undefined,
    (reason) => setState({ lastError: 'setLobbySettings: ' + reason }),
  );
}

/** Convenience: plan + apply in one step, used when the host picks a
 *  map that doesn't shrink the player count (no kicks → no
 *  confirmation needed). For shrink cases the UI calls planMapChange,
 *  shows the confirm modal, then calls applyMapChange on confirm. */
export async function setSelectedMap(mapPath: string): Promise<void> {
  if (!state.isHost) return;
  const plan = await planMapChange(mapPath);
  applyMapChange(plan);
}

/** Host-only: kick a specific peer by id. They receive a 'kicked'
 *  control message and leave the lobby on their side. */
export function kickPeer(peerId: string, reason: string = 'kicked by host'): void {
  if (!state.isHost) return;
  if (peerId === state.selfId) return;  // can't kick self
  const msg: KickedMsg = { type: 'kicked', reason };
  pokiBridgeSendStringTo(peerId, 'reliable', JSON.stringify(msg));
  // Optimistically free the slot so the host sees an immediate update;
  // peerDisconnected will repeat the cleanup once netlib catches up.
  setState({ slots: slotsWithoutOccupant(state.slots, peerId) });
  broadcastLobbyState();
}

/** Joiner-side: ask the host to move us into slotIndex. Host validates
 *  and broadcasts a fresh lobby-state if approved. No-op if WE are
 *  the host (use moveSelfToSlot instead). */
export function requestSlot(slotIndex: number): void {
  if (state.isHost) {
    moveSelfToSlot(slotIndex);
    return;
  }
  if (!state.leaderId) return;
  const msg: ClaimSlotMsg = { type: 'claim-slot', slotIndex };
  pokiBridgeSendStringTo(state.leaderId, 'reliable', JSON.stringify(msg));
}

/** Host-side: move self into slotIndex if it's open + joinable. */
export function moveSelfToSlot(slotIndex: number): void {
  if (!state.isHost) return;
  if (slotIndex < 0 || slotIndex >= state.maxPlayers) return;
  const slots = state.slots;
  const target = slots[slotIndex];
  if (!target || target.occupant !== null) return;
  if (!isSlotJoinable(target, state.mapInfo)) return;
  const moved = slotsWithoutOccupant(slots, state.selfId);
  moved[slotIndex] = { ...moved[slotIndex], occupant: state.selfId };
  setState({ slots: moved });
  broadcastLobbyState();
}

/** Update a slot's race/color/team/handicap. Permission rules:
 *   - the slot's occupant can change their own fields
 *   - the host can change any slot's fields
 *  Joiners send an `update-slot` message to the host; the host
 *  applies locally and broadcasts. Hosts apply directly. */
export function updateSlot(slotIndex: number, fields: SlotUpdate): void {
  const slot = state.slots.find(s => s.index === slotIndex);
  if (!slot) return;
  const isOccupantMe = slot.occupant === state.selfId;
  if (!state.isHost && !isOccupantMe) return;

  if (state.isHost) {
    applySlotUpdate(slotIndex, fields);
    return;
  }
  // Joiner path: forward the request to the host.
  if (!state.leaderId) return;
  const msg: UpdateSlotMsg = { type: 'update-slot', slotIndex, ...fields };
  pokiBridgeSendStringTo(state.leaderId, 'reliable', JSON.stringify(msg));
}

/** Host-side helper: write the requested fields into the slot table
 *  + broadcast. Applies only the fields the caller actually set;
 *  invalid values fall through unchanged. Validates:
 *   - race/team locked when mapInfo.fixedPlayerSettings === true
 *   - color must not collide with another slot's color
 *   - slotType=closed kicks any current occupant first
 *   - slotType only set when host calls (joiner path strips it) */
function applySlotUpdate(slotIndex: number, fields: SlotUpdate): void {
  if (!state.isHost) return;
  const racesLocked = state.mapInfo?.fixedPlayerSettings ?? false;
  const target = state.slots.find(s => s.index === slotIndex);
  if (!target) return;

  // Validate color uniqueness up front so we don't half-apply other
  // fields when one is rejected. WC3 lobbies block duplicate colors;
  // we mirror that.
  if (fields.color !== undefined) {
    const dup = state.slots.some(s => s.index !== slotIndex && s.color === fields.color);
    if (dup) {
      console.log('[lobbyClient] color', fields.color, 'already in use; rejecting update');
      delete fields.color;
    }
  }

  // Closing an occupied slot kicks the occupant first. Don't bother
  // with confirm here — the UI is responsible for that.
  if (fields.slotType === 'closed' && target.occupant) {
    const kickMsg: KickedMsg = { type: 'kicked', reason: 'host closed your slot' };
    pokiBridgeSendStringTo(target.occupant, 'reliable', JSON.stringify(kickMsg));
  }

  let changed = false;
  const slots = state.slots.map(s => {
    if (s.index !== slotIndex) return s;
    const next = { ...s };
    if (fields.race !== undefined && Number.isFinite(fields.race) && !racesLocked) {
      next.race = fields.race;
      changed = true;
    }
    if (fields.color !== undefined && fields.color >= 0 && fields.color < 12) {
      next.color = fields.color;
      changed = true;
    }
    if (fields.team !== undefined && fields.team >= -1 && !racesLocked) {
      next.team = fields.team;
      changed = true;
    }
    if (fields.handicap !== undefined && fields.handicap >= 50 && fields.handicap <= 100) {
      next.handicap = fields.handicap;
      changed = true;
    }
    if (fields.slotType !== undefined && (fields.slotType === 'open' || fields.slotType === 'closed')) {
      next.type = fields.slotType;
      // If we just closed an occupied slot, the kick message above
      // tells the peer to leave; clear the occupant locally now so
      // the broadcast reflects reality immediately.
      if (fields.slotType === 'closed') next.occupant = null;
      changed = true;
    }
    return next;
  });
  if (!changed) return;
  setState({ slots });
  broadcastLobbyState();
}

// ---- Slot helpers ----------------------------------------------------

function slotsWithOccupant(slots: LobbySlot[], slotIndex: number, peerId: string): LobbySlot[] {
  const next = slots.slice();
  if (slotIndex >= 0 && slotIndex < next.length) {
    next[slotIndex] = { ...next[slotIndex], occupant: peerId };
  }
  return next;
}

function slotsWithoutOccupant(slots: LobbySlot[], peerId: string): LobbySlot[] {
  let changed = false;
  const next = slots.map(s => {
    if (s.occupant === peerId) { changed = true; return { ...s, occupant: null }; }
    return s;
  });
  return changed ? next : slots;
}

function assignToFirstOpenSlot(slots: LobbySlot[], peerId: string, mapInfo: MapInfo | null): LobbySlot[] {
  // Skip if peer is already in some slot.
  if (slots.some(s => s.occupant === peerId)) return slots;
  const idx = slots.findIndex(s => s.occupant === null && isSlotJoinable(s, mapInfo));
  if (idx === -1) return slots;
  const next = slots.slice();
  next[idx] = { ...next[idx], occupant: peerId };
  return next;
}

/** Host-only: start the game. Reads the slot table to assign each
 *  peer their actual slot index, sends 'start-as-joiner' to every
 *  peer with a unique session token + the full slot config table,
 *  and resolves with the host's own start payload. Closed slots are
 *  flagged so the engine doesn't AI-fill them; empty open slots fall
 *  through to the engine's AI-filler fallback so melee games don't
 *  end at t=0. */
export function startGame(): {
  selfId: string;
  mapPath: string;
  sessionTokens: Record<string, number>;
  hostToken: string;
  hostSlot: number;
  slotConfigs: SlotConfigEntry[];
} | null {
  if (!state.isHost) return null;
  const sessionTokenToSlot: Record<string, number> = {};
  const hostSlotIdx = state.slots.findIndex(s => s.occupant === state.selfId);
  const hostSlot = hostSlotIdx >= 0 ? hostSlotIdx : 0;
  const hostToken = newSessionToken();
  sessionTokenToSlot[String(hostToken)] = hostSlot;

  // Snapshot the lobby's full slot table — every peer's engine
  // applies the same race/color/team/handicap so the in-game roster
  // matches the lobby UI. SlotConfigEntry mirrors LobbySlot minus
  // `occupant` (which is implicit from sessionTokenToSlot).
  const slotConfigs: SlotConfigEntry[] = state.slots.map(s => ({
    index: s.index,
    type: s.type,
    race: s.race,
    color: s.color,
    team: s.team,
    handicap: s.handicap,
  }));

  for (const slot of state.slots) {
    if (slot.occupant === null || slot.occupant === state.selfId) continue;
    const token = newSessionToken();
    sessionTokenToSlot[String(token)] = slot.index;
    const msg: StartGameMsg = {
      type: 'start-as-joiner',
      mapPath: state.selectedMap,
      hostPeerId: state.selfId,
      mySessionToken: String(token),
      mySlot: String(slot.index),
      sessionTokenToSlot,
      slotConfigs,
    };
    pokiBridgeSendStringTo(slot.occupant, 'reliable', JSON.stringify(msg));
  }
  return {
    selfId: state.selfId,
    mapPath: state.selectedMap,
    sessionTokens: sessionTokenToSlot,
    hostToken: String(hostToken),
    hostSlot,
    slotConfigs,
  };
}

function newSessionToken(): number {
  // Stay under Number.MAX_SAFE_INTEGER (2^53-1) so JS can represent
  // the value losslessly. We serialise as a string at every postMessage
  // hop, so the Java long picks it up exactly.
  return Math.floor(Math.random() * Number.MAX_SAFE_INTEGER);
}

function decodeUtf8(buf: Uint8Array): string {
  if (!buf) return '';
  return new TextDecoder().decode(buf);
}

// ---- Lobby → /play handoff -------------------------------------------
//
// When the host hits Start, /multiplayer needs to pass the start
// payload to /play so the engine worker can be told to boot into the
// multiplayer game once it reaches the menu screen. Astro's view
// transitions preserve this module's state across the nav (the JS
// context isn't re-initialised), so we stash the payload here as a
// module-level variable. /play reads it via consumeStartPayload().

export interface StartPayload {
  kind: 'mp-start-as-host' | 'mp-start-as-joiner';
  mapPath: string;
  hostPeerId: string;
  mySessionToken: string;
  mySlot: string;
  /** Parallel arrays — sessionTokens[i] maps to slots[i]. Strings on
   *  both sides because the Java long needs to round-trip exactly. */
  sessionTokens: string[];
  slots: string[];
  /** Slot config arrays, all parallel, length === total map slots
   *  (NOT just human peers). The engine on each peer applies these
   *  to CBasePlayer after loadAndCacheMapConfigs so race/color/team
   *  match what the host picked in the lobby. Strings throughout to
   *  keep the postMessage transit format uniform. */
  slotConfigIndexes:   string[];
  slotConfigTypes:     string[];  // 'open' | 'closed'
  slotConfigRaces:     string[];  // PlayerRace ids
  slotConfigColors:    string[];  // 0..11
  slotConfigTeams:     string[];  // force index, -1 for none
  slotConfigHandicaps: string[];  // 50..100
  /** Whether the map's w3i flag for "fixed player settings" is set.
   *  When true the engine activates ALL map-declared Computer slots
   *  (not just the first), mirroring single-player skirmish; when
   *  false it keeps the legacy melee fallback. Sent as a string so
   *  the postMessage shape stays uniform with the rest. */
  fixedPlayerSettings: string;  // 'true' | 'false'
}

let pendingStartPayload: StartPayload | null = null;

export function setStartPayload(p: StartPayload): void {
  pendingStartPayload = p;
}

/** Read-and-clear the pending start payload. /play calls this when
 *  EnginePage mounts after a multiplayer-driven nav. */
export function consumeStartPayload(): StartPayload | null {
  const p = pendingStartPayload;
  pendingStartPayload = null;
  return p;
}

/** Flatten a SlotConfigEntry[] into the parallel string arrays the
 *  postMessage wire format uses. */
function flattenSlotConfigs(configs: SlotConfigEntry[]): {
  indexes: string[]; types: string[]; races: string[];
  colors: string[]; teams: string[]; handicaps: string[];
} {
  const indexes:   string[] = [];
  const types:     string[] = [];
  const races:     string[] = [];
  const colors:    string[] = [];
  const teams:     string[] = [];
  const handicaps: string[] = [];
  for (const c of configs) {
    indexes.push(String(c.index));
    types.push(c.type);
    races.push(String(c.race));
    colors.push(String(c.color));
    teams.push(String(c.team));
    handicaps.push(String(c.handicap));
  }
  return { indexes, types, races, colors, teams, handicaps };
}

/** Build a host-side start payload from the result of `startGame()`. */
export function buildHostStartPayload(
  mapPath: string, hostPeerId: string, hostToken: string,
  sessionTokenToSlot: Record<string, number>,
  slotConfigs: SlotConfigEntry[],
): StartPayload {
  const sessionTokens: string[] = [];
  const slots: string[] = [];
  for (const t of Object.keys(sessionTokenToSlot)) {
    sessionTokens.push(t);
    slots.push(String(sessionTokenToSlot[t]));
  }
  const flat = flattenSlotConfigs(slotConfigs);
  const fps = state.mapInfo?.fixedPlayerSettings ? 'true' : 'false';
  return {
    kind: 'mp-start-as-host',
    mapPath, hostPeerId,
    mySessionToken: hostToken,
    mySlot: '0',
    sessionTokens, slots,
    slotConfigIndexes:   flat.indexes,
    slotConfigTypes:     flat.types,
    slotConfigRaces:     flat.races,
    slotConfigColors:    flat.colors,
    slotConfigTeams:     flat.teams,
    slotConfigHandicaps: flat.handicaps,
    fixedPlayerSettings: fps,
  };
}

/** Build a joiner-side start payload from a 'start-as-joiner' message
 *  received from the host over the reliable channel. The joiner takes
 *  fixedPlayerSettings from their own loaded mapInfo — they're playing
 *  the same map as the host, so the flag is identical. */
export function buildJoinerStartPayload(m: StartGameMsg): StartPayload {
  const sessionTokens: string[] = [];
  const slots: string[] = [];
  for (const t of Object.keys(m.sessionTokenToSlot)) {
    sessionTokens.push(t);
    slots.push(String(m.sessionTokenToSlot[t]));
  }
  const flat = flattenSlotConfigs(m.slotConfigs ?? []);
  const fps = state.mapInfo?.fixedPlayerSettings ? 'true' : 'false';
  return {
    kind: 'mp-start-as-joiner',
    mapPath: m.mapPath,
    hostPeerId: m.hostPeerId,
    mySessionToken: m.mySessionToken,
    mySlot: m.mySlot,
    sessionTokens, slots,
    slotConfigIndexes:   flat.indexes,
    slotConfigTypes:     flat.types,
    slotConfigRaces:     flat.races,
    slotConfigColors:    flat.colors,
    slotConfigTeams:     flat.teams,
    slotConfigHandicaps: flat.handicaps,
    fixedPlayerSettings: fps,
  };
}
