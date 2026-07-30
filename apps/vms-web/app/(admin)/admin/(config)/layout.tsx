"use client";

/**
 * The tabbed configuration area (UI-6, wireframe 7a).
 *
 * A route group, so the eight tables keep the URLs they already had — `(config)` is a grouping in
 * the file tree and not a path segment. Nothing about the screens changed; they stopped being ten
 * sidebar entries and became one area you move around inside.
 *
 * The tabs are links, not buttons, because each one is a real page with its own address that can
 * be bookmarked and shared. That is also why this is a `nav` with `aria-current="page"` rather
 * than an ARIA tablist: a tablist promises panels swapping inside one document, and breaking that
 * promise is worse than not making it.
 *
 * A tab whose permission the reader does not hold is absent, not disabled — the same rule the
 * sidebar follows (US-06.3.2 AC-1), and the reason the tab list is filtered rather than styled.
 */
import Link from "next/link";
import { usePathname } from "next/navigation";
import { CONFIG_TABS, visibleNavItems } from "@/lib/nav";
import { useAuth } from "@/lib/use-auth";

export default function ConfigLayout({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const pathname = usePathname();
  const tabs = visibleNavItems(CONFIG_TABS, auth.me?.permissions);

  return (
    <div>
      <h1 className="text-xl font-semibold text-text">Configuration</h1>
      <p className="mt-1 text-sm text-text-muted">
        The reference data every other screen reads. Changes here are audited.
      </p>

      <nav aria-label="Configuration sections" className="mt-4 border-b border-border">
        <ul className="-mb-px flex flex-wrap gap-1">
          {tabs.map((tab) => {
            // A nested page keeps its parent tab current — /admin/buildings/{id}/floors is still
            // Buildings, and losing the highlight there would read as having left the area.
            const current = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
            return (
              <li key={tab.href}>
                <Link
                  href={tab.href}
                  aria-current={current ? "page" : undefined}
                  className={
                    current
                      ? "block border-b-2 border-brand px-4 py-2 text-sm font-medium text-brand"
                      : "block border-b-2 border-transparent px-4 py-2 text-sm text-text-muted hover:border-border hover:text-text"
                  }
                >
                  {tab.label}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>

      <div className="mt-6">{children}</div>
    </div>
  );
}
