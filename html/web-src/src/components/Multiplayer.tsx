/**
 * Top-level orchestrator for the /multiplayer route. Decides which
 * sub-view to render based on:
 *   1. Player name in localStorage   →  NameForm if missing
 *   2. ?lobby= query param           →  LobbyRoom (we joined directly)
 *   3. Current lobby state           →  LobbyRoom if in a lobby
 *   4. Default                       →  LobbyBrowser
 *
 * Handles soft routing: pushing/popping ?lobby= via history.pushState
 * so the back button works as "leave lobby" without tearing down the
 * WebRTC mesh (the page itself doesn't reload).
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { navigate } from 'astro:transitions/client';
import {
  getLobbyState, joinLobby, subscribeLobbyState, updateLobbyHostVersion,
} from '../lib/lobbyClient';
import { getPlayerName } from '../lib/playerName';
import LobbyBrowser from './LobbyBrowser';
import LobbyRoom from './LobbyRoom';
import { useGameVersion } from '../lib/useGameVersion';
import NameForm from './NameForm';

type View = 'name' | 'browser' | 'room';

function readLobbyFromUrl(): string | null {
  if (typeof window === 'undefined') return null;
  const params = new URLSearchParams(window.location.search);
  const code = params.get('lobby');
  return code && code.trim() ? code.trim() : null;
}

function setLobbyInUrl(code: string | null): void {
  const url = new URL(window.location.href);
  if (code) url.searchParams.set('lobby', code);
  else      url.searchParams.delete('lobby');
  window.history.pushState({}, '', url.toString());
}

export default function Multiplayer() {
  const [name, setName] = useState(getPlayerName());
  const [view, setView] = useState<View>(name ? 'browser' : 'name');
  const [showChangeName, setShowChangeName] = useState(false);

  // ---- Initial: if URL has ?lobby=ABCD and we're not in that lobby
  //               yet, join it. Otherwise sync view to current state.
  useEffect(() => {
    const desired = readLobbyFromUrl();
    const cur = getLobbyState();
    if (desired && cur.lobbyCode !== desired) {
      // Auto-join from a shared link. Doesn't render the room until
      // the lobby callback fires (state update flips view).
      joinLobby(desired).catch((e) => {
        console.warn('auto-join from url failed:', e);
        // Strip the bad code from the URL so refreshing doesn't loop.
        setLobbyInUrl(null);
      });
    }
    if (cur.lobbyCode) setView('room');
  }, []);

  // ---- Subscribe: lobby code presence drives view + URL sync ----
  useEffect(() => {
    return subscribeLobbyState((s) => {
      if (!name) { setView('name'); return; }
      if (s.lobbyCode) {
        setView('room');
        if (readLobbyFromUrl() !== s.lobbyCode) setLobbyInUrl(s.lobbyCode);
      }
      else {
        setView('browser');
        if (readLobbyFromUrl() !== null) setLobbyInUrl(null);
      }
    });
  }, [name]);

  // ---- Browser back/forward = soft nav between views ----
  useEffect(() => {
    function onPop() {
      const desired = readLobbyFromUrl();
      const cur = getLobbyState();
      if (desired && desired !== cur.lobbyCode) {
        // Forward to a different lobby — re-join.
        joinLobby(desired).catch(() => setLobbyInUrl(null));
      }
      else if (!desired && cur.lobbyCode) {
        // Back out of the lobby room.
        setView('browser');
        // Don't auto-leave the lobby — the user might just be browsing.
        // They can hit "Leave lobby" inside the room view if intended.
      }
    }
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  // Run the version detection once at this level so it's the same
  // cache across views — and so we can republish to the lobby if the
  // host's build resolves *after* createLobby was already called
  // (cold-cache fast-click case). The hook itself caches, so this
  // doesn't re-parse on every render.
  const { version, build, resolving } = useGameVersion('[multiplayer]');
  // Avoid spamming setLobbySettings: only publish a particular
  // (edition, build) once per session. Cleared on lobby leave by the
  // module's own currentHostVersion = null reset.
  const lastPublishedRef = useRef<string>('');
  useEffect(() => {
    if (resolving) return;                     // wait for resolution
    if (!getLobbyState().isHost) return;       // only host owns customData
    const fp = `${version.edition}|${build?.version ?? ''}`;
    if (fp === lastPublishedRef.current) return;
    lastPublishedRef.current = fp;
    updateLobbyHostVersion({
      edition: version.edition,
      build: build?.version ?? null,
    });
  }, [version, build, resolving]);

  function handleNameSaved(clean: string) {
    setName(clean);
    setView('browser');
  }

  function handleEnteredLobby(code: string) {
    setLobbyInUrl(code);
    setView('room');
  }

  function handleStartGame(_mode: 'host' | 'joiner') {
    // Hand off to /play via Astro's soft nav so the WebRTC mesh + lobby
    // module state survive the trip (the engine on /play reattaches via
    // attachEngineWorker once its worker is alive). LobbyRoom has
    // already populated lobbyClient's module-level pendingStartPayload
    // — /play reads it via consumeStartPayload().
    //
    // Path is "../play/" so the URL resolves against /multiplayer/'s
    // parent dir (i.e. /play/). Using "./play" would land on
    // /multiplayer/play and 404 — the bug fix from the lobby flow.
    navigate('../play/');
  }

  if (view === 'name') {
    return (
      <main class="multiplayer-page">
        <header class="mp-header">
          <h1>Multiplayer</h1>
        </header>
        <section class="mp-section">
          <NameForm
            initialName={name}
            title="Enter your name to continue"
            onSave={handleNameSaved}
          />
        </section>
      </main>
    );
  }

  if (view === 'room') {
    return (
      <LobbyRoom
        onLeft={() => { setLobbyInUrl(null); setView('browser'); }}
        onStartGame={handleStartGame}
      />
    );
  }

  return (
    <>
      <LobbyBrowser
        playerName={name}
        onEnteredLobby={handleEnteredLobby}
        onChangeName={() => setShowChangeName(true)}
      />
      {showChangeName && (
        <NameForm
          modal
          initialName={name}
          title="Change your name"
          onSave={(clean) => { setName(clean); setShowChangeName(false); }}
          onCancel={() => setShowChangeName(false)}
        />
      )}
    </>
  );
}
