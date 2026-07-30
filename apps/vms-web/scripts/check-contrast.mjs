/**
 * WCAG contrast gate over the design tokens (US-06.1.1 AC-4).
 *
 * Parses styles/tokens.css directly — not a mirror of it — so a token edit is checked even when
 * nobody touched this script. Every pairing below is a place text sits on a background; a pairing
 * that fails 4.5:1 (WCAG 2.1 AA, normal text) fails the build naming both tokens and the ratio.
 *
 * If you add a token that carries text, add its pairing here. An unchecked pairing is an unkept
 * promise: the AC is "every text token pairing meets AA", and this list is the definition of
 * "every".
 *
 * Run: node scripts/check-contrast.mjs   (wired as `npm run check:tokens`, and into CI)
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(join(here, "..", "styles", "tokens.css"), "utf8");

/** [foreground, background] — text token on surface token. */
const PAIRINGS = [
  ["color-text", "color-surface"],
  ["color-text", "color-surface-sunken"],
  ["color-text", "color-surface-raised"],
  ["color-text-muted", "color-surface"],
  ["color-text-muted", "color-surface-sunken"],
  ["color-brand-contrast", "color-brand"],
  ["color-brand-contrast", "color-brand-hover"],
  ["color-danger-contrast", "color-danger"],
  ["color-danger", "color-surface"],          // error text inline on the page
  ["color-danger", "color-danger-surface"],   // error text on an error region
  ["color-success-contrast", "color-success"],
  ["color-success", "color-surface"],
  ["color-warning-contrast", "color-warning"],
  ["color-warning", "color-surface"],
];

const MIN_RATIO = 4.5;

function tokenValue(name) {
  const match = css.match(new RegExp(`--${name}:\\s*([^;]+);`));
  if (!match) {
    console.error(`✗ token --${name} is named in a pairing but not declared in tokens.css`);
    process.exit(1);
  }
  return match[1].trim();
}

function parseHex(value) {
  const hex = value.match(/^#([0-9a-fA-F]{6})$/);
  if (!hex) {
    console.error(`✗ '${value}' is not a 6-digit hex colour — contrast pairings must be plain hex`);
    process.exit(1);
  }
  const n = parseInt(hex[1], 16);
  return [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff];
}

/** Relative luminance per WCAG 2.1 §G17. */
function luminance([r, g, b]) {
  const [rs, gs, bs] = [r, g, b].map((c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs;
}

function ratio(fg, bg) {
  const l1 = luminance(parseHex(fg));
  const l2 = luminance(parseHex(bg));
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

let failures = 0;
for (const [fg, bg] of PAIRINGS) {
  const r = ratio(tokenValue(fg), tokenValue(bg));
  const ok = r >= MIN_RATIO;
  if (!ok) {
    failures++;
    console.error(`✗ --${fg} on --${bg}: ${r.toFixed(2)}:1 — below WCAG AA ${MIN_RATIO}:1`);
  } else {
    console.log(`✓ --${fg} on --${bg}: ${r.toFixed(2)}:1`);
  }
}

if (failures > 0) {
  console.error(`\n${failures} pairing(s) below AA. Adjust tokens.css — not the component.`);
  process.exit(1);
}
console.log(`\nAll ${PAIRINGS.length} token pairings meet WCAG 2.1 AA (${MIN_RATIO}:1).`);
