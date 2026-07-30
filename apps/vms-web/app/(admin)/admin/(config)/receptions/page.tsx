"use client";

/**
 * Receptions (UI-4b over US-04.4.1).
 *
 * The central designation is singular and moves via the API's two-step: the first call without
 * confirm answers 409 `confirmation_required` naming the current holder, and this screen turns
 * that into the confirm dialog — the admin always sees who loses the designation before it
 * moves. When nothing holds it yet, the first call just succeeds.
 *
 * Creation requires a floor and always starts non-central; editing changes code/name only
 * (a reception can never move floors through the API). Deactivation shows the stationed-user
 * count first, and the `would_strand_master_admin` refusal surfaces verbatim.
 */
import { Suspense, useEffect, useState } from "react";
import { ListToolbar } from "@/components/admin/ListToolbar";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input, Select } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import { ApiError } from "@/lib/api";
import {
  BuildingsApi,
  FloorsApi,
  ReceptionsApi,
  errorMessage,
  fieldError,
  type Building,
  type Floor,
  type Reception,
} from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAdminList } from "@/lib/use-admin-list";
import { useAuth } from "@/lib/use-auth";

export default function ReceptionsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <ReceptionsScreen />
    </Suspense>
  );
}

type CentralTransfer = {
  reception: Reception;
  currentHolderCode: string;
};

function ReceptionsScreen() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();
  const list = useAdminList(ReceptionsApi.list, { sort: "code" });

  const [editing, setEditing] = useState<Reception | "new" | null>(null);
  const [deactivating, setDeactivating] = useState<{ reception: Reception; activeUsers: number } | null>(null);
  const [reactivating, setReactivating] = useState<Reception | null>(null);
  const [transfer, setTransfer] = useState<CentralTransfer | null>(null);
  const [actionBusy, setActionBusy] = useState(false);

  async function designate(reception: Reception, confirm: boolean) {
    setActionBusy(true);
    try {
      await ReceptionsApi.designateCentral(reception.id, confirm);
      toast(`${reception.code} is now the central reception.`, "success");
      setTransfer(null);
      list.reload();
    } catch (e) {
      if (
        !confirm &&
        e instanceof ApiError &&
        e.problem?.["error"] === "confirmation_required" &&
        typeof e.problem?.["currentHolderCode"] === "string"
      ) {
        // The 409 names the current holder — that is the confirm step.
        setTransfer({ reception, currentHolderCode: e.problem["currentHolderCode"] });
      } else {
        toast(errorMessage(e), "error");
        setTransfer(null);
      }
    } finally {
      setActionBusy(false);
    }
  }

  const columns: Column<Reception>[] = [
    { key: "code", header: "Code", sortable: true, render: (r) => <code className="font-mono">{r.code}</code> },
    { key: "name", header: "Name", sortable: true, render: (r) => r.name },
    {
      key: "central",
      header: "Central",
      sortable: true,
      render: (r) =>
        r.central ? (
          <span className="rounded-full bg-brand px-2 py-0.5 text-xs text-brand-contrast">Central</span>
        ) : (
          "—"
        ),
    },
    {
      key: "status",
      header: "Status",
      render: (r) => (r.active ? "Active" : <span className="text-text-muted">Inactive</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (r) =>
        canEdit ? (
          <span className="flex justify-end gap-2">
            {!r.central && r.active && (
              <Button variant="secondary" onClick={() => designate(r, false)}>
                Make central
              </Button>
            )}
            <Button variant="secondary" onClick={() => setEditing(r)}>
              Edit
            </Button>
            {r.active ? (
              <Button
                variant="danger"
                onClick={async () => {
                  try {
                    const { activeUsers } = await ReceptionsApi.dependents(r.id);
                    setDeactivating({ reception: r, activeUsers });
                  } catch (e) {
                    toast(errorMessage(e), "error");
                  }
                }}
              >
                Deactivate
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => setReactivating(r)}>
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
      <h2 className="text-xl font-semibold text-text">Receptions</h2>
      <div className="mt-4">
        <ListToolbar list={list} searchLabel="Search receptions">
          {canEdit && <Button onClick={() => setEditing("new")}>New reception</Button>}
        </ListToolbar>

        {list.data === null ? (
          <LoadingState />
        ) : (
          <DataTable<Reception>
            caption="Receptions"
            columns={columns}
            rows={list.data.items}
            rowKey={(r) => r.id}
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.total}
            sort={{ key: list.params.sort ?? "code", direction: "asc" }}
            onPageChange={(page) => list.update({ page })}
            onSortChange={(sort) => list.update({ sort: sort.key })}
            loading={list.loading}
            empty={
              <EmptyState
                title="No receptions match"
                action={canEdit ? <Button onClick={() => setEditing("new")}>New reception</Button> : undefined}
              />
            }
          />
        )}
      </div>

      {editing && (
        <ReceptionFormDialog
          reception={editing === "new" ? null : editing}
          onDone={(saved) => {
            setEditing(null);
            if (saved) {
              toast(saved, "success");
              list.reload();
            }
          }}
        />
      )}

      {transfer && (
        <ConfirmDialog
          open
          title="Transfer central designation"
          description={
            <>
              Reception <strong>{transfer.currentHolderCode}</strong> currently holds the central
              designation. Move it to <strong>{transfer.reception.code} — {transfer.reception.name}</strong>?
            </>
          }
          confirmLabel="Transfer"
          destructive
          busy={actionBusy}
          onCancel={() => setTransfer(null)}
          onConfirm={() => designate(transfer.reception, true)}
        />
      )}

      {deactivating && (
        <ConfirmDialog
          open
          title="Deactivate reception"
          description={
            <>
              Deactivate reception{" "}
              <strong>
                {deactivating.reception.code} — {deactivating.reception.name}
              </strong>
              ?{" "}
              {deactivating.activeUsers > 0 ? (
                <>
                  <strong>{deactivating.activeUsers}</strong> active user
                  {deactivating.activeUsers === 1 ? " is" : "s are"} stationed there and will keep the
                  assignment.
                </>
              ) : (
                "No active users are stationed there."
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
              await ReceptionsApi.deactivate(deactivating.reception.id);
              toast(`${deactivating.reception.code} deactivated.`, "success");
              list.reload();
            } catch (e) {
              // Includes the would_strand_master_admin refusal, shown verbatim.
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
          title="Reactivate reception"
          description={
            <>
              Reactivate reception{" "}
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
              await ReceptionsApi.reactivate(reactivating.id);
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

function ReceptionFormDialog({
  reception,
  onDone,
}: {
  reception: Reception | null;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState({
    code: reception?.code ?? "",
    name: reception?.name ?? "",
  });
  const [buildings, setBuildings] = useState<Building[]>([]);
  const [buildingId, setBuildingId] = useState("");
  const [floors, setFloors] = useState<Floor[]>([]);
  const [floorId, setFloorId] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const creating = reception === null;

  useEffect(() => {
    if (!creating) return;
    BuildingsApi.list({ active: true, size: 200 })
      .then((p) => setBuildings(p.items))
      .catch(() => setBuildings([]));
  }, [creating]);

  useEffect(() => {
    if (!buildingId) {
      setFloors([]);
      setFloorId("");
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
    try {
      if (reception) {
        await ReceptionsApi.update(reception.id, { code: values.code, name: values.name });
        onDone(`${values.code.toUpperCase()} updated.`);
      } else {
        await ReceptionsApi.create({ floorId, code: values.code, name: values.name });
        onDone(`${values.code.toUpperCase()} created.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      if (fe && ["code", "name"].includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
      } else if (fe && (fe.field === "floorId" || fe.field === "floor_id")) {
        setErrors({ floorId: fe.message });
      } else {
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog
      open
      onClose={() => onDone(null)}
      title={reception ? `Edit ${reception.code}` : "New reception"}
    >
      <form onSubmit={onSubmit} noValidate>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          {creating && (
            <>
              <Field label="Building" hint="Narrows the floor list below." required>
                <Select value={buildingId} onChange={(e) => setBuildingId(e.target.value)}>
                  <option value="">Select a building…</option>
                  {buildings.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.code} — {b.name}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label="Floor" error={errors["floorId"]} required>
                <Select value={floorId} onChange={(e) => setFloorId(e.target.value)}>
                  <option value="">Select a floor…</option>
                  {floors.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.code} — {f.name}
                    </option>
                  ))}
                </Select>
              </Field>
            </>
          )}
          {!creating && (
            <p className="text-sm text-text-muted">
              The floor and the central designation are managed separately; this edits the label only.
            </p>
          )}
          <Field label="Code" error={errors["code"]} hint="Unique within its floor. Stored upper-case." required>
            <Input
              value={values.code}
              onChange={(e) => setValues({ ...values, code: e.target.value })}
              required
              maxLength={32}
              autoFocus={!reception}
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
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {reception ? "Save" : "Create"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
