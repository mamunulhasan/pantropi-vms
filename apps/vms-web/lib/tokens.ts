/**
 * Typed access to the design tokens (US-06.1.1, T-06.1.1.1).
 *
 * The point of this module is the type, not the function: `TokenName` is the closed set of
 * custom properties declared in styles/tokens.css, so a component asking for a token that does
 * not exist is a compile error rather than a silently-unset CSS variable.
 *
 * Most styling should go through Tailwind's token-mapped utilities (`bg-brand`, `text-danger`).
 * This accessor exists for the places utilities cannot reach — inline SVG fills, canvas, style
 * props computed at runtime — so those too can only name real tokens.
 *
 * Keep this list in step with tokens.css. The contrast script parses the CSS file itself, so a
 * token added there but not here still gets its contrast check; it just cannot be used from
 * TypeScript until it is named here.
 */
export const TOKEN_NAMES = [
  "color-brand",
  "color-brand-contrast",
  "color-brand-hover",
  "color-surface",
  "color-surface-sunken",
  "color-surface-raised",
  "color-text",
  "color-text-muted",
  "color-text-inverted",
  "color-border",
  "color-border-strong",
  "color-danger",
  "color-danger-contrast",
  "color-danger-surface",
  "color-success",
  "color-success-contrast",
  "color-warning",
  "color-warning-contrast",
  "color-focus",
  "font-family-sans",
  "font-size-sm",
  "font-size-base",
  "font-size-lg",
  "font-size-xl",
  "font-size-2xl",
  "line-height-tight",
  "line-height-base",
  "space-1",
  "space-2",
  "space-3",
  "space-4",
  "space-6",
  "space-8",
  "space-12",
  "radius-sm",
  "radius-md",
  "radius-lg",
  "shadow-raised",
  "shadow-overlay",
  "duration-fast",
  "duration-base",
  "easing-standard",
] as const;

export type TokenName = (typeof TOKEN_NAMES)[number];

/** The token as a CSS value — `token("color-brand")` → `var(--color-brand)`. */
export function token(name: TokenName): string {
  return `var(--${name})`;
}
