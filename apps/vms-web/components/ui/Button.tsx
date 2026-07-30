"use client";

/**
 * The button (US-06.2.1). One place for the token-styled variants, so a screen never invents
 * its own destructive red or forgets the disabled treatment.
 *
 * `busy` is the submit-in-flight state: the control disables (no double submit) and announces
 * `aria-busy` rather than silently swallowing clicks.
 */
import { forwardRef, type ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "danger" | "ghost";

const VARIANT_CLASSES: Record<Variant, string> = {
  primary: "bg-brand text-brand-contrast hover:bg-brand-hover",
  secondary: "border border-border bg-surface text-text hover:bg-surface-sunken",
  danger: "bg-danger text-danger-contrast hover:opacity-90",
  ghost: "text-text hover:bg-surface-sunken",
};

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  busy?: boolean;
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", busy = false, disabled, className, children, type = "button", ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      // Default "button", not the HTML default "submit" — an accidental form submit from a
      // dialog's cancel button is exactly the bug this component exists to make impossible.
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`rounded-md px-4 py-2 text-sm font-medium disabled:opacity-60 ${VARIANT_CLASSES[variant]} ${className ?? ""}`}
      {...rest}
    >
      {children}
    </button>
  );
});
