"use client";

/**
 * The admin area's shell — the shared {@link AppShell} bound to the admin navigation registry
 * and the permissions that justify entering the area (US-06.3.1 / US-06.3.2).
 *
 * The guard, the bfcache re-check and the redirect logic live in AppShell, so the tenant area
 * cannot drift away from them.
 */
import { AppShell } from "@/components/AppShell";
import { ADMIN_ENTRY, ADMIN_NAV } from "@/lib/nav";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <AppShell
      navItems={ADMIN_NAV}
      navLabel="Administration"
      entryPermissions={ADMIN_ENTRY}
      deniedDescription="Your account doesn't have access to the administration area."
    >
      {children}
    </AppShell>
  );
}
