/**
 * Public lobby browser. Shows everyone's `net.list()` output as cards
 * with map name + player count + host name + age. Plus a "Host new
 * lobby" button and a "Join by code" form for private games.
 *
 * Refreshes on a 5-second timer (cheap signaling-server query) and
 * on demand via the Refresh button. Listing is read-only — actually
 * joining triggers `joinLobby` which navigates the parent component
 * into the lobby room view.
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import {
  createLobby, joinLobby, listPublicLobbies, getLobbyState,
} from '../lib/lobbyClient';
import type { PublicLobbyEntry } from '../lib/poki-bridge';

interface Props {
  playerName: string;
  /** Called once we've confirmed the host has been created or joined.
   *  Parent navigates to the lobby room view (URL update + render). */
  onEnteredLobby: (code: string) => void;
  onChangeName: () => void;
}

const REFRESH_INTERVAL_MS = 5000;
const DEFAULT_MAP = 'Maps/FrozenThrone/(2)EchoIsles.w3x';

export default function LobbyBrowser({ playerName, onEnteredLobby, onChangeName }: Props) {
  const [entries, setEntries] = useState<PublicLobbyEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [joinCode, setJoinCode] = useState('');
  const [hosting, setHosting] = useState(false);
  const [bridgeReady, setBridgeReady] = useState(getLobbyState().ready);
  const refreshTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  async function refresh() {
    if (!getLobbyState().ready) {
      setBridgeReady(false);
      return;
    }
    setBridgeReady(true);
    try {
      const list = await listPublicLobbies();
      setEntries(list);
      setError('');
    }
    catch (e) {
      setError('Failed to list lobbies: ' + msg(e));
    }
    finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    refreshTimer.current = setInterval(refresh, REFRESH_INTERVAL_MS);
    return () => {
      if (refreshTimer.current) clearInterval(refreshTimer.current);
    };
  }, []);

  // Poll lobby-state readiness so we don't spin forever if netlib
  // hadn't connected by first refresh. (Quick signal-store check
  // every 500ms — tiny overhead, big UX win on slow networks.)
  useEffect(() => {
    if (bridgeReady) return;
    const t = setInterval(() => {
      if (getLobbyState().ready) {
        setBridgeReady(true);
        clearInterval(t);
        refresh();
      }
    }, 500);
    return () => clearInterval(t);
  }, [bridgeReady]);

  async function onHost() {
    setHosting(true);
    setError('');
    try {
      const code = await createLobby({ mapPath: DEFAULT_MAP });
      onEnteredLobby(code);
    }
    catch (e) {
      setError('Host failed: ' + msg(e));
      setHosting(false);
    }
  }

  async function onJoinByCode(e: Event) {
    e.preventDefault();
    const code = joinCode.trim();
    if (!code) return;
    setError('');
    try {
      await joinLobby(code);
      onEnteredLobby(code);
    }
    catch (err) {
      setError('Join failed: ' + msg(err));
    }
  }

  async function onJoinEntry(entry: PublicLobbyEntry) {
    setError('');
    try {
      await joinLobby(entry.code);
      onEnteredLobby(entry.code);
    }
    catch (e) {
      setError('Join failed: ' + msg(e));
    }
  }

  return (
    <main class="multiplayer-page">
      <header class="mp-header">
        <h1>Multiplayer</h1>
        <div class="mp-header-actions">
          <span class="mp-name">
            Playing as <strong>{playerName}</strong>
            <button class="link-btn" onClick={onChangeName}>change</button>
          </span>
          <a href="../" class="secondary btn">Home</a>
        </div>
      </header>

      {!bridgeReady && (
        <div class="mp-status-banner">Connecting to signaling server…</div>
      )}

      <section class="mp-section">
        <div class="mp-section-header">
          <h2>Host a game</h2>
        </div>
        <p class="mp-section-hint">
          Create a new public lobby. Other players will see it in their browser
          immediately, or you can share the lobby code for private games.
        </p>
        <button class="primary" onClick={onHost} disabled={!bridgeReady || hosting}>
          {hosting ? 'Creating lobby…' : 'Host new lobby'}
        </button>
      </section>

      <section class="mp-section">
        <div class="mp-section-header">
          <h2>Join by code</h2>
        </div>
        <form class="mp-join-form" onSubmit={onJoinByCode}>
          <input
            type="text"
            placeholder="lobby code"
            maxlength={20}
            value={joinCode}
            onInput={(e) => setJoinCode((e.currentTarget as HTMLInputElement).value.trim())}
          />
          <button type="submit" class="primary" disabled={!bridgeReady || !joinCode}>Join</button>
        </form>
      </section>

      <section class="mp-section">
        <div class="mp-section-header">
          <h2>Public lobbies</h2>
          <button class="secondary mp-refresh" onClick={refresh} disabled={!bridgeReady}>Refresh</button>
        </div>
        {/* Hide orphans (host disconnected, peer record GC'd by the
            signaling server but lobby record lingers). They aren't
            joinable in any meaningful way and we can't reclaim them
            without an upstream netlib API. */}
        {(() => {
          const visible = entries.filter(e => e.playerCount > 0);
          if (loading) return <p class="mp-empty">Loading…</p>;
          if (!bridgeReady) return null;
          if (!visible.length) return <p class="mp-empty">No public lobbies right now. Be the first — host one!</p>;
          return (
            <ul class="mp-lobbies">
              {visible.map(entry => (
                <li class="mp-lobby-card" key={entry.code}>
                  <div class="mp-lobby-main">
                    <div class="mp-lobby-line1">
                      <span class="mp-lobby-map">{shortMap(entry.customData?.mapPath) || 'Unknown map'}</span>
                      <span class="mp-lobby-host">hosted by {entry.customData?.hostName || 'Anonymous'}</span>
                    </div>
                    <div class="mp-lobby-line2">
                      <code class="mp-lobby-code">{entry.code}</code>
                      <span class="mp-lobby-count">{entry.playerCount} / {entry.maxPlayers || '∞'} players</span>
                      <span class="mp-lobby-age">{ageOf(entry.createdAt)}</span>
                    </div>
                  </div>
                  <button
                    class="primary"
                    onClick={() => onJoinEntry(entry)}
                    disabled={entry.playerCount >= (entry.maxPlayers || 99)}
                  >
                    Join
                  </button>
                </li>
              ))}
            </ul>
          );
        })()}
        {error && <div class="mp-error">{error}</div>}
      </section>
    </main>
  );
}

function shortMap(path: string | undefined): string {
  if (!path) return '';
  // Strip "Maps/" prefix and the .w3x/.w3m suffix for a friendlier label.
  return path.replace(/^Maps\//, '').replace(/\.(w3x|w3m)$/i, '');
}

function ageOf(iso: string): string {
  const created = new Date(iso).getTime();
  if (Number.isNaN(created)) return '';
  const ms = Date.now() - created;
  if (ms < 60_000)        return 'just now';
  if (ms < 3_600_000)     return `${Math.floor(ms / 60_000)}m ago`;
  if (ms < 86_400_000)    return `${Math.floor(ms / 3_600_000)}h ago`;
  return `${Math.floor(ms / 86_400_000)}d ago`;
}

function msg(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
