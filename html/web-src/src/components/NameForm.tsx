/**
 * Player-name capture modal. Shown the first time the user visits
 * /multiplayer (or any time they explicitly want to change their
 * name). Persists to localStorage via the playerName lib.
 *
 * Validation is light — see lib/playerName.ts. The Save button stays
 * disabled until the input clears the minimum length, so we never
 * persist an unusable name.
 */
import { useEffect, useState } from 'preact/hooks';
import { isValidName, sanitize, setPlayerName } from '../lib/playerName';

interface Props {
  initialName?: string;
  /** Renders as a modal (with backdrop) when true; inline form when false. */
  modal?: boolean;
  /** Title shown at the top of the form. */
  title?: string;
  /** Called with the cleaned name once the user clicks Save. */
  onSave: (name: string) => void;
  /** Called when the user closes the modal without saving. Only meaningful
   *  when modal=true. Optional — first-visit forms don't need it. */
  onCancel?: () => void;
}

export default function NameForm({ initialName = '', modal = false, title = 'Enter your name', onSave, onCancel }: Props) {
  const [value, setValue] = useState(initialName);
  const valid = isValidName(value);

  useEffect(() => {
    if (!modal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && onCancel) onCancel();
      if (e.key === 'Enter' && valid) {
        const clean = setPlayerName(value);
        onSave(clean);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valid, value, modal]);

  function onSubmit(e: Event) {
    e.preventDefault();
    if (!valid) return;
    const clean = setPlayerName(value);
    onSave(clean);
  }

  const form = (
    <form class="name-form" onSubmit={onSubmit}>
      <h2>{title}</h2>
      <p>This name appears beside your slot in the lobby and in-game.</p>
      <input
        type="text"
        value={value}
        autofocus
        placeholder="e.g. Arthas"
        maxlength={24}
        onInput={(e) => setValue(sanitize((e.currentTarget as HTMLInputElement).value))}
      />
      <div class="name-form-actions">
        {onCancel && <button type="button" class="secondary" onClick={onCancel}>Cancel</button>}
        <button type="submit" class="primary" disabled={!valid}>Save</button>
      </div>
    </form>
  );

  if (!modal) return form;
  return (
    <div
      class="modal-backdrop"
      onClick={(e) => { if (e.target === e.currentTarget && onCancel) onCancel(); }}
    >
      {form}
    </div>
  );
}
