"use client";

/**
 * The tenant area's shell (VJ-1) — the same {@link AppShell} as the admin area, bound to the
 * tenant navigation registry.
 *
 * Entry needs `visitor.request`, the only permission a TENANT role holds. A visitor-management
 * administrator who wanders in here holds none of it and gets the no-access state, which is
 * correct: the tenant screens read and write *their own tenant's* requests, and the API scopes
 * every one of those queries to the caller's tenant.
 */
import { AppShell } from "@/components/AppShell";
import { TENANT_ENTRY, TENANT_NAV } from "@/lib/nav";

export default function TenantLayout({ children }: { children: React.ReactNode }) {
  return (
    <AppShell
      navItems={TENANT_NAV}
      navLabel="Visits"
      entryPermissions={TENANT_ENTRY}
      deniedDescription="Your account doesn't have access to the visitor request area."
    >
      {children}
    </AppShell>
  );
}
