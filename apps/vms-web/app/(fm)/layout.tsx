"use client";

/**
 * The facilities-management area's shell (VJ-2) — the same {@link AppShell} as every other area,
 * bound to the FM navigation registry.
 *
 * A group of its own rather than a corner of the admin console: FM_ADMIN holds `visitor.approve`
 * and nothing else, so the console's shell would refuse it entry. The backlog's route-group list
 * — `(auth) (admin) (tenant) (reception) (display)` — predates the approval screens and names no
 * home for them; adding `(fm)` is a deliberate, recorded deviation rather than an oversight.
 */
import { AppShell } from "@/components/AppShell";
import { FM_ENTRY, FM_NAV } from "@/lib/nav";

export default function FmLayout({ children }: { children: React.ReactNode }) {
  return (
    <AppShell
      navItems={FM_NAV}
      navLabel="Facilities"
      entryPermissions={FM_ENTRY}
      deniedDescription="Your account doesn't have access to the approvals area."
    >
      {children}
    </AppShell>
  );
}
