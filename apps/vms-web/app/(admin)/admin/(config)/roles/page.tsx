"use client";

/**
 * Role grant matrix (UI-4b over US-03.1.1 / US-03.3.1).
 *
 * Editing honours the version token: each role's PUT carries the version read with the matrix,
 * and a 409 `stale_version` means someone else changed the grants first. The screen then shows
 * what changed and re-asks — the admin's draft is kept, the stored version advances to the
 * current one, and nothing is ever retried silently. The token is content-derived, so saving an
 * identical set back is harmless by design.
 *
 * The unbacked permissions (credential.override, report.*) render disabled, not hidden: they are
 * real capabilities awaiting a requirement (TODO-04/16), and the API refuses them with a reason.
 * Changes take effect on each affected user's next request — no re-login.
 */
import { useCallback, useEffect, useState } from "react";
import { PageHeader } from "@/components/ui/PageHeader";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { ErrorState, LoadingState } from "@/components/ui/states";
import {
  RolesApi,
  errorMessage,
  staleVersionOf,
  type RolesOverview,
  type StaleVersionConflict,
} from "@/lib/admin-api";
import { UNBACKED_PERMISSIONS } from "@/lib/permissions";

type Drafts = Record<string, { version: string; original: string[]; draft: string[] }>;

function sameSet(a: string[], b: string[]): boolean {
  return a.length === b.length && [...a].sort().every((v, i) => v === [...b].sort()[i]);
}

export default function RolesPage() {
  const { toast } = useToast();

  const [overview, setOverview] = useState<RolesOverview | null>(null);
  const [drafts, setDrafts] = useState<Drafts>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [savingRole, setSavingRole] = useState<string | null>(null);
  const [stale, setStale] = useState<StaleVersionConflict | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    RolesApi.overview()
      .then((o) => {
        setOverview(o);
        setDrafts(
          Object.fromEntries(
            o.roles.map((r) => [
              r.code,
              { version: r.version, original: r.permissions, draft: r.permissions },
            ]),
          ),
        );
      })
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  function toggle(roleCode: string, permission: string) {
    setDrafts((d) => {
      const entry = d[roleCode];
      if (!entry) return d;
      const has = entry.draft.includes(permission);
      const draft = has ? entry.draft.filter((p) => p !== permission) : [...entry.draft, permission];
      return { ...d, [roleCode]: { ...entry, draft } };
    });
  }

  async function save(roleCode: string) {
    const entry = drafts[roleCode];
    if (!entry) return;
    setSavingRole(roleCode);
    try {
      const saved = await RolesApi.putGrants(roleCode, entry.version, entry.draft);
      setDrafts((d) => ({
        ...d,
        [roleCode]: { version: saved.version, original: saved.permissions, draft: saved.permissions },
      }));
      toast(`Grants for ${roleCode} saved.`, "success");
    } catch (e) {
      const conflict = staleVersionOf(e);
      if (conflict) {
        // Advance to the current version but KEEP the draft: the admin reviews against what
        // changed and decides — re-ask, never silently retry.
        setDrafts((d) => {
          const cur = d[roleCode];
          return cur
            ? {
                ...d,
                [roleCode]: {
                  version: conflict.currentVersion,
                  original: conflict.currentPermissions,
                  draft: cur.draft,
                },
              }
            : d;
        });
        setStale(conflict);
      } else {
        // administrative_lockout and invalid_permission carry their reason in message.
        toast(errorMessage(e), "error");
      }
    } finally {
      setSavingRole(null);
    }
  }

  if (error) {
    return <ErrorState error={error} retry={load} />;
  }
  if (loading || overview === null) {
    return <LoadingState />;
  }

  return (
    <div>
      <PageHeader title="Roles and permissions" level={2} />
      <p className="mt-1 text-sm text-text-muted">
        A change takes effect on each affected user&rsquo;s next request. Saving is per role and
        refuses to overwrite someone else&rsquo;s concurrent edit.
      </p>

      <div className="mt-4 overflow-x-auto rounded-lg border border-border">
        <table className="w-full border-collapse bg-surface text-sm">
          <caption className="sr-only">Role-to-permission grant matrix</caption>
          <thead>
            <tr className="border-b border-border bg-surface-sunken text-left">
              <th scope="col" className="px-4 py-3 font-medium">Permission</th>
              {overview.roles.map((r) => (
                <th key={r.code} scope="col" className="px-4 py-3 text-center font-medium">
                  {r.code}
                  <span className="block text-xs font-normal text-text-muted">
                    {r.activeUsers} active user{r.activeUsers === 1 ? "" : "s"}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {overview.catalogue.map((p) => {
              const unbacked = (UNBACKED_PERMISSIONS as readonly string[]).includes(p.code);
              return (
                <tr key={p.code} className="border-b border-border last:border-b-0">
                  <th scope="row" className="px-4 py-3 text-left font-normal">
                    <code className="font-mono text-text">{p.code}</code>
                    <span className="block text-xs text-text-muted">{p.description}</span>
                    {unbacked && (
                      <span className="mt-1 inline-block rounded-full bg-surface-sunken px-2 py-0.5 text-xs text-text-muted">
                        not grantable — awaiting requirement
                      </span>
                    )}
                  </th>
                  {overview.roles.map((r) => {
                    const entry = drafts[r.code];
                    const checked = entry?.draft.includes(p.code) ?? false;
                    return (
                      <td key={r.code} className="px-4 py-3 text-center">
                        <input
                          type="checkbox"
                          aria-label={`${p.code} for ${r.code}`}
                          checked={checked}
                          disabled={unbacked && !checked}
                          onChange={() => toggle(r.code, p.code)}
                        />
                      </td>
                    );
                  })}
                </tr>
              );
            })}
            <tr className="bg-surface-sunken">
              <th scope="row" className="px-4 py-3 text-left font-medium">
                Save changes
              </th>
              {overview.roles.map((r) => {
                const entry = drafts[r.code];
                const dirty = entry ? !sameSet(entry.draft, entry.original) : false;
                return (
                  <td key={r.code} className="px-4 py-3 text-center">
                    <Button
                      variant={dirty ? "primary" : "secondary"}
                      disabled={!dirty}
                      busy={savingRole === r.code}
                      onClick={() => save(r.code)}
                    >
                      Save
                    </Button>
                  </td>
                );
              })}
            </tr>
          </tbody>
        </table>
      </div>

      {stale && (
        <Dialog open onClose={() => setStale(null)} title="Grants changed while you were editing">
          <p className="mt-2 text-sm text-text-muted">{stale.message}</p>
          <p className="mt-2 text-sm">
            <strong>{stale.roleCode}</strong> currently holds:{" "}
            {stale.currentPermissions.length > 0 ? (
              <code className="font-mono">{stale.currentPermissions.join(", ")}</code>
            ) : (
              <em>nothing</em>
            )}
          </p>
          <p className="mt-2 text-sm text-text-muted">
            Your selection is still on the matrix. Review it against the current state, then save
            again to apply it on top.
          </p>
          <div className="mt-6 flex justify-end">
            <Button onClick={() => setStale(null)}>Review</Button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
