import type { Metadata } from "next";
import "./globals.css";
import { branding } from "@/lib/branding";

/*
 * Root layout: language, title, token-backed body styling — nothing else. The authenticated
 * shell (header, navigation, route protection) belongs to the (admin) group layout and arrives
 * with US-06.3.1; the login screen owns the (auth) group.
 *
 * No web-font download: the system font stack is a token (--font-family-sans), which keeps
 * first render independent of a font CDN and keeps typography a token decision (US-06.1.1 AC-1).
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
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
