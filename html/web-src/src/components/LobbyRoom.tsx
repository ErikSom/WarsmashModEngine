/**
 * In-lobby view. Owns the slot list (the headline UX), the map
 * picker entry point, and the start-game handshake. Subscribes to
 * lobbyClient state via subscribeLobbyState — which is the single
 * source of truth for slot occupancy + leadership.
 *
 * Slot model:
 *   - Each map declares N player slots (parsed from the filename
 *     `(N)MapName.w3x` convention via lib/mapMeta).
 *   - Host auto-assigns each new peer to the first open slot.
 *   - Anyone can click an empty slot to claim it (joiners send a
 *     claim-slot request to the host; host applies + broadcasts).
 *   - Host can kick any non-self peer via the X button.
 *   - Switching to a smaller map shows a confirm dialog listing
 *     who'd be kicked.
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import {
  applyMapChange, buildHostStartPayload, buildJoinerStartPayload,
  getLobbyState, isSlotJoinable, kickPeer, leaveLobby, onStartFromHost,
  planMapChange, requestSlot, setStartPayload, startGame,
  subscribeLobbyState, updateSlot,
  type LobbyPlayer, type LobbyState, type LobbySlot, type MapChangePlan,
} from '../lib/lobbyClient';
import { PlayerRace, PlayerType, raceLabel, type MapInfo } from '../lib/mapInfo';
import { shortMapLabel } from '../lib/mapMeta';
import { HANDICAP_VALUES, PLAYER_COLORS, colorById } from '../lib/playerColors';
import { stripWc3Color } from '../lib/wc3Color';
import ColoredText from './ColoredText';
import ConfirmModal from './ConfirmModal';
import MapPicker from './MapPicker';

const RACE_PICKER_OPTIONS: PlayerRace[] = [
  PlayerRace.Selectable,
  PlayerRace.Human,
  PlayerRace.Orc,
  PlayerRace.Undead,
  PlayerRace.NightElf,
];

interface Props {
  /** Called when the user leaves the lobby (or netlib emits 'left').
   *  Parent navigates back to the browser view. */
  onLeft: () => void;
  /** Called once the start handshake has fired. Parent navigates to
   *  /play with the payload preserved on the lobby module. */
  onStartGame: (mode: 'host' | 'joiner') => void;
}

export default function LobbyRoom({ onLeft, onStartGame }: Props) {
  const [state, setState] = useState<LobbyState>(getLobbyState());
  const [copied, setCopied] = useState(false);
  const [starting, setStarting] = useState(false);
  const [showMapPicker, setShowMapPicker] = useState(false);
  /** Pending map change that would kick people — held until the host
   *  confirms via the modal. Null when no confirmation is pending. */
  const [pendingMapChange, setPendingMapChange] = useState<MapChangePlan | null>(null);
  /** Surfacing of map-load failures for the "Change map" path. */
  const [mapLoadError, setMapLoadError] = useState('');
  /** Pending peer kick — held until host confirms. */
  const [pendingKick, setPendingKick] = useState<LobbyPlayer | null>(null);

  // Subscribe to lobby-client state. The re-sync inside the effect
  // closes a mount-time race: useState's initializer ran during the
  // first render, but subscribeLobbyState only registers after the
  // effect runs (post-paint). Any setState that fires between those
  // two — like createLobby's resolve callback writing the parsed
  // mapInfo into the lobby module — would otherwise be missed, so
  // the preview/etc. wouldn't show until the NEXT state change.
  useEffect(() => {
    setState(getLobbyState());
    return subscribeLobbyState(setState);
  }, []);

  // If a start-as-joiner control message arrives, hand off.
  useEffect(() => {
    return onStartFromHost((m) => {
      setStartPayload(buildJoinerStartPayload(m));
      onStartGame('joiner');
    });
  }, [onStartGame]);

  // If we're suddenly out of a lobby (left event), notify parent.
  useEffect(() => {
    if (state.lobbyCode === null) onLeft();
  }, [state.lobbyCode, onLeft]);

  if (state.lobbyCode === null) {
    return <div class="multiplayer-page"><p>Leaving lobby…</p></div>;
  }

  async function onCopy() {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(state.lobbyCode!);
      }
      else {
        const ta = document.createElement('textarea');
        ta.value = state.lobbyCode!;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    }
    catch (e) {
      console.warn('clipboard failed', e);
    }
  }

  async function handleMapPicked(mapPath: string) {
    if (mapPath === state.selectedMap) return;
    if (!state.isHost) return;
    setMapLoadError('');
    let plan: MapChangePlan;
    try {
      plan = await planMapChange(mapPath);
    }
    catch (e) {
      setMapLoadError('Failed to load map: ' + (e instanceof Error ? e.message : String(e)));
      return;
    }
    if (plan.surplus.length > 0) {
      setPendingMapChange(plan);
      return;
    }
    applyMapChange(plan);
  }

  function confirmMapChange() {
    if (!pendingMapChange) return;
    applyMapChange(pendingMapChange);
    setPendingMapChange(null);
  }

  function confirmKick() {
    if (!pendingKick) return;
    kickPeer(pendingKick.peerId);
    setPendingKick(null);
  }

  async function onStart() {
    if (!state.isHost) return;
    const occupied = state.slots.filter(s => s.occupant !== null).length;
    if (occupied < 2) return;  // need at least one peer
    setStarting(true);
    const result = startGame();
    if (result) {
      setStartPayload(buildHostStartPayload(
        result.mapPath, result.selfId, result.hostToken, result.sessionTokens,
        result.slotConfigs,
      ));
      onStartGame('host');
    }
    else {
      setStarting(false);
    }
  }

  const occupiedCount = state.slots.filter(s => s.occupant !== null).length;
  const peerCount = occupiedCount - (state.isHost ? 1 : 0);
  const canStart = state.isHost && peerCount >= 1;

  return (
    <main class="multiplayer-page">
      <header class="mp-header">
        <h1>Lobby</h1>
        <div class="mp-header-actions">
          <button class="secondary" onClick={leaveLobby}>Leave lobby</button>
        </div>
      </header>

      <section class="mp-section">
        <div class="mp-lobby-code-block">
          <span class="mp-section-hint">Lobby code (share to invite):</span>
          <code class={`mp-lobby-code-big ${copied ? 'copied' : ''}`} onClick={onCopy} title="Click to copy">
            {state.lobbyCode}
          </code>
          {copied && <span class="mp-copied">copied!</span>}
        </div>
        {state.lastError && <div class="mp-error">{state.lastError}</div>}
      </section>

      <section class="mp-section">
        <div class="mp-section-header">
          <h2>Map</h2>
          {state.isHost && (
            <button class="secondary" onClick={() => setShowMapPicker(true)}>Change map</button>
          )}
        </div>
        <div class="mp-map-readout">
          {state.mapInfo?.previewUrl && (
            <img class="mp-map-preview" src={state.mapInfo.previewUrl} alt="" />
          )}
          <div class="mp-map-readout-main">
            <div class="mp-map-readout-name">
              {state.mapInfo?.name?.trim()
                ? <ColoredText text={state.mapInfo.name} />
                : shortMapLabel(state.selectedMap)}
            </div>
            {state.mapInfo?.author?.trim() && (
              <div class="mp-map-readout-author">
                by <ColoredText text={state.mapInfo.author} />
              </div>
            )}
            {state.mapInfo?.recommendedPlayers?.trim() && (
              <div class="mp-map-readout-recommended">
                <ColoredText text={state.mapInfo.recommendedPlayers} />
              </div>
            )}
          </div>
          <div class="mp-map-readout-meta">
            <span class="mp-map-players">{state.maxPlayers} players</span>
            {state.mapInfo?.tileset && <span class="mp-map-tileset">{tilesetLabel(state.mapInfo.tileset)}</span>}
          </div>
        </div>
        {state.mapInfo?.description?.trim() && (
          <p class="mp-map-description">
            <ColoredText text={state.mapInfo.description} />
          </p>
        )}
        {mapLoadError && <div class="mp-error">{mapLoadError}</div>}
      </section>

      <section class="mp-section">
        <div class="mp-section-header">
          <h2>Slots ({occupiedCount} / {state.maxPlayers})</h2>
        </div>
        <SlotsByForce
          state={state}
          onKick={(player) => setPendingKick(player)}
        />
      </section>

      <section class="mp-section mp-start-section">
        {state.isHost ? (
          <>
            <button class="primary mp-start-btn" onClick={onStart} disabled={!canStart || starting}>
              {starting ? 'Starting…' : `Start game (${occupiedCount} player${occupiedCount === 1 ? '' : 's'})`}
            </button>
            {peerCount === 0 && (
              <div class="mp-section-hint mp-warn">
                Need at least one other player to start. Share the lobby code above.
              </div>
            )}
          </>
        ) : (
          <div class="mp-section-hint">Waiting for the host to start the game…</div>
        )}
      </section>

      {showMapPicker && (
        <MapPicker
          currentPath={state.selectedMap}
          onPick={handleMapPicked}
          onClose={() => setShowMapPicker(false)}
        />
      )}

      <ConfirmModal
        open={pendingMapChange !== null}
        title="Change map?"
        confirmLabel="Change & kick"
        destructive
        onConfirm={confirmMapChange}
        onCancel={() => setPendingMapChange(null)}
      >
        <p>
          Switching to <strong>
            {pendingMapChange?.mapInfo.name?.trim()
              ? stripWc3Color(pendingMapChange.mapInfo.name)
              : shortMapLabel(pendingMapChange?.mapPath ?? '')}
          </strong> reduces the lobby to fewer slots. The following players will be removed:
        </p>
        <ul class="confirm-name-list">
          {(pendingMapChange?.surplus ?? []).map(peerId => {
            const name = state.players.find(p => p.peerId === peerId)?.name ?? peerId;
            return <li key={peerId}>{name}</li>;
          })}
        </ul>
      </ConfirmModal>

      <ConfirmModal
        open={pendingKick !== null}
        title="Kick player?"
        confirmLabel="Kick"
        destructive
        onConfirm={confirmKick}
        onCancel={() => setPendingKick(null)}
      >
        <p>
          Remove <strong>{pendingKick?.name ?? ''}</strong> from this lobby? They'll be
          disconnected and have to re-join with the lobby code.
        </p>
      </ConfirmModal>
    </main>
  );
}

/** Render slots either as a flat list (FFA / no forces) or grouped
 *  under each force's name. Slots that aren't claimed by any force —
 *  including ones the user moved to "No team" — fall into a trailing
 *  "Unassigned" group. Force ordering matches the map's w3i. */
function SlotsByForce({
  state,
  onKick,
}: { state: LobbyState; onKick: (p: LobbyPlayer) => void }) {
  const forces = state.mapInfo?.forces ?? [];
  if (forces.length === 0) {
    return (
      <ul class="mp-slots">
        {state.slots.map(slot => (
          <SlotRow
            key={slot.index}
            slot={slot}
            state={state}
            mapInfo={state.mapInfo}
            onClaim={() => requestSlot(slot.index)}
            onKick={onKick}
          />
        ))}
      </ul>
    );
  }

  const orphan = state.slots.filter(s => s.team < 0 || s.team >= forces.length);
  return (
    <>
      {forces.map((force, forceIdx) => {
        const slotsInForce = state.slots.filter(s => s.team === forceIdx);
        if (slotsInForce.length === 0) return null;
        return (
          <div class="mp-force-group" key={forceIdx}>
            <div class="mp-force-header">{force.name?.trim() || `Force ${forceIdx + 1}`}</div>
            <ul class="mp-slots">
              {slotsInForce.map(slot => (
                <SlotRow
                  key={slot.index}
                  slot={slot}
                  state={state}
                  mapInfo={state.mapInfo}
                  onClaim={() => requestSlot(slot.index)}
                  onKick={onKick}
                />
              ))}
            </ul>
          </div>
        );
      })}
      {orphan.length > 0 && (
        <div class="mp-force-group">
          <div class="mp-force-header">Unassigned</div>
          <ul class="mp-slots">
            {orphan.map(slot => (
              <SlotRow
                key={slot.index}
                slot={slot}
                state={state}
                mapInfo={state.mapInfo}
                onClaim={() => requestSlot(slot.index)}
                onKick={onKick}
              />
            ))}
          </ul>
        </div>
      )}
    </>
  );
}

interface SlotRowProps {
  slot: LobbySlot;
  state: LobbyState;
  mapInfo: MapInfo | null;
  onClaim: () => void;
  onKick: (player: LobbyPlayer) => void;
}

function SlotRow({ slot, state, mapInfo, onClaim, onKick }: SlotRowProps) {
  const occupant = slot.occupant
    ? state.players.find(p => p.peerId === slot.occupant)
    : null;
  const isSelf = slot.occupant === state.selfId;
  const isHost = slot.occupant !== null && slot.occupant === state.leaderId;
  const isEmpty = slot.occupant === null;
  const isClosed = slot.type === 'closed';
  const isHostMe = state.isHost;
  const canKick = isHostMe && !isSelf && occupant !== null && occupant !== undefined;

  // The map may declare this slot as a Computer (AI) — in fixed-
  // settings UMS maps that's a hard lock, in melee maps it's just a
  // suggestion the lobby can override. We mirror the WC3 lobby's
  // behavior: only treat Computer-typed slots as "predetermined AI"
  // when the map's fixed-player-settings flag is set.
  const mapSlot = mapInfo?.players.find(p => p.id === slot.index);
  const slotLabel = mapSlot?.name?.trim() || `Slot ${slot.index + 1}`;
  const racesLocked = mapInfo?.fixedPlayerSettings ?? false;
  const isMapComputer = mapSlot?.type === PlayerType.Computer && racesLocked;
  const canClaim = !isMapComputer && isSlotJoinable(slot, mapInfo) && isEmpty;
  // Permission to mutate this slot's fields: the occupant or the
  // host. Empty/closed/computer slots are read-only.
  const canEditFields = !isEmpty && (isSelf || isHostMe) && !isMapComputer;
  const canEditRace = canEditFields && !racesLocked;

  function changeRace(e: Event)     { updateSlot(slot.index, { race: parseInt((e.currentTarget as HTMLSelectElement).value, 10) }); }
  function changeHandicap(e: Event) { updateSlot(slot.index, { handicap: parseInt((e.currentTarget as HTMLSelectElement).value, 10) }); }
  function toggleType()             { updateSlot(slot.index, { slotType: isClosed ? 'open' : 'closed' }); }

  const color = colorById(slot.color);

  // In-use colors (excluding our own current pick) are flagged in
  // the palette so users can see what's available without trying
  // and being silently rejected by the host.
  const inUseColors = new Set(
    state.slots
      .filter(s => s.index !== slot.index)
      .map(s => s.color)
  );

  return (
    <li class={`mp-slot ${isSelf ? 'is-self' : ''} ${isHost ? 'is-host' : ''} ${isEmpty ? 'is-open' : ''} ${isClosed ? 'is-closed' : ''} ${isMapComputer ? 'is-computer' : ''}`}>
      {/* Row 1: identity + tags + actions. Anything beyond the
          flexible name area shrinks gracefully on narrow widths. */}
      <div class="mp-slot-header">
        <span class="mp-slot-num">{slot.index + 1}</span>
        {occupant || isMapComputer
          ? <span class="mp-slot-color-dot" style={{ background: color.hex }} title={color.name} />
          : <span class="mp-slot-color-dot mp-slot-color-dot-open" />}
        <span class="mp-slot-identity">
          {occupant ? (
            <span class="mp-slot-name" title={occupant.name}>{occupant.name}</span>
          ) : isMapComputer ? (
            <span class="mp-slot-empty">Computer (Normal)</span>
          ) : (
            <span class="mp-slot-empty">{isClosed ? '— closed —' : '— open —'}</span>
          )}
          <span class="mp-slot-mapname">({slotLabel})</span>
        </span>
        <span class="mp-slot-tags">
          {isHost  && <span class="mp-slot-tag">host</span>}
          {isSelf  && <span class="mp-slot-tag mp-slot-tag-self">you</span>}
          {isMapComputer && <span class="mp-slot-tag">computer</span>}
        </span>
        <span class="mp-slot-actions">
          {canClaim && (
            <button class="primary mp-slot-claim" onClick={onClaim}>Claim</button>
          )}
          {isHostMe && isEmpty && !isMapComputer && (
            <button
              class="mp-slot-toggle"
              title={isClosed ? 'Open this slot' : 'Close this slot'}
              onClick={toggleType}
            >
              {isClosed ? 'Open' : 'Close'}
            </button>
          )}
          {canKick && occupant && (
            <button class="mp-slot-kick" title="Kick player" onClick={() => onKick(occupant)}>×</button>
          )}
        </span>
      </div>

      {/* Row 2: per-slot pickers — race, color cube, handicap. Team
          is implicit from the force-grouped layout above. Labels
          dropped since each picker's value is self-explanatory. */}
      <div class="mp-slot-pickers">
        <select
          class="mp-slot-select"
          disabled={!canEditRace}
          value={String(slot.race)}
          onChange={changeRace}
          title={racesLocked ? 'Race locked by the map' : 'Race'}
        >
          {RACE_PICKER_OPTIONS.map(r => (
            <option value={String(r)} key={r}>{raceLabel(r)}</option>
          ))}
        </select>

        <ColorPicker
          slotColor={slot.color}
          inUseColors={inUseColors}
          disabled={!canEditFields}
          onChange={(id) => updateSlot(slot.index, { color: id })}
        />

        <select
          class="mp-slot-select mp-slot-handicap"
          disabled={!canEditFields}
          value={String(slot.handicap)}
          onChange={changeHandicap}
          title="Handicap"
        >
          {HANDICAP_VALUES.map(h => (
            <option value={String(h)} key={h}>{h}%</option>
          ))}
        </select>
      </div>
    </li>
  );
}

/** Color picker — current color rendered as a clickable cube; click
 *  opens a small grid of cubes with all 12 WC3 colors. In-use colors
 *  (occupied by another slot) are visibly dimmed and refuse clicks.
 *  Closes on outside click. */
function ColorPicker({
  slotColor, inUseColors, disabled, onChange,
}: {
  slotColor: number;
  inUseColors: Set<number>;
  disabled: boolean;
  onChange: (id: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onMouseDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [open]);

  const current = colorById(slotColor);
  return (
    <div class="mp-color-pick" ref={ref}>
      <button
        type="button"
        class="mp-color-cube mp-color-cube-trigger"
        style={{ background: current.hex }}
        title={current.name}
        disabled={disabled}
        onClick={() => setOpen(o => !o)}
      />
      {open && (
        <div class="mp-color-palette" role="listbox">
          {PLAYER_COLORS.map(c => {
            const taken = inUseColors.has(c.id) && c.id !== slotColor;
            const isCurrent = c.id === slotColor;
            return (
              <button
                type="button"
                key={c.id}
                role="option"
                aria-selected={isCurrent}
                class={`mp-color-cube ${taken ? 'is-taken' : ''} ${isCurrent ? 'is-current' : ''}`}
                style={{ background: c.hex }}
                title={taken ? `${c.name} (in use)` : c.name}
                disabled={taken}
                onClick={() => { onChange(c.id); setOpen(false); }}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}

/** Single-character WC3 tileset codes mapped to friendly names. */
function tilesetLabel(tileset: string): string {
  const map: Record<string, string> = {
    A: 'Ashenvale', B: 'Barrens', C: 'Felwood', D: 'Dungeon', F: 'Lordaeron Fall',
    G: 'Underground', I: 'Icecrown', J: 'Dalaran Ruins', K: 'Sunken Ruins',
    L: 'Lordaeron Summer', N: 'Northrend', O: 'Outland', P: 'Village',
    Q: 'Village Fall', V: 'Dalaran', W: 'Lordaeron Winter', X: 'Black Citadel',
    Y: 'Cityscape', Z: 'Ruins',
  };
  return map[tileset] ?? tileset;
}
