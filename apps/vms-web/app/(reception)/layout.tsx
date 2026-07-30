"use client";

/**
 * The reception desk's shell (VJ-3) — the same {@link AppShell} as every other area, bound to the
 * reception navigation registry. `(reception)` is one of the route groups the backlog names, so
 * unlike `(fm)` it needs no deviation recorded.
 */
import { AppShell } from "@/components/AppShell";
import { RECEPTION_ENTRY, RECEPTION_NAV } from "@/lib/nav";

export default function ReceptionLayout({ children }: { children: React.ReactNode }) {
  return (
    <AppShell
      navItems={RECEPTION_NAV}
      navLabel="Reception"
      entryPermissions={RECEPTION_ENTRY}
      deniedDescription="Your account doesn't have access to the reception desk."
    >
      {children}
    </AppShell>
  );
}
