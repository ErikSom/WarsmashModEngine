/**
 * Inline clickable version label. Renders "v<VERSION>" as a button
 * that opens the changelog modal on click. Designed to drop into a
 * page footer (the landing page uses it) rather than float over a
 * fullscreen canvas — see CSS .version-chip for the inline styling.
 */
import { useState } from 'preact/hooks';
import { VERSION } from '../lib/version';
import ChangelogModal from './ChangelogModal';

export default function VersionChip() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        class="version-chip"
        title="View changelog"
        onClick={() => setOpen(true)}
      >
        v{VERSION}
      </button>
      {open && <ChangelogModal onClose={() => setOpen(false)} />}
    </>
  );
}
