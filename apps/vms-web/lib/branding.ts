/**
 * Client branding, from environment configuration (US-06.1.1 AC-2/AC-3).
 *
 * Rebranding the portal for a client is a deployment concern, not a code change: the name and
 * logo come from `NEXT_PUBLIC_*` variables baked at build time per environment. Nothing in
 * component code names a client.
 *
 * The defaults are the delivering product's own, so a missing configuration is a branded portal
 * rather than a broken one — and {@link BrandMark} falls back to the text name if a configured
 * logo asset fails to load (AC-6), so a bad asset path cannot collapse the header.
 */
export const branding = {
  /** Shown in the header, the login screen, and the document title. */
  name: process.env.NEXT_PUBLIC_BRAND_NAME ?? "Pantropi VMS",

  /** Optional logo path (under /public) or absolute URL. Absent means text-only branding. */
  logoSrc: process.env.NEXT_PUBLIC_BRAND_LOGO ?? null,
} as const;
