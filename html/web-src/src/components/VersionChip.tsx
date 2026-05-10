/**
 * Bottom-right version chip. Click to open the changelog modal.
 * Lazy-loads the modal so the page doesn't pay for it unless the
 * user actually clicks.
 */
import { useState } from 'preact/hooks';
import { VERSION } from '../lib/version';
import ChangelogModal from './ChangelogModal';

export default function VersionChip() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <div
        class="version-chip"
        title="View changelog"
        onClick={() => setOpen(true)}
      >
        v{VERSION}
      </div>
      {open && <ChangelogModal onClose={() => setOpen(false)} />}
    </>
  );
}
