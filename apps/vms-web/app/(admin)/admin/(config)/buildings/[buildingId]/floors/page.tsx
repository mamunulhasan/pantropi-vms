"use client";

/**
 * Floors of one building (UI-4a over US-04.2.1).
 *
 * The parent building comes from the path and only the path — the API scopes every floor
 * operation to it (a floor reached through the wrong building 404s), and there is no way to
 * move a floor between buildings. Codes are unique per building, so L01 may exist in every
 * tower. `levelNo` is optional: a mezzanine has no level number, and the default sort puts
 * those last.
 */
import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ListToolbar } from "@/components/admin/ListToolbar";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  BuildingsApi,
  FloorsApi,
  errorMessage,
  fieldError,
  type Building,
  type Floor,
  type FloorRequest,
  type ListParams,
} from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAdminList } from "@/lib/use-admin-list";
import { useAuth } from "@/lib/use-auth";

export default function FloorsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <FloorsScreen />
    </Suspense>
  );
}

function FloorsScreen() {
  const { buildingId } = useParams<{ buildingId: string }>();
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();

  const fetcher = useCallback(
    (params: ListParams) => FloorsApi.list(buildingId, params),
    [buildingId],
  );
  const list = useAdminList(fetcher, { sort: "levelNo" });

  // The floor list for an unknown building is an empty page, not a 404 — so the heading
  // resolves the building itself, which does 404 and turns a guessed URL into an error.
  const [building, setBuilding] = useState<Building | null>(null);
  const [buildingError, setBuildingError] = useState<unknown>(null);
  useEffect(() => {
    let cancelled = false;
    BuildingsApi.get(buildingId)
      .then((b) => {
        if (!cancelled) setBuilding(b);
      })
      .catch((e: unknown) => {
        if (!cancelled) setBuildingError(e);
      });
    return () => {
      cancelled = true;
    };
  }, [buildingId]);

  const [editing, setEditing] = useState<Floor | "new" | null>(null);
  const [toggling, setToggling] = useState<Floor | null>(null);
  const [toggleBusy, setToggleBusy] = useState(false);

  const columns: Column<Floor>[] = [
    {
      key: "levelNo",
      header: "Level",
      sortable: true,
      render: (f) => (f.levelNo === null ? "—" : f.levelNo),
    },
    { key: "code", header: "Code", sortable: true, render: (f) => <code className="font-mono">{f.code}</code> },
    { key: "name", header: "Name", sortable: true, render: (f) => f.name },
    {
      key: "status",
      header: "Status",
      render: (f) => (f.active ? "Active" : <span className="text-text-muted">Inactive</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (f) =>
        canEdit ? (
          <span className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEditing(f)}>
              Edit
            </Button>
            <Button variant={f.active ? "danger" : "secondary"} onClick={() => setToggling(f)}>
              {f.active ? "Deactivate" : "Reactivate"}
            </Button>
          </span>
        ) : null,
    },
  ];

  if (buildingError) {
    return <ErrorState error={buildingError} />;
  }
  if (list.error) {
    return <ErrorState error={list.error} retry={list.reload} />;
  }

  return (
    <div>
      <nav aria-label="Breadcrumb" className="text-sm text-text-muted">
        <Link href="/admin/buildings" className="hover:text-brand hover:underline">
          Buildings
        </Link>
        {" / "}
        <span aria-current="page" className="text-text">
          {building ? `${building.code} — ${building.name}` : "…"}
        </span>
      </nav>
      <h2 className="mt-2 text-xl font-semibold text-text">Floors</h2>

      <div className="mt-4">
        <ListToolbar list={list} searchLabel="Search floors">
          {canEdit && <Button onClick={() => setEditing("new")}>New floor</Button>}
        </ListToolbar>

        {list.data === null ? (
          <LoadingState />
        ) : (
          <DataTable<Floor>
            caption={`Floors of ${building?.code ?? "building"}`}
            columns={columns}
            rows={list.data.items}
            rowKey={(f) => f.id}
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.total}
            sort={{ key: list.params.sort ?? "levelNo", direction: "asc" }}
            onPageChange={(page) => list.update({ page })}
            onSortChange={(sort) => list.update({ sort: sort.key })}
            loading={list.loading}
            empty={
              <EmptyState
                title="No floors yet"
                description="Create the building's floors to hang tenants and receptions from."
                action={canEdit ? <Button onClick={() => setEditing("new")}>New floor</Button> : undefined}
              />
            }
          />
        )}
      </div>

      {editing && (
        <FloorFormDialog
          buildingId={buildingId}
          floor={editing === "new" ? null : editing}
          onDone={(saved) => {
            setEditing(null);
            if (saved) {
              toast(saved, "success");
              list.reload();
            }
          }}
        />
      )}

      {toggling && (
        <ConfirmDialog
          open
          title={toggling.active ? "Deactivate floor" : "Reactivate floor"}
          description={
            <>
              {toggling.active ? "Deactivate" : "Reactivate"} floor{" "}
              <strong>
                {toggling.code} — {toggling.name}
              </strong>{" "}
              of {building?.code ?? "this building"}?
            </>
          }
          confirmLabel={toggling.active ? "Deactivate" : "Reactivate"}
          destructive={toggling.active}
          busy={toggleBusy}
          onCancel={() => setToggling(null)}
          onConfirm={async () => {
            setToggleBusy(true);
            try {
              await (toggling.active
                ? FloorsApi.deactivate(buildingId, toggling.id)
                : FloorsApi.reactivate(buildingId, toggling.id));
              toast(`${toggling.code} ${toggling.active ? "deactivated" : "reactivated"}.`, "success");
              list.reload();
            } catch (e) {
              toast(errorMessage(e), "error");
            } finally {
              setToggleBusy(false);
              setToggling(null);
            }
          }}
        />
      )}
    </div>
  );
}

function FloorFormDialog({
  buildingId,
  floor,
  onDone,
}: {
  buildingId: string;
  floor: Floor | null;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState({
    code: floor?.code ?? "",
    name: floor?.name ?? "",
    levelNo: floor?.levelNo === null || floor === null ? "" : String(floor.levelNo),
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    const req: FloorRequest = {
      code: values.code,
      name: values.name,
      levelNo: values.levelNo.trim() === "" ? null : Number(values.levelNo),
    };
    try {
      if (floor) {
        await FloorsApi.update(buildingId, floor.id, req);
        onDone(`${req.code.toUpperCase()} updated.`);
      } else {
        await FloorsApi.create(buildingId, req);
        onDone(`${req.code.toUpperCase()} created.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      if (fe && ["code", "name", "levelNo"].includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
      } else {
        // e.g. buildingId no longer active — not a field on this form.
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={floor ? `Edit ${floor.code}` : "New floor"}>
      <form onSubmit={onSubmit} noValidate>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          <Field label="Code" error={errors["code"]} hint="Unique within this building." required>
            <Input
              value={values.code}
              onChange={(e) => setValues({ ...values, code: e.target.value })}
              required
              maxLength={32}
              autoFocus={!floor}
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
          <Field
            label="Level number"
            error={errors["levelNo"]}
            hint="Optional; −20 to 200. Leave empty for mezzanines and plant rooms."
          >
            <Input
              type="number"
              min={-20}
              max={200}
              value={values.levelNo}
              onChange={(e) => setValues({ ...values, levelNo: e.target.value })}
            />
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {floor ? "Save" : "Create"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
