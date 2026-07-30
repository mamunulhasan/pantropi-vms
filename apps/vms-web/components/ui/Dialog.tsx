"use client";

/**
 * Modal dialog with the focus contract hand-rolled (US-06.2.1 AC-6, F-06.4).
 *
 * Hand-rolled rather than `<dialog>` on purpose: the ACs are about behaviour we must control and
 * test — trap, Escape, restore — and the native element's focus semantics still vary enough
 * across browsers (and jsdom) that owning the ~30 lines is cheaper than papering over them.
 *
 * The contract:
 * - opening moves focus into the dialog (`initialFocusRef`, else the first focusable);
 * - Tab and Shift+Tab cycle inside — the page behind is inert to the keyboard;
 * - Escape closes;
 * - closing returns focus to the element that opened it, so a keyboard user isn't dumped back
 *   at the top of the document.
 */
import { useEffect, useId, useRef, type ReactNode, type RefObject } from "react";
import { Button } from "./Button";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Dialog({
  open,
  onClose,
  title,
  children,
  initialFocusRef,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** Where focus lands on open. Defaults to the first focusable element. */
  initialFocusRef?: RefObject<HTMLElement | null>;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const titleId = useId();

  useEffect(() => {
    if (!open) {
      return;
    }
    openerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = panelRef.current;
    const target =
      initialFocusRef?.current ?? panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel;
    target?.focus();
    return () => {
      openerRef.current?.focus();
    };
  }, [open, initialFocusRef]);

  if (!open) {
    return null;
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab") {
      return;
    }
    const panel = panelRef.current;
    if (!panel) {
      return;
    }
    const focusables = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusables.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-scrim/50 p-4">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className="w-full max-w-md rounded-lg bg-surface-raised p-6 shadow-overlay"
      >
        <h2 id={titleId} className="text-lg font-semibold text-text">
          {title}
        </h2>
        {children}
      </div>
    </div>
  );
}

/**
 * The confirm variant (US-06.2.1 AC-6). The description must name the record — "Deactivate
 * building Westgate Tower?", never "Are you sure?" — the caller owns that sentence.
 *
 * Initial focus is the cancel button, explicitly: for a destructive action, Enter pressed a
 * beat too early must mean "keep it", so the destructive control is never the default focus.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  /** Names the record the action applies to. */
  description: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const cancelRef = useRef<HTMLButtonElement>(null);

  return (
    <Dialog open={open} onClose={onCancel} title={title} initialFocusRef={cancelRef}>
      <div className="mt-2 text-sm text-text-muted">{description}</div>
      <div className="mt-6 flex justify-end gap-3">
        <Button ref={cancelRef} variant="secondary" onClick={onCancel} disabled={busy}>
          {cancelLabel}
        </Button>
        <Button variant={destructive ? "danger" : "primary"} onClick={onConfirm} busy={busy}>
          {confirmLabel}
        </Button>
      </div>
    </Dialog>
  );
}
