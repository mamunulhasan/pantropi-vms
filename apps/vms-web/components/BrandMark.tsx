"use client";

import { useState } from "react";
import { branding } from "@/lib/branding";

/**
 * The client's mark, wherever the brand appears (US-06.1.1 AC-2/AC-6).
 *
 * Renders the configured logo when there is one, and degrades to the brand name as text when the
 * asset is absent **or fails to load** — the neutral fallback AC-6 requires, so a broken asset
 * URL in some environment's config shows a name instead of a broken-image icon, and the layout
 * around it does not collapse (the text occupies the same slot).
 *
 * A plain <img>, not next/image: the logo is a small static asset whose src may be an absolute
 * URL from configuration, and the optimisation pipeline would need that origin allowlisted per
 * environment for no visible gain at this size.
 */
export function BrandMark({ className }: { className?: string }) {
  const [logoFailed, setLogoFailed] = useState(false);

  if (!branding.logoSrc || logoFailed) {
    return (
      <span className={`font-semibold text-lg ${className ?? ""}`}>{branding.name}</span>
    );
  }

  return (
    // eslint-disable-next-line @next/next/no-img-element -- config may point off-origin; see above
    <img
      src={branding.logoSrc}
      alt={branding.name}
      className={`h-8 w-auto ${className ?? ""}`}
      onError={() => setLogoFailed(true)}
    />
  );
}
