"use client";

/**
 * Visitor types (UI-4a over US-04.5.1).
 *
 * The types are data rows (GUEST, CONTRACTOR, VIP, …), not enum constants — code, name,
 * description, active. Retirement is deactivation: history keeps its classification, new
 * requests stop offering the type.
 */
import { Suspense, useState } from "react";
import { ListToolbar } from "@/components/admin/ListToolbar";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input, Textarea } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  VisitorTypesApi,
  errorMessage,
  fieldError,
  type VisitorType,
  type VisitorTypeRequest,
} from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAdminList } from "@/lib/use-admin-list";
import { useAuth } from "@/lib/use-auth";

export default function VisitorTypesPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <VisitorTypesScreen />
    </Suspense>
  );
}

function VisitorTypesScreen() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();
  const list = useAdminList(VisitorTypesApi.list, { sort: "code" });

  const [editing, setEditing] = useState<VisitorType | "new" | null>(null);
  const [toggling, setToggling] = useState<VisitorType | null>(null);
  const [toggleBusy, setToggleBusy] = useState(false);

  const columns: Column<VisitorType>[] = [
    { key: "code", header: "Code", sortable: true, render: (v) => <code className="font-mono">{v.code}</code> },
    { key: "name", header: "Name", sortable: true, render: (v) => v.name },
    { key: "description", header: "Description", render: (v) => v.description ?? "—" },
    {
      key: "status",
      header: "Status",
      render: (v) => (v.active ? "Active" : <span className="text-text-muted">Inactive</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (v) =>
        canEdit ? (
          <span className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEditing(v)}>
              Edit
            </Button>
            <Button variant={v.active ? "danger" : "secondary"} onClick={() => setToggling(v)}>
              {v.active ? "Deactivate" : "Reactivate"}
            </Button>
          </span>
        ) : null,
    },
  ];

  if (list.error) {
    return <ErrorState error={list.error} retry={list.reload} />;
  }

  return (
    <div>
      <h2 className="text-xl font-semibold text-text">Visitor types</h2>
      <div className="mt-4">
        <ListToolbar list={list} searchLabel="Search visitor types">
          {canEdit && <Button onClick={() => setEditing("new")}>New visitor type</Button>}
        </ListToolbar>

        {list.data === null ? (
          <LoadingState />
        ) : (
          <DataTable<VisitorType>
            caption="Visitor types"
            columns={columns}
            rows={list.data.items}
            rowKey={(v) => v.id}
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.total}
            sort={{ key: list.params.sort ?? "code", direction: "asc" }}
            onPageChange={(page) => list.update({ page })}
            onSortChange={(sort) => list.update({ sort: sort.key })}
            loading={list.loading}
            empty={
              <EmptyState
                title="No visitor types match"
                action={canEdit ? <Button onClick={() => setEditing("new")}>New visitor type</Button> : undefined}
              />
            }
          />
        )}
      </div>

      {editing && (
        <VisitorTypeFormDialog
          visitorType={editing === "new" ? null : editing}
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
          title={toggling.active ? "Deactivate visitor type" : "Reactivate visitor type"}
          description={
            <>
              {toggling.active ? "Deactivate" : "Reactivate"} visitor type{" "}
              <strong>
                {toggling.code} — {toggling.name}
              </strong>
              ?
              {toggling.active &&
                " Existing visitor records keep the classification; new requests stop offering it."}
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
                ? VisitorTypesApi.deactivate(toggling.id)
                : VisitorTypesApi.reactivate(toggling.id));
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

function VisitorTypeFormDialog({
  visitorType,
  onDone,
}: {
  visitorType: VisitorType | null;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState<VisitorTypeRequest>({
    code: visitorType?.code ?? "",
    name: visitorType?.name ?? "",
    description: visitorType?.description ?? "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    const req: VisitorTypeRequest = {
      code: values.code,
      name: values.name,
      description: values.description || null,
    };
    try {
      if (visitorType) {
        await VisitorTypesApi.update(visitorType.id, req);
        onDone(`${req.code.toUpperCase()} updated.`);
      } else {
        await VisitorTypesApi.create(req);
        onDone(`${req.code.toUpperCase()} created.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      if (fe && ["code", "name", "description"].includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
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
      title={visitorType ? `Edit ${visitorType.code}` : "New visitor type"}
    >
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
              autoFocus={!visitorType}
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
          <Field label="Description" error={errors["description"]}>
            <Textarea
              value={values.description ?? ""}
              onChange={(e) => setValues({ ...values, description: e.target.value })}
              maxLength={500}
              rows={3}
            />
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {visitorType ? "Save" : "Create"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
