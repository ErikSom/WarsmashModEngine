/**
 * Generic confirm/cancel modal. Used right now for the kick-on-shrink
 * flow when the host picks a map with fewer slots than the lobby has
 * occupied; could grow into the place we hang any "are you sure?"
 * affordance. Renders nothing if `open === false`.
 */
import { useEffect } from 'preact/hooks';
import type { ComponentChildren } from 'preact';

interface Props {
  open: boolean;
  title: string;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children: ComponentChildren;
}

export default function ConfirmModal({
  open, title, confirmLabel = 'Confirm', cancelLabel = 'Cancel',
  destructive = false, onConfirm, onCancel, children,
}: Props) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
      if (e.key === 'Enter')  onConfirm();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (!open) return null;

  return (
    <div
      class="modal-backdrop"
      onClick={(e) => { if (e.target === e.currentTarget) onCancel(); }}
    >
      <div class="confirm-panel">
        <div class="confirm-title">{title}</div>
        <div class="confirm-body">{children}</div>
        <div class="confirm-actions">
          <button class="secondary" onClick={onCancel}>{cancelLabel}</button>
          <button class={destructive ? 'danger' : 'primary'} onClick={onConfirm}>{confirmLabel}</button>
        </div>
      </div>
    </div>
  );
}
