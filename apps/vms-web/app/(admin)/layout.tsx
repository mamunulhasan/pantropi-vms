"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { AccessDenied } from "@/components/AccessDenied";
import { ToastProvider } from "@/components/ui/Toast";
import { BrandMark } from "@/components/BrandMark";
import { getSnapshot, logout } from "@/lib/auth-store";
import { ADMIN_ENTRY, ADMIN_NAV, visibleNavItems } from "@/lib/nav";
import { hasAny } from "@/lib/permissions";
import { useAuth } from "@/lib/use-auth";

/**
 * The authenticated shell (US-06.3.1, T-06.3.1.1): header, navigation region, content region,
 * and the route protection for everything in the (admin) group.
 *
 * Protection is client-side by necessity: the access token lives in page memory and the refresh
 * cookie is path-scoped to /api/session, so the server rendering this layout has nothing to
 * inspect — which is the point of the cookie scope, not a gap. While the one resume attempt is
 * settling, the guard holds rather than flashing a redirect at a user whose cookie was about to
 * restore them. And none of this is an authorization decision: every API call behind this shell
 * is enforced server-side (US-03.2.1); hiding is usability.
 *
 * The `pageshow` listener covers the back/forward cache (AC-4): after logout, a bfcache restore
 * would resurrect the last-rendered DOM without re-running effects — `event.persisted` is that
 * exact case, and re-checking the store then is what keeps a signed-out browser from showing
 * authenticated content on the back button.
 */
export default function AdminShell({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  const signedOut = !auth.resuming && !auth.accessToken;

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

  // A signed-in principal whose permissions cover none of the admin area gets the explicit
  // no-access state (AC-4/AC-5) — with the header kept, so signing out remains reachable.
  // An empty permission list lands here too: absence of authority is stated, never an empty shell.
  const deniedEntry = auth.me !== null && !hasAny(auth.me.permissions, ADMIN_ENTRY);

  return (
    <ToastProvider>
    <div className="min-h-screen bg-surface-sunken">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-surface-raised focus:px-4 focus:py-2 focus:shadow-raised"
      >
        Skip to content
      </a>

      <header className="flex items-center justify-between border-b border-border bg-brand px-6 py-3 text-brand-contrast">
        <BrandMark className="text-brand-contrast" />
        <div className="flex items-center gap-4 text-sm">
          <span aria-label="Signed in as">{auth.me?.displayName ?? auth.me?.username ?? "…"}</span>
          <button
            type="button"
            onClick={onLogout}
            className="rounded-md border border-brand-contrast/40 px-3 py-1 hover:bg-brand-hover"
          >
            Sign out
          </button>
        </div>
      </header>

      <div className="flex">
        {/* Unheld destinations are absent, never disabled (US-06.3.2 AC-1). */}
        <nav aria-label="Administration" className="w-56 shrink-0 border-r border-border bg-surface p-4">
          <ul className="space-y-1 text-sm">
            {visibleNavItems(ADMIN_NAV, auth.me?.permissions).map((item) => (
              <li key={item.href}>
                <Link href={item.href} className="block rounded-md px-3 py-2 hover:bg-surface-sunken">
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>

        {deniedEntry ? (
          <AccessDenied
            username={auth.me?.username}
            description="Your account doesn't have access to the administration area."
          />
        ) : (
          <main id="main-content" className="min-h-screen flex-1 bg-surface p-8">
            {children}
          </main>
        )}
      </div>
    </div>
    </ToastProvider>
  );
}
