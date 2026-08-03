import type { Metadata } from "next";
import "./globals.css";
import { branding } from "@/lib/branding";
import { barlow, barlowCondensed } from "./fonts";

/*
 * Root layout: language, title, token-backed body styling — nothing else. The authenticated
 * shell (header, navigation, route protection) belongs to the (admin) group layout and arrives
 * with US-06.3.1; the login screen owns the (auth) group.
 *
 * The font faces are self-hosted and attached here as CSS variables; --font-family-sans and
 * --font-family-heading in tokens.css read them, so typography stays a token decision
 * (US-06.1.1 AC-1) and first render never waits on a font CDN.
 */
export const metadata: Metadata = {
  title: {
    default: branding.name,
    template: `%s — ${branding.name}`,
  },
  description: "Visitor management for the building's tenants, reception and administrators.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${barlow.variable} ${barlowCondensed.variable}`}>
      <body className="antialiased">{children}</body>
    </html>
  );
}
