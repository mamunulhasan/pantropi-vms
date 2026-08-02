"use client";

/**
 * The authenticated shell, shared by every signed-in area (US-06.3.1, T-06.3.1.1).
 *
 * Extracted from the admin layout when the tenant area arrived (VJ-1). The alternative was a
 * second copy of the session guard, the bfcache re-check and the redirect logic — security-
 * relevant code whose two copies would drift, and where the drift would be invisible until
 * someone's back button showed them a stale console. One implementation, parameterised by the
 * navigation registry and the permissions that justify entering.
 *
 * Protection is client-side by necessity: the access token lives in page memory and the refresh
 * cookie is path-scoped to /api/session, so the server rendering this layout has nothing to
 * inspect — which is the point of the cookie scope, not a gap. While the one resume attempt is
 * settling the guard holds, rather than flashing a redirect at a user whose cookie was about to
 * restore them. And none of this is an authorization decision: every API call behind this shell
 * is enforced server-side (US-03.2.1); hiding is usability.
 *
 * The rail marks where you are (`aria-current="page"`) and, below `md`, folds into a disclosure in
 * the header. It stays one `<nav>` element in both shapes rather than a desktop copy and a mobile
 * copy: two would be two navigation landmarks for a screen reader to choose between, and the one
 * that is visually hidden is the one that would rot.
 *
 * The `pageshow` listener covers the back/forward cache (AC-4): after logout a bfcache restore
 * would resurrect the last-rendered DOM without re-running effects — `event.persisted` is that
 * exact case, and re-checking the store then is what keeps a signed-out browser from showing
 * authenticated content on the back button.
 */
import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { AccessDenied } from "@/components/AccessDenied";
import { BrandMark } from "@/components/BrandMark";
import { ToastProvider } from "@/components/ui/Toast";
import { getSnapshot, logout } from "@/lib/auth-store";
import { visibleNavItems, type NavItem } from "@/lib/nav";
import { hasAny, type Permission } from "@/lib/permissions";
import { useAuth } from "@/lib/use-auth";

export function AppShell({
  navItems,
  navLabel,
  entryPermissions,
  deniedDescription,
  children,
}: {
  navItems: readonly NavItem[];
  /** Names the navigation landmark — screen readers list it, so it must say which one this is. */
  navLabel: string;
  /** Any-of. Holding none of these lands on the no-access state instead of the content. */
  entryPermissions: readonly Permission[];
  deniedDescription: string;
  children: React.ReactNode;
}) {
  const auth = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [navOpen, setNavOpen] = useState(false);

  const signedOut = !auth.resuming && !auth.accessToken;
  const items = visibleNavItems(navItems, auth.me?.permissions);

  // Longest match wins. `/visits` and `/visits/new` are siblings, so a plain prefix test would
  // light up both while you are on the second — whereas `/admin/buildings/{id}/floors` genuinely
  // should keep Buildings current. Picking the most specific href satisfies both without a rule
  // per area.
  const currentHref = items
    .filter((item) => pathname === item.href || pathname.startsWith(`${item.href}/`))
    .reduce<string | null>(
      (best, item) => (best === null || item.href.length > best.length ? item.href : best),
      null,
    );

  useEffect(() => {
    if (signedOut) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [signedOut, router, pathname]);

  useEffect(() => {
    function onPageShow(event: PageTransitionEvent) {
      if (event.persisted && getSnapshot().accessToken === null) {
        router.replace("/login");
      }
    }
    window.addEventListener("pageshow", onPageShow);
    return () => window.removeEventListener("pageshow", onPageShow);
  }, [router]);

  // A tap that navigates should not leave the panel covering what it navigated to.
  useEffect(() => {
    setNavOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!auth.resuming && auth.mustChangePassword) {
      router.replace("/change-password");
    }
  }, [auth.resuming, auth.mustChangePassword, router]);

  if (auth.resuming) {
    return (
      <main className="min-h-screen flex items-center justify-center bg-surface-sunken">
        <p role="status" className="text-text-muted">
          Restoring your session…
        </p>
      </main>
    );
  }

  if (signedOut) {
    return null; // the effect above is already navigating
  }

  async function onLogout() {
    await logout();
    router.replace("/login");
  }

  // A signed-in principal whose permissions cover none of this area gets the explicit no-access
  // state (US-06.3.2 AC-4/AC-5) — with the header kept, so signing out remains reachable. An
  // empty permission list lands here too: absence of authority is stated, never an empty shell.
  const deniedEntry = auth.me !== null && !hasAny(auth.me.permissions, entryPermissions);

  return (
    <ToastProvider>
      <div className="min-h-screen bg-surface-sunken">
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-surface-raised focus:px-4 focus:py-2 focus:shadow-raised"
        >
          Skip to content
        </a>

        <header className="flex items-center justify-between border-b border-border bg-brand px-4 py-3 text-brand-contrast sm:px-6">
          <div className="flex min-w-0 items-center gap-3">
            {/* Only a control below md, where the rail is folded away. Hidden from the
                accessibility tree above it, so a desktop reader is not offered a toggle for
                something already on screen. */}
            <button
              type="button"
              aria-expanded={navOpen}
              aria-controls="area-nav"
              onClick={() => setNavOpen((open) => !open)}
              className="rounded-md border border-brand-contrast/40 px-2 py-1 text-sm md:hidden"
            >
              <span aria-hidden="true">☰</span>
              <span className="sr-only">{navOpen ? "Hide" : "Show"} {navLabel} menu</span>
            </button>
            <BrandMark className="text-brand-contrast" />
          </div>
          <div className="flex items-center gap-3 text-sm sm:gap-4">
            <span aria-label="Signed in as" className="truncate">
              {auth.me?.displayName ?? auth.me?.username ?? "…"}
            </span>
            <button
              type="button"
              onClick={onLogout}
              className="rounded-md border border-brand-contrast/40 px-3 py-1 hover:bg-brand-hover"
            >
              Sign out
            </button>
          </div>
        </header>

        <div className="flex flex-col md:flex-row">
          {/* Unheld destinations are absent, never disabled (US-06.3.2 AC-1). */}
          <nav
            id="area-nav"
            aria-label={navLabel}
            className={`shrink-0 border-border bg-surface p-4 max-md:border-b md:block md:w-56 md:border-r ${
              navOpen ? "block" : "max-md:hidden"
            }`}
          >
            <ul className="space-y-1 text-sm">
              {items.map((item) => {
                const current = item.href === currentHref;
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      aria-current={current ? "page" : undefined}
                      className={
                        current
                          ? "block rounded-md bg-surface-sunken px-3 py-2 font-medium text-brand"
                          : "block rounded-md px-3 py-2 text-text hover:bg-surface-sunken"
                      }
                    >
                      {item.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </nav>

          {deniedEntry ? (
            <AccessDenied username={auth.me?.username} description={deniedDescription} />
          ) : (
            <main id="main-content" className="min-h-screen flex-1 bg-surface p-4 sm:p-6 lg:p-8">
              {children}
            </main>
          )}
        </div>
      </div>
    </ToastProvider>
  );
}
