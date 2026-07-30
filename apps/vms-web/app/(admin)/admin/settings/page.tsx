"use client";

/**
 * System settings (UI-4a over US-04.8.1/2).
 *
 * The catalogue is closed and small, and the API returns it as a bare array in catalogue
 * order — so this screen is a table without pagination, deliberately.
 *
 * The edit dialog renders the control the declared type calls for (BOOLEAN → select,
 * INTEGER → number, STRING → text), but the value always travels as a string — that is the
 * wire contract. Server refusals surface verbatim: the 409 `change_refused` texts (credential-
 * shaped value, WhatsApp while TODO-05 is open) were written for exactly this screen.
 *
 * A `secret: true` value renders as "Set (not shown)" — the payload's asterisks are a
 * redaction marker, not data. Editing needs `settings.manage`; viewing only `masterdata.view`.
 */
import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field, Input, Select } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { ErrorState, LoadingState } from "@/components/ui/states";
import { SettingsApi, errorMessage, type Setting } from "@/lib/admin-api";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAuth } from "@/lib/use-auth";

export default function SettingsPage() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.SETTINGS_MANAGE]);
  const { toast } = useToast();

  const [settings, setSettings] = useState<Setting[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [editing, setEditing] = useState<Setting | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    SettingsApi.list()
      .then(setSettings)
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  if (loading && settings === null) {
    return <LoadingState />;
  }
  if (error) {
    return <ErrorState error={error} retry={load} />;
  }

  return (
    <div>
      <h1 className="text-xl font-semibold text-text">Settings</h1>
      <p className="mt-1 text-sm text-text-muted">
        System-wide configuration. Changes take effect immediately and are audited.
      </p>

      <div className="mt-4 overflow-x-auto rounded-lg border border-border">
        <table className="w-full border-collapse bg-surface text-sm">
          <caption className="sr-only">System settings</caption>
          <thead>
            <tr className="border-b border-border bg-surface-sunken text-left">
              <th scope="col" className="px-4 py-3 font-medium">Setting</th>
              <th scope="col" className="px-4 py-3 font-medium">Value</th>
              <th scope="col" className="px-4 py-3 font-medium">Type</th>
              {canEdit && (
                <th scope="col" className="px-4 py-3">
                  <span className="sr-only">Actions</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {(settings ?? []).map((s) => (
              <tr key={s.key} className="border-b border-border last:border-b-0 align-top">
                <th scope="row" className="px-4 py-3 text-left font-normal">
                  <code className="font-mono text-text">{s.key}</code>
                  <p className="mt-1 max-w-md text-text-muted">{s.description}</p>
                </th>
                <td className="px-4 py-3">
                  {s.secret ? (
                    <span className="text-text-muted">Set (not shown)</span>
                  ) : (
                    <code className="font-mono">{s.value}</code>
                  )}
                  {s.usingDefault && (
                    <span className="ml-2 rounded-full bg-surface-sunken px-2 py-0.5 text-xs text-text-muted">
                      default
                    </span>
                  )}
                </td>
                <td className="px-4 py-3 text-text-muted">{s.type}</td>
                {canEdit && (
                  <td className="px-4 py-3 text-right">
                    <Button variant="secondary" onClick={() => setEditing(s)}>
                      Edit
                    </Button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing && (
        <EditSettingDialog
          setting={editing}
          onDone={(saved) => {
            setEditing(null);
            if (saved) {
              toast(`${saved.key} updated.`, "success");
              load();
            }
          }}
        />
      )}
    </div>
  );
}

function EditSettingDialog({
  setting,
  onDone,
}: {
  setting: Setting;
  onDone: (saved: Setting | null) => void;
}) {
  const [value, setValue] = useState(setting.secret ? "" : setting.value);
  const [fieldMessage, setFieldMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldMessage(null);
    setBusy(true);
    try {
      const saved = await SettingsApi.update(setting.key, value);
      onDone(saved);
    } catch (e) {
      // invalid_value (400), change_refused (409) and unknown_setting (404) all name this key;
      // the message is the controller's, written to be shown.
      setFieldMessage(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={`Edit ${setting.key}`}>
      <form onSubmit={onSubmit} noValidate>
        <p className="mt-2 text-sm text-text-muted">{setting.description}</p>
        <div className="mt-4">
          <Field label="Value" error={fieldMessage}>
            {setting.type === "BOOLEAN" ? (
              <Select value={value} onChange={(e) => setValue(e.target.value)}>
                <option value="true">true</option>
                <option value="false">false</option>
              </Select>
            ) : (
              <Input
                type={setting.type === "INTEGER" ? "number" : "text"}
                value={value}
                onChange={(e) => setValue(e.target.value)}
                placeholder={setting.secret ? "Enter a new value" : undefined}
                autoFocus
              />
            )}
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
