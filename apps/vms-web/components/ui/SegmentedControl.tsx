"use client";

/**
 * A one-of-N filter shown as a row of segments (UI-7, prototype `.seg`).
 *
 * The prototype filters with a segmented control where this codebase used a `<select>`. The
 * difference is worth having on a filter with four or five options: every choice is visible, so a
 * reader can see what the alternatives are without opening anything, and switching is one click.
 *
 * <strong>It is a radio group, not a row of buttons.</strong> Native radios give the whole
 * contract for free — arrow-key movement within the group, one tab stop for the group rather than
 * one per option, and the correct announcement ("Status, Approved, 3 of 5"). A `role="tablist"`
 * would promise panels that swap inside the page, and these filters navigate. The inputs are
 * visually hidden but focusable, so `:has(:focus-visible)` draws the ring on the visible segment.
 *
 * Use a `<select>` instead when the list is long or open-ended; this is for short, fixed sets.
 */
import { useId } from "react";

export type Segment<T extends string> = { value: T; label: string };

export function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
  name,
  disabled = false,
}: {
  /** Names the group for assistive technology; rendered visually unless `labelHidden`. */
  label: string;
  options: readonly Segment<T>[];
  value: T;
  onChange: (next: T) => void;
  /** Distinguishes this group's radios from any other on the page. Defaults to a generated id. */
  name?: string;
  disabled?: boolean;
}) {
  const generated = useId();
  const groupName = name ?? generated;

  return (
    <fieldset className="min-w-0" disabled={disabled}>
      <legend className="mb-1 text-sm font-medium text-text">{label}</legend>
      <div className="inline-flex flex-wrap rounded-md border border-border bg-surface-raised p-0.5">
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <label
              key={option.value}
              className={`cursor-pointer rounded px-3 py-1.5 text-sm has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-focus ${
                selected
                  ? "bg-brand font-medium text-brand-contrast"
                  : "text-text-muted hover:bg-surface-sunken hover:text-text"
              }`}
            >
              <input
                type="radio"
                name={groupName}
                value={option.value}
                checked={selected}
                onChange={() => onChange(option.value)}
                className="sr-only"
              />
              {option.label}
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
