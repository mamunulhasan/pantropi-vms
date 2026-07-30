"use client";

/**
 * Buildings (UI-4a over US-04.1.1).
 *
 * List with URL-reflected search/filter/sort/paging; create and edit in a dialog;
 * deactivate/reactivate behind a confirm that names the record. There is no delete —
 * the API deliberately has none; retirement is deactivation.
 */
import Link from "next/link";
import { Suspense, useState } from "react";
import { ListToolbar } from "@/components/admin/ListToolbar";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  BuildingsApi,
  errorMessage,
  fieldError,
  type Building,
  type BuildingRequest,
} from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAdminList } from "@/lib/use-admin-list";
import { useAuth } from "@/lib/use-auth";

export default function BuildingsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <BuildingsScreen />
    </Suspense>
  );
}

function BuildingsScreen() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();
  const list = useAdminList(BuildingsApi.list, { sort: "code" });

  const [editing, setEditing] = useState<Building | "new" | null>(null);
  const [toggling, setToggling] = useState<Building | null>(null);
  const [toggleBusy, setToggleBusy] = useState(false);

  const columns: Column<Building>[] = [
    { key: "code", header: "Code", sortable: true, render: (b) => <code className="font-mono">{b.code}</code> },
    { key: "name", header: "Name", sortable: true, render: (b) => b.name },
    { key: "address", header: "Address", render: (b) => b.address ?? "—" },
    {
      key: "status",
      header: "Status",
      render: (b) => (b.active ? "Active" : <span className="text-text-muted">Inactive</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (b) => (
        <span className="flex justify-end gap-2">
          <Link
            href={`/admin/buildings/${b.id}/floors`}
            className="rounded-md border border-border px-3 py-2 text-sm hover:bg-surface-sunken"
          >
            Floors
          </Link>
          {canEdit && (
            <>
              <Button variant="secondary" onClick={() => setEditing(b)}>
                Edit
              </Button>
              <Button variant={b.active ? "danger" : "secondary"} onClick={() => setToggling(b)}>
                {b.active ? "Deactivate" : "Reactivate"}
              </Button>
            </>
          )}
        </span>
      ),
    },
  ];

  if (list.error) {
    return <ErrorState error={list.error} retry={list.reload} />;
  }

  return (
    <div>
      <h1 className="text-xl font-semibold text-text">Buildings</h1>
      <div className="mt-4">
        <ListToolbar list={list} searchLabel="Search buildings">
          {canEdit && <Button onClick={() => setEditing("new")}>New building</Button>}
        </ListToolbar>

        {list.data === null ? (
          <LoadingState />
        ) : (
          <DataTable<Building>
            caption="Buildings"
            columns={columns}
            rows={list.data.items}
            rowKey={(b) => b.id}
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.total}
            sort={
              list.params.sort && list.params.sort !== "createdAt"
                ? { key: list.params.sort, direction: "asc" }
                : { key: "code", direction: "asc" }
            }
            onPageChange={(page) => list.update({ page })}
            onSortChange={(sort) => list.update({ sort: sort.key })}
            loading={list.loading}
            empty={
              <EmptyState
                title="No buildings match"
                description="Adjust the search, or create the building."
                action={canEdit ? <Button onClick={() => setEditing("new")}>New building</Button> : undefined}
              />
            }
          />
        )}
      </div>

      {editing && (
        <BuildingFormDialog
          building={editing === "new" ? null : editing}
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
          title={toggling.active ? "Deactivate building" : "Reactivate building"}
          description={
            <>
              {toggling.active ? "Deactivate" : "Reactivate"} building{" "}
              <strong>
                {toggling.code} — {toggling.name}
              </strong>
              ?
              {toggling.active && " New floors and receptions can no longer reference it."}
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
                ? BuildingsApi.deactivate(toggling.id)
                : BuildingsApi.reactivate(toggling.id));
              toast(
                `${toggling.code} ${toggling.active ? "deactivated" : "reactivated"}.`,
                "success",
              );
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

function BuildingFormDialog({
  building,
  onDone,
}: {
  building: Building | null;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState<BuildingRequest>({
    code: building?.code ?? "",
    name: building?.name ?? "",
    address: building?.address ?? "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    const req: BuildingRequest = {
      code: values.code,
      name: values.name,
      address: values.address || null,
    };
    try {
      if (building) {
        await BuildingsApi.update(building.id, req);
        onDone(`${req.code.toUpperCase()} updated.`);
      } else {
        await BuildingsApi.create(req);
        onDone(`${req.code.toUpperCase()} created.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      if (fe && ["code", "name", "address"].includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
      } else {
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={building ? `Edit ${building.code}` : "New building"}>
      <form onSubmit={onSubmit} noValidate>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          <Field label="Code" error={errors["code"]} hint="Letters, digits, underscore, hyphen. Stored upper-case." required>
            <Input
              value={values.code}
              onChange={(e) => setValues({ ...values, code: e.target.value })}
              required
              maxLength={32}
              autoFocus={!building}
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
          <Field label="Address" error={errors["address"]}>
            <Input
              value={values.address ?? ""}
              onChange={(e) => setValues({ ...values, address: e.target.value })}
              maxLength={500}
            />
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {building ? "Save" : "Create"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
