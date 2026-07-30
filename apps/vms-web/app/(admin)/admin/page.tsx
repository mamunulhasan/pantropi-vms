"use client";

/**
 * The admin console home.
 *
 * No <main> of its own: the shell owns that landmark, and a second one here would give the
 * document two — which is how this started life, as a UI-0 placeholder written before the shell
 * existed. The a11y gate (UI-5) is what surfaced it.
 *
 * The destinations are the same permission-filtered registry the navigation renders from, so a
 * link can never appear here that the nav withheld.
 */
import Link from "next/link";
import { ADMIN_NAV, visibleNavItems } from "@/lib/nav";
import { useAuth } from "@/lib/use-auth";

export default function AdminHome() {
  const auth = useAuth();
  const destinations = visibleNavItems(ADMIN_NAV, auth.me?.permissions).filter(
    (item) => item.href !== "/admin",
  );

  return (
    <div>
      <h1 className="text-xl font-semibold text-text">Administration</h1>
      <p className="mt-1 text-sm text-text-muted">
        Configuration and user administration. Every change here is audited.
      </p>

      <ul className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {destinations.map((item) => (
          <li key={item.href}>
            <Link
              href={item.href}
              className="block rounded-lg border border-border bg-surface p-4 hover:bg-surface-sunken"
            >
              <span className="font-medium text-text">{item.label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
