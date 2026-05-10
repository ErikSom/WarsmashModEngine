/**
 * Desync diagnostic modal. The engine worker postMessages two events:
 *   { kind: 'mp-desync-report', turn, peerHashes, localHashHex, localDump }
 *     — fires immediately on local detection. We open the modal in a
 *       loading state because the next event is the user-facing one.
 *   { kind: 'mp-desync-combined-report', turn, combinedReport }
 *     — server-aggregated dump from all clients. This is what the user
 *       copies and shares with the developers.
 *
 * The simulation has been halted server-side anyway, so the modal's
 * fixed-position "block everything" stance is fine — there's nothing
 * the user could do with the canvas underneath.
 */
import { useEffect, useState } from 'preact/hooks';

export interface DesyncReportPayload {
  turn?: number;
  combinedReport?: string;
  /** Lobby code, for the report header. Provided by the page. */
  lobbyCode?: string;
  /** netlib peer id of this client. */
  selfPeerId?: string;
}

interface Props {
  /** When undefined the overlay is hidden. When set with no
   *  combinedReport, shows the loading state. When combinedReport is
   *  populated, renders the full diagnostic. */
  payload: DesyncReportPayload | null;
  onReload: () => void;
}

const LOADING_MSG = 'Loading combined desync report from server...\n\n'
  + '(Game has been halted. The server is collecting state dumps from all '
  + 'clients to build a single comparable report. This usually arrives '
  + 'within a second.)';

export default function DesyncOverlay({ payload, onReload }: Props) {
  const [copyStatus, setCopyStatus] = useState('');

  if (payload === null) return null;

  const text = payload.combinedReport
    ? buildCombinedReportText(payload)
    : LOADING_MSG;

  const title = payload.combinedReport
    ? 'Multiplayer desync — combined report'
    : 'Multiplayer desync detected';

  async function onCopy(e: Event) {
    const ta = (e.currentTarget as HTMLElement).closest('.desync-overlay')?.querySelector('textarea');
    if (!ta) return;
    ta.focus();
    ta.select();
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(ta.value);
      }
      else {
        document.execCommand('copy');
      }
      setCopyStatus('copied!');
      setTimeout(() => setCopyStatus(''), 2000);
    }
    catch (err) {
      setCopyStatus('clipboard error: ' + (err instanceof Error ? err.message : String(err)));
    }
  }

  // Lock body scroll while the modal is open.
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  return (
    <div class="desync-overlay">
      <div class="desync-panel">
        <h2>{title}</h2>
        <p>
          The simulation has been halted because the game state diverged between players.
          This is a bug — please copy the report below and share it with the developers.
        </p>
        <div class="desync-buttons">
          <button class="primary" onClick={onCopy}>Copy report</button>
          <button class="danger" onClick={onReload}>Reload page</button>
          <span class="desync-copy-status">{copyStatus}</span>
        </div>
        <textarea readonly value={text} class="desync-textarea" />
      </div>
    </div>
  );
}

function buildCombinedReportText(p: DesyncReportPayload): string {
  const lines: string[] = [];
  lines.push('=== Warsmash multiplayer desync — combined report ===');
  lines.push('Generated: ' + new Date().toISOString());
  lines.push('Build path: ' + location.href);
  lines.push('User agent: ' + navigator.userAgent);
  lines.push('Lobby: ' + (p.lobbyCode || '(unknown)'));
  lines.push('This peer id: ' + (p.selfPeerId || '(unknown)'));
  lines.push('Game turn at desync: ' + (p.turn ?? '(unknown)'));
  lines.push('');
  lines.push(p.combinedReport || '(empty combined report)');
  return lines.join('\n');
}
