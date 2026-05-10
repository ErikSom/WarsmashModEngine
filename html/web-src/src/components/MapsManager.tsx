/**
 * Manage Maps modal — list every .w3x/.w3m under /w3/Maps/, let the
 * user add new ones (uploaded into /w3/Maps/Upload/) and remove any
 * existing entry. Stock maps and uploaded maps are grouped separately.
 *
 * Opens from the play page's "Manage maps" button (visible once an
 * install is staged). Self-contained — no engine state involved; runs
 * entirely against OPFS.
 */
import { useEffect, useState } from 'preact/hooks';
import { addUploadedMaps, listAllMaps, removeMapByEntry, type MapEntry } from '../lib/opfs';

interface Props {
  onClose: () => void;
}

export default function MapsManager({ onClose }: Props) {
  const [entries, setEntries] = useState<MapEntry[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  async function refresh() {
    try {
      setError('');
      const list = await listAllMaps();
      setEntries(list);
    }
    catch (err) {
      setError('Failed to list maps: ' + msg(err));
    }
    finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function onAdd(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const files = Array.from(input.files || []);
    input.value = '';
    if (!files.length) return;
    setError('');
    try {
      const written = await addUploadedMaps(files);
      if (!written) setError('No .w3x or .w3m files in selection.');
      await refresh();
    }
    catch (err) {
      setError('Add maps failed: ' + msg(err));
    }
  }

  async function onRemove(entry: MapEntry) {
    try {
      await removeMapByEntry(entry);
      await refresh();
    }
    catch (err) {
      setError('Remove failed: ' + msg(err));
    }
  }

  const uploaded = entries.filter(e => e.isUpload);
  const stock = entries.filter(e => !e.isUpload);

  return (
    <div
      class="modal-backdrop"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div class="maps-panel">
        <div class="maps-header">
          <div class="maps-title">Manage maps</div>
          <button class="secondary" onClick={onClose}>Close</button>
        </div>

        <div class="maps-actions">
          <label class="fakebtn" for="maps-add-input">Add maps</label>
          <input
            id="maps-add-input"
            type="file"
            accept=".w3x,.w3m"
            multiple
            style={{ display: 'none' }}
            onChange={onAdd}
          />
          <span class="maps-info">
            {entries.length ? `${entries.length} map${entries.length === 1 ? '' : 's'}` : ''}
          </span>
        </div>

        <div class="maps-list">
          {loading && <div class="empty">Loading…</div>}
          {!loading && !entries.length && <div class="empty">No maps found in /w3/Maps/.</div>}
          {!!uploaded.length && <Section label="Uploaded" items={uploaded} onRemove={onRemove} />}
          {!!stock.length    && <Section label="Stock"    items={stock}    onRemove={onRemove} />}
        </div>

        {error && <div class="maps-error">{error}</div>}
      </div>
    </div>
  );
}

function Section({ label, items, onRemove }: { label: string; items: MapEntry[]; onRemove: (e: MapEntry) => void }) {
  return (
    <>
      <div class="group-header">{label} ({items.length})</div>
      {items.map(entry => {
        const subdir = entry.dirParts.length ? entry.dirParts.join('/') + '/' : '';
        return (
          <div class="row-item" key={entry.relPath}>
            <span class="name">
              {entry.name}
              <span class="path">{subdir}</span>
            </span>
            <button onClick={() => onRemove(entry)} class="row-remove">Remove</button>
          </div>
        );
      })}
    </>
  );
}

function msg(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
