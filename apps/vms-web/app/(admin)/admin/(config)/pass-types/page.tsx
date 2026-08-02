"use client";

/**
 * Pass types (UI-4a over US-04.6.1).
 *
 * The two enum fields render as selects over the exact wire values (`qr`/`rfid`,
 * `time_bound`/`one_time`) — the database's spelling, not the Java constant names.
 * Validity is 1–744 hours (a full month); the server refuses zero and above.
 */
import { Suspense, useState } from "react";
import { ListToolbar } from "@/components/admin/ListToolbar";
import { ActiveTag } from "@/components/ui/StatusTag";
import { PageHeader } from "@/components/ui/PageHeader";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input, Select } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  CREDENTIAL_TYPES,
  PassTypesApi,
  RESTRICTION_TYPES,
  errorMessage,
  fieldError,
  type CredentialType,
  type PassType,
  type PassTypeRequest,
  type RestrictionType,
} from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAdminList } from "@/lib/use-admin-list";
import { useAuth } from "@/lib/use-auth";

const CREDENTIAL_LABELS: Record<CredentialType, string> = {
  qr: "QR code",
  rfid: "RFID card",
};

const RESTRICTION_LABELS: Record<RestrictionType, string> = {
  time_bound: "Time-bound (valid for its window)",
  one_time: "One-time (spent on first use)",
};

export default function PassTypesPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <PassTypesScreen />
    </Suspense>
  );
}

function PassTypesScreen() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();
  const list = useAdminList(PassTypesApi.list, { sort: "code" });

  const [editing, setEditing] = useState<PassType | "new" | null>(null);
  const [toggling, setToggling] = useState<PassType | null>(null);
  const [toggleBusy, setToggleBusy] = useState(false);

  const columns: Column<PassType>[] = [
    { key: "code", header: "Code", sortable: true, render: (p) => <code className="font-mono">{p.code}</code> },
    { key: "name", header: "Name", sortable: true, render: (p) => p.name },
    { key: "credential", header: "Credential", render: (p) => CREDENTIAL_LABELS[p.defaultCredential] },
    { key: "restriction", header: "Restriction", render: (p) => RESTRICTION_LABELS[p.defaultRestriction] },
    {
      key: "validHours",
      header: "Valid (hours)",
      sortable: true,
      align: "right",
      render: (p) => p.defaultValidHours,
    },
    {
      key: "status",
      header: "Status",
      render: (p) => <ActiveTag active={p.active} />,
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (p) =>
        canEdit ? (
          <span className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEditing(p)}>
              Edit
            </Button>
            <Button variant={p.active ? "danger" : "secondary"} onClick={() => setToggling(p)}>
              {p.active ? "Deactivate" : "Reactivate"}
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
      <PageHeader title="Pass types" level={2} />
      <div className="mt-4">
        <ListToolbar list={list} searchLabel="Search pass types">
          {canEdit && <Button onClick={() => setEditing("new")}>New pass type</Button>}
        </ListToolbar>

        {list.data === null ? (
          <LoadingState />
        ) : (
          <DataTable<PassType>
            caption="Pass types"
            columns={columns}
            rows={list.data.items}
            rowKey={(p) => p.id}
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.total}
            sort={{
              key: list.params.sort === "validHours" ? "validHours" : (list.params.sort ?? "code"),
              direction: "asc",
            }}
            onPageChange={(page) => list.update({ page })}
            onSortChange={(sort) => list.update({ sort: sort.key })}
            loading={list.loading}
            empty={
              <EmptyState
                title="No pass types match"
                action={canEdit ? <Button onClick={() => setEditing("new")}>New pass type</Button> : undefined}
              />
            }
          />
        )}
      </div>

      {editing && (
        <PassTypeFormDialog
          passType={editing === "new" ? null : editing}
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
          title={toggling.active ? "Deactivate pass type" : "Reactivate pass type"}
          description={
            <>
              {toggling.active ? "Deactivate" : "Reactivate"} pass type{" "}
              <strong>
                {toggling.code} — {toggling.name}
              </strong>
              ?
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
                ? PassTypesApi.deactivate(toggling.id)
                : PassTypesApi.reactivate(toggling.id));
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

function PassTypeFormDialog({
  passType,
  onDone,
}: {
  passType: PassType | null;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState({
    code: passType?.code ?? "",
    name: passType?.name ?? "",
    defaultCredential: passType?.defaultCredential ?? ("qr" as CredentialType),
    defaultRestriction: passType?.defaultRestriction ?? ("time_bound" as RestrictionType),
    defaultValidHours: passType ? String(passType.defaultValidHours) : "12",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    const req: PassTypeRequest = {
      code: values.code,
      name: values.name,
      defaultCredential: values.defaultCredential,
      defaultRestriction: values.defaultRestriction,
      defaultValidHours: Number(values.defaultValidHours),
    };
    try {
      if (passType) {
        await PassTypesApi.update(passType.id, req);
        onDone(`${req.code.toUpperCase()} updated.`);
      } else {
        await PassTypesApi.create(req);
        onDone(`${req.code.toUpperCase()} created.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      const formFields = ["code", "name", "defaultCredential", "defaultRestriction", "defaultValidHours"];
      if (fe && formFields.includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
      } else {
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={passType ? `Edit ${passType.code}` : "New pass type"}>
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
              autoFocus={!passType}
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
          <Field label="Default credential" error={errors["defaultCredential"]} required>
            <Select
              value={values.defaultCredential}
              onChange={(e) =>
                setValues({ ...values, defaultCredential: e.target.value as CredentialType })
              }
            >
              {CREDENTIAL_TYPES.map((c) => (
                <option key={c} value={c}>
                  {CREDENTIAL_LABELS[c]}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Default restriction" error={errors["defaultRestriction"]} required>
            <Select
              value={values.defaultRestriction}
              onChange={(e) =>
                setValues({ ...values, defaultRestriction: e.target.value as RestrictionType })
              }
            >
              {RESTRICTION_TYPES.map((r) => (
                <option key={r} value={r}>
                  {RESTRICTION_LABELS[r]}
                </option>
              ))}
            </Select>
          </Field>
          <Field
            label="Default validity (hours)"
            error={errors["defaultValidHours"]}
            hint="1 to 744 (a full month)."
            required
          >
            <Input
              type="number"
              min={1}
              max={744}
              value={values.defaultValidHours}
              onChange={(e) => setValues({ ...values, defaultValidHours: e.target.value })}
              required
            />
          </Field>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {passType ? "Save" : "Create"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
