import type { Metadata } from "next";

export const metadata: Metadata = { title: "Administration" };

/*
 * Placeholder for the admin console home. The authenticated shell (US-06.3.1) and
 * permission-driven navigation (US-06.3.2) wrap this group before any real screen lands here;
 * until then this page carries no data and no controls.
 */
export default function AdminHome() {
  return (
    <main className="p-8">
      <h1 className="text-2xl font-semibold">Administration</h1>
      <p className="mt-2 text-text-muted">
        Admin screens arrive with stories UI-4a/4b, behind the authenticated shell.
      </p>
    </main>
  );
}
