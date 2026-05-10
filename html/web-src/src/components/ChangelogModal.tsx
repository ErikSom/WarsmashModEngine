/**
 * Changelog modal — fetches CHANGELOG.md once on first open, renders
 * it via the tiny markdown converter in lib/changelog.ts. Closing
 * keeps the parsed result in memory; subsequent opens are instant.
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { renderChangelog } from '../lib/changelog';
import { versionedAsset } from '../lib/version';

interface Props {
  onClose: () => void;
}

export default function ChangelogModal({ onClose }: Props) {
  const [body, setBody] = useState<string>('Loading…');
  const bodyRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    fetch(versionedAsset('CHANGELOG.md'), { cache: 'no-store' })
      .then(r => r.ok ? r.text() : Promise.reject(r.status))
      .then(text => setBody(renderChangelog(text)))
      .catch(err => setBody('Failed to load changelog: ' + err));

    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      class="modal-backdrop"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div class="changelog-panel">
        <div class="changelog-header">
          <div class="changelog-title">Changelog</div>
          <button class="secondary" onClick={onClose}>Close</button>
        </div>
        <div
          ref={bodyRef}
          class="changelog-body"
          // We trust our own markdown converter — content comes from
          // a hand-written file in this repo, not user input.
          // eslint-disable-next-line react/no-danger
          dangerouslySetInnerHTML={{ __html: body }}
        />
      </div>
    </div>
  );
}
