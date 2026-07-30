"use client";

/**
 * User administration (UI-4b over US-02.2.1/2).
 *
 * Creation is a two-call flow by API design: POST /users writes the account with an unusable
 * password sentinel and returns only the id; the activation token comes from a follow-up
 * reset-password call. This screen runs both and hands the admin the activation link exactly
 * once — the token is single-use, never stored client-side, and gone when the dialog closes
 * (interim until the email channel lands).
 *
 * CSV import is preview-then-execute against the API's two endpoints (raw text/csv body, not
 * multipart). Conflicting rows are skipped only after the admin explicitly opts in — the 409
 * that forces the choice carries the same preview shape.
 *
 * The list keeps its filters in the URL like every other list screen; the users API has no
 * free-text search, so the filters are role and status.
 */
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input, Select } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  ReceptionsApi,
  RolesApi,
  TenantsApi,
  UsersApi,
  errorMessage,
  fieldError,
  type AdminUser,
  type ImportPreview,
  type ImportResult,
  type Reception,
  type RoleView,
  type Tenant,
  type UserPage,
} from "@/lib/admin-api";
import { useAuth } from "@/lib/use-auth";

export default function UsersPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <UsersScreen />
    </Suspense>
  );
}

function UsersScreen() {
  useAuth(); // the shell guards; reading keeps this screen re-rendering on session changes
  const { toast } = useToast();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const params = useMemo(() => {
    const pageRaw = Number(searchParams.get("page"));
    const active = searchParams.get("active");
    return {
      role: searchParams.get("role") ?? undefined,
      active: active === null ? undefined : active === "true",
      page: Number.isInteger(pageRaw) && pageRaw > 0 ? pageRaw : 0,
      size: 20,
      sort: searchParams.get("sort") ?? undefined,
    };
  }, [searchParams]);

  const updateUrl = useCallback(
    (next: Partial<{ role: string | undefined; active: boolean | undefined; page: number; sort: string }>) => {
      const q = new URLSearchParams(searchParams);
      const merged = { ...params, ...next };
      const page = Object.keys(next).some((k) => k !== "page") ? 0 : (merged.page ?? 0);
      const write = (key: string, value: string | undefined) =>
        value === undefined || value === "" ? q.delete(key) : q.set(key, value);
      write("role", merged.role);
      write("active", merged.active === undefined ? undefined : String(merged.active));
      write("page", page > 0 ? String(page) : undefined);
      write("sort", merged.sort);
      const query = q.toString();
      router.replace(query ? `${pathname}?${query}` : pathname);
    },
    [router, pathname, searchParams, params],
  );

  const [data, setData] = useState<UserPage<AdminUser> | null>(null);
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setListError(null);
    UsersApi.list(params)
      .then((p) => {
        if (!cancelled) setData(p);
      })
      .catch((e: unknown) => {
        if (!cancelled) setListError(e);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [params, tick]);

  // Role codes for the filter and the forms — one read serves both.
  const [roles, setRoles] = useState<RoleView[]>([]);
  useEffect(() => {
    RolesApi.overview()
      .then((o) => setRoles(o.roles))
      .catch(() => setRoles([]));
  }, []);

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [confirming, setConfirming] = useState<{
    user: AdminUser;
    action: "deactivate" | "reactivate" | "unlock" | "reset-password";
  } | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [activation, setActivation] = useState<{ username: string; token: string } | null>(null);
  const [importing, setImporting] = useState(false);

  const CONFIRM_COPY = {
    deactivate: {
      title: "Deactivate user",
      label: "Deactivate",
      destructive: true,
      description: (u: AdminUser) => (
        <>
          Deactivate <strong>{u.username}</strong> ({u.fullName})? Every live session is revoked
          immediately.
        </>
      ),
    },
    reactivate: {
      title: "Reactivate user",
      label: "Reactivate",
      destructive: false,
      description: (u: AdminUser) => (
        <>
          Reactivate <strong>{u.username}</strong> ({u.fullName})?
        </>
      ),
    },
    unlock: {
      title: "Unlock account",
      label: "Unlock",
      destructive: false,
      description: (u: AdminUser) => (
        <>
          Clear the failed-login lockout for <strong>{u.username}</strong>?
        </>
      ),
    },
    "reset-password": {
      title: "Reset password",
      label: "Reset",
      destructive: true,
      description: (u: AdminUser) => (
        <>
          Reset the password of <strong>{u.username}</strong>? Every live session is revoked and a
          single-use activation link is issued for them to set a new one.
        </>
      ),
    },
  } as const;

  async function runConfirmedAction() {
    if (!confirming) return;
    const { user, action } = confirming;
    setConfirmBusy(true);
    try {
      if (action === "deactivate") {
        await UsersApi.deactivate(user.id);
        toast(`${user.username} deactivated.`, "success");
      } else if (action === "reactivate") {
        await UsersApi.reactivate(user.id);
        toast(`${user.username} reactivated.`, "success");
      } else if (action === "unlock") {
        await UsersApi.unlock(user.id);
        toast(`${user.username} unlocked.`, "success");
      } else {
        const { activationToken } = await UsersApi.resetPassword(user.id);
        setActivation({ username: user.username, token: activationToken });
      }
      reload();
    } catch (e) {
      toast(errorMessage(e), "error");
    } finally {
      setConfirmBusy(false);
      setConfirming(null);
    }
  }

  const columns: Column<AdminUser>[] = [
    { key: "username", header: "Username", sortable: true, render: (u) => <code className="font-mono">{u.username}</code> },
    { key: "full_name", header: "Full name", sortable: true, render: (u) => u.fullName },
    { key: "email", header: "Email", sortable: true, render: (u) => u.email ?? "—" },
    { key: "role", header: "Role", render: (u) => u.roleCode },
    {
      key: "status",
      header: "Status",
      render: (u) => (u.active ? "Active" : <span className="text-text-muted">Inactive</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (u) => (
        <span className="flex flex-wrap justify-end gap-2">
          <Button variant="secondary" onClick={() => setEditing(u)}>
            Edit
          </Button>
          <Button variant="secondary" onClick={() => setConfirming({ user: u, action: "unlock" })}>
            Unlock
          </Button>
          {u.active && (
            <Button
              variant="secondary"
              onClick={() => setConfirming({ user: u, action: "reset-password" })}
            >
              Reset password
            </Button>
          )}
          {u.active ? (
            <Button variant="danger" onClick={() => setConfirming({ user: u, action: "deactivate" })}>
              Deactivate
            </Button>
          ) : (
            <Button variant="secondary" onClick={() => setConfirming({ user: u, action: "reactivate" })}>
              Reactivate
            </Button>
          )}
        </span>
      ),
    },
  ];

  if (listError) {
    return <ErrorState error={listError} retry={reload} />;
  }

  return (
    <div>
      <h2 className="text-xl font-semibold text-text">Users</h2>

      <div className="mt-4 flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-2">
          <div>
            <label htmlFor="filter-role" className="block text-sm font-medium text-text">
              Role
            </label>
            <select
              id="filter-role"
              value={params.role ?? ""}
              onChange={(e) => updateUrl({ role: e.target.value || undefined })}
              className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
            >
              <option value="">All roles</option>
              {roles.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.code}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="filter-active" className="block text-sm font-medium text-text">
              Status
            </label>
            <select
              id="filter-active"
              value={params.active === undefined ? "all" : String(params.active)}
              onChange={(e) => {
                const v = e.target.value;
                updateUrl({ active: v === "all" ? undefined : v === "true" });
              }}
              className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
            >
              <option value="all">All</option>
              <option value="true">Active</option>
              <option value="false">Inactive</option>
            </select>
          </div>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setImporting(true)}>
            Import CSV
          </Button>
          <Button onClick={() => setCreating(true)}>New user</Button>
        </div>
      </div>

      <div className="mt-4">
        {data === null ? (
          <LoadingState />
        ) : (
          <DataTable<AdminUser>
            caption="Users"
            columns={columns}
            rows={data.content}
            rowKey={(u) => u.id}
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            sort={{ key: params.sort ?? "username", direction: "asc" }}
            onPageChange={(page) => updateUrl({ page })}
            onSortChange={(sort) => updateUrl({ sort: sort.key })}
            loading={loading}
            empty={
              <EmptyState
                title="No users match"
                action={<Button onClick={() => setCreating(true)}>New user</Button>}
              />
            }
          />
        )}
      </div>

      {creating && (
        <CreateUserDialog
          roles={roles}
          onDone={(created) => {
            setCreating(false);
            if (created) {
              setActivation(created);
              reload();
            }
          }}
        />
      )}

      {editing && (
        <AssignmentDialog
          user={editing}
          roles={roles}
          onDone={(saved) => {
            setEditing(null);
            if (saved) {
              toast(saved, "success");
              reload();
            }
          }}
        />
      )}

      {confirming && (
        <ConfirmDialog
          open
          title={CONFIRM_COPY[confirming.action].title}
          description={CONFIRM_COPY[confirming.action].description(confirming.user)}
          confirmLabel={CONFIRM_COPY[confirming.action].label}
          destructive={CONFIRM_COPY[confirming.action].destructive}
          busy={confirmBusy}
          onCancel={() => setConfirming(null)}
          onConfirm={runConfirmedAction}
        />
      )}

      {activation && (
        <ActivationTokenDialog activation={activation} onClose={() => setActivation(null)} />
      )}

      {importing && (
        <ImportCsvDialog
          onDone={(result) => {
            setImporting(false);
            if (result) {
              toast(
                `${result.created.length} users created, ${result.skipped} skipped, ${result.rejected} rejected.`,
                "success",
              );
              reload();
            }
          }}
        />
      )}
    </div>
  );
}

// ---- dialogs ----

function ActivationTokenDialog({
  activation,
  onClose,
}: {
  activation: { username: string; token: string };
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <Dialog open onClose={onClose} title={`Activation link for ${activation.username}`}>
      <p className="mt-2 text-sm text-text-muted">
        Hand this token to the user through a channel you trust. It is single-use, it expires, and
        {" "}<strong>it will not be shown again</strong>. They redeem it on the activation screen by
        choosing their password.
      </p>
      <code className="mt-3 block break-all rounded-md bg-surface-sunken p-3 font-mono text-sm">
        {activation.token}
      </code>
      <div className="mt-6 flex justify-end gap-3">
        <Button
          variant="secondary"
          onClick={async () => {
            await navigator.clipboard.writeText(activation.token);
            setCopied(true);
          }}
        >
          {copied ? "Copied" : "Copy token"}
        </Button>
        <Button onClick={onClose}>Done</Button>
      </div>
    </Dialog>
  );
}

function useAssignmentOptions() {
  const [receptions, setReceptions] = useState<Reception[]>([]);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  useEffect(() => {
    ReceptionsApi.list({ active: true, size: 200 })
      .then((p) => setReceptions(p.items))
      .catch(() => setReceptions([]));
    TenantsApi.list({ active: true, size: 200 })
      .then((p) => setTenants(p.items))
      .catch(() => setTenants([]));
  }, []);
  return { receptions, tenants };
}

function CreateUserDialog({
  roles,
  onDone,
}: {
  roles: RoleView[];
  onDone: (created: { username: string; token: string } | null) => void;
}) {
  const { receptions, tenants } = useAssignmentOptions();
  const [values, setValues] = useState({
    username: "",
    email: "",
    fullName: "",
    roleCode: "",
    receptionId: "",
    tenantId: "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    try {
      const { id } = await UsersApi.create({
        username: values.username,
        email: values.email || null,
        fullName: values.fullName,
        roleCode: values.roleCode,
        receptionId: values.receptionId || null,
        tenantId: values.tenantId || null,
      });
      // The account exists but cannot log in yet; the activation token comes from the
      // reset-password call — the API's create deliberately issues none.
      const { activationToken } = await UsersApi.resetPassword(id);
      onDone({ username: values.username, token: activationToken });
    } catch (e) {
      const fe = fieldError(e);
      const map: Record<string, string> = { reception_id: "receptionId", tenant_id: "tenantId" };
      if (fe) {
        const field = map[fe.field] ?? fe.field;
        if (["username", "email", "fullName", "roleCode", "receptionId", "tenantId"].includes(field)) {
          // The users API sends {error, field} without a message — compose one from the error kind.
          const message =
            fe.message || (field === "username" || field === "email" ? "Already in use." : "Invalid value.");
          setErrors({ [field]: message });
        } else {
          setFormError(errorMessage(e));
        }
      } else {
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title="New user">
      <form onSubmit={onSubmit} noValidate>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          <Field label="Username" error={errors["username"]} hint="3–60 characters: letters, digits, . _ -" required>
            <Input
              value={values.username}
              onChange={(e) => setValues({ ...values, username: e.target.value })}
              required
              autoFocus
              autoComplete="off"
            />
          </Field>
          <Field label="Full name" error={errors["fullName"]} required>
            <Input
              value={values.fullName}
              onChange={(e) => setValues({ ...values, fullName: e.target.value })}
              required
            />
          </Field>
          <Field label="Email" error={errors["email"]}>
            <Input
              type="email"
              value={values.email}
              onChange={(e) => setValues({ ...values, email: e.target.value })}
              autoComplete="off"
            />
          </Field>
          <Field label="Role" error={errors["roleCode"]} required>
            <Select value={values.roleCode} onChange={(e) => setValues({ ...values, roleCode: e.target.value })}>
              <option value="">Select a role…</option>
              {roles.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.code}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Reception" error={errors["receptionId"]} hint="For receptionist roles.">
            <Select
              value={values.receptionId}
              onChange={(e) => setValues({ ...values, receptionId: e.target.value })}
            >
              <option value="">None</option>
              {receptions.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.code} — {r.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Tenant" error={errors["tenantId"]} hint="For tenant roles.">
            <Select value={values.tenantId} onChange={(e) => setValues({ ...values, tenantId: e.target.value })}>
              <option value="">None</option>
              {tenants.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.code} — {t.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            Create and issue activation link
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function AssignmentDialog({
  user,
  roles,
  onDone,
}: {
  user: AdminUser;
  roles: RoleView[];
  onDone: (savedMessage: string | null) => void;
}) {
  const { receptions, tenants } = useAssignmentOptions();
  const [values, setValues] = useState({
    roleCode: user.roleCode,
    receptionId: user.receptionId ?? "",
    tenantId: user.tenantId ?? "",
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError(null);
    setBusy(true);
    try {
      await UsersApi.updateAssignment(user.id, {
        roleCode: values.roleCode,
        receptionId: values.receptionId || null,
        tenantId: values.tenantId || null,
      });
      onDone(`${user.username} updated.`);
    } catch (e) {
      setFormError(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={`Edit ${user.username}`}>
      <form onSubmit={onSubmit} noValidate>
        <p className="mt-2 text-sm text-text-muted">
          Assignment only — username, name and email are fixed at creation.
        </p>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          <Field label="Role" required>
            <Select value={values.roleCode} onChange={(e) => setValues({ ...values, roleCode: e.target.value })}>
              {roles.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.code}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Reception">
            <Select
              value={values.receptionId}
              onChange={(e) => setValues({ ...values, receptionId: e.target.value })}
            >
              <option value="">None</option>
              {receptions.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.code} — {r.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Tenant">
            <Select value={values.tenantId} onChange={(e) => setValues({ ...values, tenantId: e.target.value })}>
              <option value="">None</option>
              {tenants.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.code} — {t.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            Save
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function ImportCsvDialog({ onDone }: { onDone: (result: ImportResult | null) => void }) {
  const [csv, setCsv] = useState("");
  const [fileName, setFileName] = useState("pasted.csv");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [skipConflicts, setSkipConflicts] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onFile(file: File) {
    setFileName(file.name);
    setCsv(await file.text());
    setPreview(null);
    setError(null);
  }

  async function runPreview() {
    setError(null);
    setBusy(true);
    try {
      setPreview(await UsersApi.importPreview(csv));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function runExecute() {
    setError(null);
    setBusy(true);
    try {
      setResult(await UsersApi.importExecute(csv, fileName, skipConflicts));
    } catch (e) {
      const problem = (e as { problem?: unknown })?.problem;
      if (problem && typeof problem === "object" && "conflicts" in problem) {
        // The 409 is the preview again: conflicts exist and skipping was not confirmed.
        setPreview(problem as ImportPreview);
        setError("Conflicting rows exist. Tick “skip conflicting rows” to import the rest.");
      } else {
        setError(errorMessage(e));
      }
    } finally {
      setBusy(false);
    }
  }

  if (result) {
    return (
      <Dialog open onClose={() => onDone(result)} title="Import complete">
        <p className="mt-2 text-sm text-text-muted">
          {result.created.length} created · {result.skipped} skipped · {result.rejected} rejected.
          Each activation token below is single-use and <strong>will not be shown again</strong>.
        </p>
        <div className="mt-3 max-h-64 overflow-y-auto rounded-md border border-border">
          <table className="w-full border-collapse text-sm">
            <caption className="sr-only">Created users and activation tokens</caption>
            <thead>
              <tr className="border-b border-border bg-surface-sunken text-left">
                <th scope="col" className="px-3 py-2 font-medium">Username</th>
                <th scope="col" className="px-3 py-2 font-medium">Activation token</th>
              </tr>
            </thead>
            <tbody>
              {result.created.map((c) => (
                <tr key={c.id} className="border-b border-border last:border-b-0">
                  <td className="px-3 py-2 font-mono">{c.username}</td>
                  <td className="px-3 py-2 font-mono break-all">{c.activationToken}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="mt-6 flex justify-end">
          <Button onClick={() => onDone(result)}>Done</Button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog open onClose={() => onDone(null)} title="Import users from CSV">
      <p className="mt-2 text-sm text-text-muted">
        Columns: <code className="font-mono">username, fullName, roleCode, receptionId</code>{" "}
        (required), <code className="font-mono">email</code> (optional). A password column rejects
        the whole file.
      </p>
      <div className="mt-3 space-y-3">
        <input
          type="file"
          accept=".csv,text/csv,text/plain"
          aria-label="CSV file"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) void onFile(f);
          }}
          className="block text-sm"
        />
        <textarea
          aria-label="CSV content"
          value={csv}
          onChange={(e) => {
            setCsv(e.target.value);
            setPreview(null);
          }}
          rows={6}
          className="w-full rounded-md border border-border bg-surface px-3 py-2 font-mono text-sm"
          placeholder={"username,fullName,roleCode,receptionId,email\njdoe,Jane Doe,FLOOR_RECEPTIONIST,<reception-uuid>,jdoe@ex.com"}
        />
      </div>

      {error && (
        <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
          {error}
        </p>
      )}

      {preview && (
        <div className="mt-3 rounded-md border border-border p-3 text-sm">
          <p>
            <strong>{preview.creatable}</strong> creatable · <strong>{preview.conflicts}</strong>{" "}
            conflicts · <strong>{preview.rejected}</strong> rejected
          </p>
          {preview.rows.some((r) => r.status !== "CREATABLE") && (
            <ul className="mt-2 max-h-40 space-y-1 overflow-y-auto">
              {preview.rows
                .filter((r) => r.status !== "CREATABLE")
                .map((r) => (
                  <li key={r.line}>
                    Line {r.line} ({r.username ?? "—"}):{" "}
                    <span className={r.status === "REJECTED" ? "text-danger" : "text-text-muted"}>
                      {r.reason ?? r.status}
                    </span>
                  </li>
                ))}
            </ul>
          )}
          {preview.conflicts > 0 && (
            <label className="mt-2 flex items-center gap-2">
              <input
                type="checkbox"
                checked={skipConflicts}
                onChange={(e) => setSkipConflicts(e.target.checked)}
              />
              <span>Skip conflicting rows (existing accounts are never overwritten)</span>
            </label>
          )}
        </div>
      )}

      <div className="mt-6 flex justify-end gap-3">
        <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
          Cancel
        </Button>
        <Button variant="secondary" onClick={runPreview} busy={busy} disabled={csv.trim() === ""}>
          Preview
        </Button>
        <Button
          onClick={runExecute}
          busy={busy}
          disabled={csv.trim() === "" || preview === null || preview.creatable === 0}
        >
          Import
        </Button>
      </div>
    </Dialog>
  );
}
