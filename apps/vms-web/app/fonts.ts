/**
 * Barlow and Barlow Condensed, self-hosted (UI-7).
 *
 * The faces are the ones the design prototype embeds, extracted from it rather than fetched. That
 * is not a preference: no font CDN is reachable from this network, and `next/font/google` resolves
 * at build time, so a Google-hosted import would turn every build into a network dependency and
 * every offline build into a silent fallback to the system stack. Files in the repository cannot
 * fail that way.
 *
 * Only the latin subset ships. The prototype carries latin, latin-ext and vietnamese for each
 * weight; keeping all three would triple the payload for glyphs no screen in this product renders.
 *
 * `display: "swap"` on purpose. A reception desk reading a visitor's name should get text
 * immediately in a fallback face rather than a blank line while a webfont arrives — the reflow is
 * the lesser cost of the two.
 */
import localFont from "next/font/local";

/** Body copy, tables, form controls. */
export const barlow = localFont({
  src: [
    { path: "./fonts/barlow-400.woff2", weight: "400", style: "normal" },
    { path: "./fonts/barlow-500.woff2", weight: "500", style: "normal" },
    { path: "./fonts/barlow-700.woff2", weight: "700", style: "normal" },
  ],
  variable: "--font-barlow",
  display: "swap",
  // Named so a missing file degrades to something close in metrics rather than to Times.
  fallback: ["system-ui", "Segoe UI", "Roboto", "Helvetica Neue", "Arial", "sans-serif"],
});

/** Headings only. The condensed cut is what gives the prototype its operational density. */
export const barlowCondensed = localFont({
  src: [
    { path: "./fonts/barlow-condensed-400.woff2", weight: "400", style: "normal" },
    { path: "./fonts/barlow-condensed-600.woff2", weight: "600", style: "normal" },
  ],
  variable: "--font-barlow-condensed",
  display: "swap",
  fallback: ["system-ui", "Segoe UI", "Roboto", "Helvetica Neue", "Arial", "sans-serif"],
});
