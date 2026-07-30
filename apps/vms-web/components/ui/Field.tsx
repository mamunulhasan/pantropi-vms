"use client";

/**
 * Form field with the accessibility contract built in (US-06.2.1, F-06.4).
 *
 * The rules every screen gets for free by using this instead of a bare `<input>`:
 * - the label is programmatically associated (`htmlFor`/`id`), so clicking it focuses the
 *   control and a screen reader announces it;
 * - the error renders adjacent to the control and is referenced by `aria-describedby`, so the
 *   announcement carries the reason, not just "invalid";
 * - `aria-invalid` marks the control, which is also what {@link focusFirstInvalid} finds.
 *
 * The control is whatever renders inside — {@link Input}, {@link Select} and {@link Textarea}
 * read the wiring from context, so the field never needs ids threaded through by hand.
 */
import {
  createContext,
  useContext,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";

type FieldWiring = {
  controlId: string;
  describedBy: string | undefined;
  invalid: boolean;
};

const FieldContext = createContext<FieldWiring | null>(null);

function useFieldWiring(component: string): FieldWiring {
  const wiring = useContext(FieldContext);
  if (!wiring) {
    throw new Error(`<${component}> must render inside a <Field>`);
  }
  return wiring;
}

export function Field({
  label,
  error,
  hint,
  required = false,
  className,
  children,
}: {
  label: string;
  /** The validation message. Presence marks the control invalid. */
  error?: string | null;
  hint?: string;
  required?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(" ") || undefined;

  return (
    <div className={className}>
      <label htmlFor={id} className="block text-sm font-medium text-text">
        {label}
        {required && (
          // Decorative: required-ness reaches assistive tech through the control's own
          // `required` attribute, not through a bare asterisk read aloud as "star".
          <span aria-hidden="true" className="text-danger">
            {" "}
            *
          </span>
        )}
      </label>
      {hint && (
        <p id={hintId} className="mt-1 text-sm text-text-muted">
          {hint}
        </p>
      )}
      <FieldContext.Provider value={{ controlId: id, describedBy, invalid: Boolean(error) }}>
        {children}
      </FieldContext.Provider>
      {error && (
        <p id={errorId} role="alert" className="mt-1 text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

const CONTROL_CLASSES =
  "mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  const f = useFieldWiring("Input");
  return (
    <input
      id={f.controlId}
      aria-describedby={f.describedBy}
      aria-invalid={f.invalid || undefined}
      className={CONTROL_CLASSES}
      {...props}
    />
  );
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  const f = useFieldWiring("Select");
  return (
    <select
      id={f.controlId}
      aria-describedby={f.describedBy}
      aria-invalid={f.invalid || undefined}
      className={CONTROL_CLASSES}
      {...props}
    />
  );
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const f = useFieldWiring("Textarea");
  return (
    <textarea
      id={f.controlId}
      aria-describedby={f.describedBy}
      aria-invalid={f.invalid || undefined}
      className={CONTROL_CLASSES}
      {...props}
    />
  );
}

/**
 * Move focus to the first invalid control (US-06.2.1: focus-first-invalid on submit).
 *
 * Called by a screen after a failed submit has rendered its errors — the invalid marker this
 * looks for is the one <Field error=…> sets, so the two halves cannot drift apart.
 */
export function focusFirstInvalid(root: HTMLElement): boolean {
  const invalid = root.querySelector<HTMLElement>('[aria-invalid="true"]');
  if (invalid) {
    invalid.focus();
    return true;
  }
  return false;
}
