"use client";

/**
 * Tenants (UI-4b over US-04.3.1).
 *
 * Deactivation shows the dependent count first — the API's `dependents` endpoint exists so the
 * admin decides with the number in front of them; it informs, never blocks. Assigned users keep
 * their tenant link either way.
 *
 * The floor picker narrows building → floor because floors only list per building. On edit,
 * "(keep current floor)" preserves an assignment without making the admin rediscover which
 * building it was in.
 */
import { Suspense, useEffect, useState } from "react";
import { ListToolbar } from "@/components/admin/ListToolbar";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input, Select } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  BuildingsApi,
  FloorsApi,
  TenantsApi,
  errorMessage,
  fieldError,
  type Building,
  type Floor,
  type Tenant,
  type TenantRequest,
} from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAdminList } from "@/lib/use-admin-list";
import { useAuth } from "@/lib/use-auth";

export default function TenantsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <TenantsScreen />
    </Suspense>
  );
}

function TenantsScreen() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();
  const list = useAdminList(TenantsApi.list, { sort: "code" });

  const [editing, setEditing] = useState<Tenant | "new" | null>(null);
  const [deactivating, setDeactivating] = useState<{ tenant: Tenant; activeUsers: number } | null>(null);
  const [reactivating, setReactivating] = useState<Tenant | null>(null);
  const [actionBusy, setActionBusy] = useState(false);

  const columns: Column<Tenant>[] = [
    { key: "code", header: "Code", sortable: true, render: (t) => <code className="font-mono">{t.code}</code> },
    { key: "name", header: "Name", sortable: true, render: (t) => t.name },
    { key: "contact", header: "Contact", render: (t) => t.contactEmail ?? t.contactPhone ?? "—" },
    {
      key: "status",
      header: "Status",
      render: (t) => (t.active ? "Active" : <span className="text-text-muted">Inactive</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (t) =>
        canEdit ? (
          <span className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEditing(t)}>
              Edit
            </Button>
            {t.active ? (
              <Button
                variant="danger"
                onClick={async () => {
                  // Fetch the dependent count first: the confirm must state it (US-04.3.1).
                  try {
                    const { activeUsers } = await TenantsApi.dependents(t.id);
                    setDeactivating({ tenant: t, activeUsers });
                  } catch (e) {
                    toast(errorMessage(e), "error");
                  }
                }}
              >
                Deactivate
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => setReactivating(t)}>
                Reactivate
              </Button>
            )}
          </span>
        ) : null,
    },
  ];

  if (list.error) {
    return <ErrorState error={list.error} retry={list.reload} />;
  }

  return (
    <div>
      <h2 className="text-xl font-semibold text-text">Tenants</h2>
      <div className="mt-4">
        <ListToolbar list={list} searchLabel="Search tenants">
          {canEdit && <Button onClick={() => setEditing("new")}>New tenant</Button>}
        </ListToolbar>

        {list.data === null ? (
          <LoadingState />
        ) : (
          <DataTable<Tenant>
            caption="Tenants"
            columns={columns}
            rows={list.data.items}
            rowKey={(t) => t.id}
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.total}
            sort={{ key: list.params.sort ?? "code", direction: "asc" }}
            onPageChange={(page) => list.update({ page })}
            onSortChange={(sort) => list.update({ sort: sort.key })}
            loading={list.loading}
            empty={
              <EmptyState
                title="No tenants match"
                action={canEdit ? <Button onClick={() => setEditing("new")}>New tenant</Button> : undefined}
              />
            }
          />
        )}
      </div>

      {editing && (
        <TenantFormDialog
          tenant={editing === "new" ? null : editing}
          onDone={(saved) => {
            setEditing(null);
            if (saved) {
              toast(saved, "success");
              list.reload();
            }
          }}
        />
      )}

      {deactivating && (
        <ConfirmDialog
          open
          title="Deactivate tenant"
          description={
            <>
              Deactivate tenant{" "}
              <strong>
                {deactivating.tenant.code} — {deactivating.tenant.name}
              </strong>
              ?{" "}
              {deactivating.activeUsers > 0 ? (
                <>
                  <strong>{deactivating.activeUsers}</strong> active user
                  {deactivating.activeUsers === 1 ? " is" : "s are"} assigned to it and will keep the
                  assignment.
                </>
              ) : (
                "No active users are assigned to it."
              )}
            </>
          }
          confirmLabel="Deactivate"
          destructive
          busy={actionBusy}
          onCancel={() => setDeactivating(null)}
          onConfirm={async () => {
            setActionBusy(true);
            try {
              await TenantsApi.deactivate(deactivating.tenant.id);
              toast(`${deactivating.tenant.code} deactivated.`, "success");
              list.reload();
            } catch (e) {
              toast(errorMessage(e), "error");
            } finally {
              setActionBusy(false);
              setDeactivating(null);
            }
          }}
        />
      )}

      {reactivating && (
        <ConfirmDialog
          open
          title="Reactivate tenant"
          description={
            <>
              Reactivate tenant{" "}
              <strong>
                {reactivating.code} — {reactivating.name}
              </strong>
              ?
            </>
          }
          confirmLabel="Reactivate"
          busy={actionBusy}
          onCancel={() => setReactivating(null)}
          onConfirm={async () => {
            setActionBusy(true);
            try {
              await TenantsApi.reactivate(reactivating.id);
              toast(`${reactivating.code} reactivated.`, "success");
              list.reload();
            } catch (e) {
              toast(errorMessage(e), "error");
            } finally {
              setActionBusy(false);
              setReactivating(null);
            }
          }}
        />
      )}
    </div>
  );
}

const KEEP_CURRENT = "__keep__";

function TenantFormDialog({
  tenant,
  onDone,
}: {
  tenant: Tenant | null;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState({
    code: tenant?.code ?? "",
    name: tenant?.name ?? "",
    contactEmail: tenant?.contactEmail ?? "",
    contactPhone: tenant?.contactPhone ?? "",
  });
  // Floor selection: "" = none, KEEP_CURRENT = the existing assignment, else a floor id.
  const [floorChoice, setFloorChoice] = useState(tenant?.floorId ? KEEP_CURRENT : "");
  const [buildings, setBuildings] = useState<Building[]>([]);
  const [buildingId, setBuildingId] = useState("");
  const [floors, setFloors] = useState<Floor[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    BuildingsApi.list({ active: true, size: 200 })
      .then((p) => setBuildings(p.items))
      .catch(() => setBuildings([])); // picker degrades; the field still accepts "none"/"keep"
  }, []);

  useEffect(() => {
    if (!buildingId) {
      setFloors([]);
      return;
    }
    let cancelled = false;
    FloorsApi.list(buildingId, { active: true, size: 200 })
      .then((p) => {
        if (!cancelled) setFloors(p.items);
      })
      .catch(() => {
        if (!cancelled) setFloors([]);
      });
    return () => {
      cancelled = true;
    };
  }, [buildingId]);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    const floorId =
      floorChoice === KEEP_CURRENT ? (tenant?.floorId ?? null) : floorChoice === "" ? null : floorChoice;
    const req: TenantRequest = {
      code: values.code,
      name: values.name,
      floorId,
      contactEmail: values.contactEmail || null,
      contactPhone: values.contactPhone || null,
    };
    try {
      if (tenant) {
        await TenantsApi.update(tenant.id, req);
        onDone(`${req.code.toUpperCase()} updated.`);
      } else {
        await TenantsApi.create(req);
        onDone(`${req.code.toUpperCase()} created.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      if (fe && ["code", "name", "contactEmail", "contactPhone"].includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
      } else if (fe && fe.field === "floor_id") {
        setErrors({ floorId: fe.message });
      } else {
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={tenant ? `Edit ${tenant.code}` : "New tenant"}>
      <form onSubmit={onSubmit} noValidate>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          <Field label="Code" error={errors["code"]} hint="Stored upper-case." required>
            <Input
              value={values.code}
              onChange={(e) => setValues({ ...values, code: e.target.value })}
              required
              maxLength={32}
              autoFocus={!tenant}
            />
          </Field>
          <Field label="Name" error={errors["name"]} required>
            <Input
              value={values.name}
              onChange={(e) => setValues({ ...values, name: e.target.value })}
              required
              maxLength={200}
            />
          </Field>
          <Field label="Building" hint="Narrows the floor list below.">
            <Select value={buildingId} onChange={(e) => setBuildingId(e.target.value)}>
              <option value="">Select a building…</option>
              {buildings.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.code} — {b.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Floor" error={errors["floorId"]}>
            <Select value={floorChoice} onChange={(e) => setFloorChoice(e.target.value)}>
              <option value="">No floor</option>
              {tenant?.floorId && <option value={KEEP_CURRENT}>(keep current floor)</option>}
              {floors.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.code} — {f.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Contact email" error={errors["contactEmail"]}>
            <Input
              type="email"
              value={values.contactEmail}
              onChange={(e) => setValues({ ...values, contactEmail: e.target.value })}
              maxLength={320}
            />
          </Field>
          <Field label="Contact phone" error={errors["contactPhone"]}>
            <Input
              type="tel"
              value={values.contactPhone}
              onChange={(e) => setValues({ ...values, contactPhone: e.target.value })}
              maxLength={40}
            />
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {tenant ? "Save" : "Create"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
