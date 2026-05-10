/**
 * Searchable map picker modal. Lists every .w3x/.w3m under OPFS
 * /w3/Maps/, parses each one's MPQ + war3map.w3i + war3map.wts, and
 * shows the real map name + author + player count. Filename + path
 * are kept as a subtitle so the host can still identify maps that
 * have empty or trigger-string-token names.
 *
 * Parsing is cached at the module level (lib/mapInfoCache), so the
 * first picker open is the only slow one — subsequent opens reuse
 * the parsed data. We render entries immediately with a "loading…"
 * placeholder per row, then upgrade to the real metadata as each
 * parse resolves. This keeps the picker responsive on first open.
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { listAllMaps, type MapEntry } from '../lib/opfs';
import { extractMapPlayerCount, fullMapPath, shortMapLabel } from '../lib/mapMeta';
import { getMapInfo } from '../lib/mapInfoCache';
import type { MapInfo } from '../lib/mapInfo';
import { stripWc3Color } from '../lib/wc3Color';
import ColoredText from './ColoredText';

interface Props {
  /** Path of the currently-selected map; used to highlight the row. */
  currentPath: string;
  /** Called with the engine-relative path (e.g. `Maps/FrozenThrone/(2)EchoIsles.w3x`). */
  onPick: (fullPath: string) => void;
  onClose: () => void;
}

interface RowData {
  entry: MapEntry;
  /** Parsed map info, null while loading, undefined on parse failure. */
  info: MapInfo | null | undefined;
}

export default function MapPicker({ currentPath, onPick, onClose }: Props) {
  const [rows, setRows] = useState<RowData[] | null>(null);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    listAllMaps()
      .then(async (entries) => {
        if (cancelled) return;
        // Initial render: every row is "loading".
        setRows(entries.map(entry => ({ entry, info: null })));

        // Kick off parses in parallel. Each row updates as its parse
        // resolves so the UI fills in progressively.
        await Promise.all(entries.map(async (entry) => {
          try {
            const info = await getMapInfo(entry.relPath);
            if (cancelled) return;
            setRows(prev => updateRow(prev, entry.relPath, info));
          }
          catch (e) {
            if (cancelled) return;
            console.warn('[MapPicker] parse failed for', entry.relPath, e);
            setRows(prev => updateRow(prev, entry.relPath, undefined));
          }
        }));
      })
      .catch((e) => setError('Failed to list maps: ' + msg(e)));

    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    setTimeout(() => inputRef.current?.focus(), 0);
    return () => {
      cancelled = true;
      document.removeEventListener('keydown', onKey);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filtered = useMemo(() => {
    if (!rows) return null;
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter(r => {
      // Strip WC3 inline color codes (|cAARRGGBB...|r) before
      // searching so users typing "Echo Isles" match a map named
      // "|cffffaa00Echo Isles|r" too.
      const fields = [
        r.entry.relPath,
        r.entry.name,
        r.info?.name ? stripWc3Color(r.info.name) : '',
        r.info?.author ? stripWc3Color(r.info.author) : '',
      ].map(s => s.toLowerCase());
      return fields.some(f => f.includes(q));
    });
  }, [rows, query]);

  function pick(entry: MapEntry) {
    onPick(fullMapPath(entry.relPath));
    onClose();
  }

  return (
    <div
      class="modal-backdrop"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div class="map-picker-panel">
        <div class="map-picker-header">
          <div class="map-picker-title">Choose a map</div>
          <button class="secondary" onClick={onClose}>Close</button>
        </div>

        <div class="map-picker-search">
          <input
            ref={inputRef}
            type="text"
            placeholder="Search by name, author, or path…"
            value={query}
            onInput={(e) => setQuery((e.currentTarget as HTMLInputElement).value)}
          />
        </div>

        <div class="map-picker-list">
          {rows === null && !error && <div class="map-picker-empty">Loading…</div>}
          {error && <div class="map-picker-error">{error}</div>}
          {filtered && filtered.length === 0 && (
            <div class="map-picker-empty">
              {rows?.length === 0
                ? 'No maps in OPFS yet. Stage your Warcraft III install or upload one under Manage assets.'
                : `No maps match "${query}".`}
            </div>
          )}
          {filtered && filtered.map(row => {
            const fullPath = fullMapPath(row.entry.relPath);
            const selected = fullPath === currentPath;
            return (
              <MapRow
                key={fullPath}
                row={row}
                selected={selected}
                onPick={() => pick(row.entry)}
              />
            );
          })}
        </div>
      </div>
    </div>
  );
}

function MapRow({ row, selected, onPick }: { row: RowData; selected: boolean; onPick: () => void }) {
  const { entry, info } = row;
  const subdir = entry.dirParts.length ? entry.dirParts.join('/') + '/' : '';
  const author = info?.author?.trim() || '';
  // Real player count from w3i; falls back to filename heuristic
  // before the parse resolves (or on parse failure — info===undefined).
  const players = info ? info.humanLikeSlots.length : extractMapPlayerCount(entry.name);
  const isLoading = info === null;
  const parseError = info === undefined;

  return (
    <button
      class={`map-picker-row ${selected ? 'is-selected' : ''}`}
      onClick={onPick}
    >
      <span class="map-picker-row-name">
        {/* Map authors often embed |cAARRGGBB...|r color codes in
            the name; render them as colored spans rather than as
            literal escape characters. */}
        {info?.name?.trim()
          ? <ColoredText text={info.name} />
          : shortMapLabel(entry.name)}
        {entry.isUpload && <span class="map-picker-row-tag">uploaded</span>}
        {parseError && <span class="map-picker-row-tag map-picker-row-tag-err">unparsable</span>}
      </span>
      <span class="map-picker-row-meta">
        {author && <span class="map-picker-row-author">by <ColoredText text={author} /></span>}
        <span class="map-picker-row-path">{subdir}{entry.name}</span>
        <span class="map-picker-row-players">
          {isLoading ? '…' : `${players} players`}
        </span>
      </span>
    </button>
  );
}

/** Immutably update a single row's `info` field, keyed by entry.relPath. */
function updateRow(prev: RowData[] | null, relPath: string, info: MapInfo | undefined): RowData[] | null {
  if (!prev) return prev;
  return prev.map(r => r.entry.relPath === relPath ? { ...r, info } : r);
}

function msg(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
