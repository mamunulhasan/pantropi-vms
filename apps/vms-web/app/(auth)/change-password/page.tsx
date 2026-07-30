"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { BrandMark } from "@/components/BrandMark";
import { apiFetch, ApiError } from "@/lib/api";
import { clearMustChangePassword } from "@/lib/auth-store";
import { useAuth } from "@/lib/use-auth";

/**
 * The forced password change (US-06.3.1, closing the loop US-02.3.1 opened).
 *
 * The API refuses every other authenticated call while `mustChangePassword` is set, so a user
 * routed anywhere else would see nothing but errors — this screen is the only way forward, and
 * the login page routes here unconditionally when the flag is up.
 *
 * The policy failure message from the API is shown as-is: it was written not to reveal which
 * rule matched (US-02.3.1), and this page must not editorialise on top of it.
 */
export default function ChangePasswordPage() {
  const router = useRouter();
  const auth = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    const form = new FormData(event.currentTarget);
    const next = String(form.get("newPassword") ?? "");
    if (next !== String(form.get("confirmPassword") ?? "")) {
      setError("The new passwords do not match.");
      return;
    }

    setSubmitting(true);
    try {
      await apiFetch<null>("/api/v1/auth/password", {
        method: "POST",
        body: JSON.stringify({
          currentPassword: String(form.get("currentPassword") ?? ""),
          newPassword: next,
        }),
      });
      clearMustChangePassword();
      router.replace("/admin");
    } catch (e) {
      setError(e instanceof ApiError && e.detail
        ? e.detail
        : "The password could not be changed. Check the current password and the policy.");
    } finally {
      setSubmitting(false);
    }
  }

  if (!auth.resuming && !auth.accessToken) {
    router.replace("/login");
    return null;
  }

  return (
    <main className="min-h-screen flex flex-col items-center justify-center gap-6 bg-surface-sunken p-8">
      <BrandMark />
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg bg-surface-raised p-8 shadow-raised"
        noValidate
      >
        <h1 className="text-xl font-semibold">Change your password</h1>
        <p className="mt-2 text-sm text-text-muted">
          Your password must be changed before you can continue.
        </p>

        {error && (
          <p role="alert" className="mt-4 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {error}
          </p>
        )}

        <div className="mt-4">
          <label htmlFor="currentPassword" className="block text-sm font-medium">
            Current password
          </label>
          <input
            id="currentPassword"
            name="currentPassword"
            type="password"
            required
            autoComplete="current-password"
            autoFocus
            className="mt-1 w-full rounded-md border border-border px-3 py-2"
          />
        </div>

        <div className="mt-4">
          <label htmlFor="newPassword" className="block text-sm font-medium">
            New password
          </label>
          <input
            id="newPassword"
            name="newPassword"
            type="password"
            required
            autoComplete="new-password"
            className="mt-1 w-full rounded-md border border-border px-3 py-2"
          />
        </div>

        <div className="mt-4">
          <label htmlFor="confirmPassword" className="block text-sm font-medium">
            Confirm new password
          </label>
          <input
            id="confirmPassword"
            name="confirmPassword"
            type="password"
            required
            autoComplete="new-password"
            className="mt-1 w-full rounded-md border border-border px-3 py-2"
          />
        </div>

        <button
          type="submit"
          disabled={submitting}
          className="mt-6 w-full rounded-md bg-brand px-4 py-2 text-brand-contrast hover:bg-brand-hover disabled:opacity-60"
        >
          {submitting ? "Changing…" : "Change password"}
        </button>
      </form>
    </main>
  );
}
