"use client";

import { FormEvent, Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { BrandMark } from "@/components/BrandMark";
import { getSnapshot, login } from "@/lib/auth-store";
import { homeFor } from "@/lib/nav";
import { safeNextPath } from "@/lib/safe-next";

/**
 * Sign-in (US-06.3.1; the branded login surface of US-06.1.1 AC-2).
 *
 * One neutral failure message, whatever went wrong. The API spent a story making wrong-password,
 * locked and unknown-account indistinguishable (US-02.3.1 AC-5); a chattier message here would
 * hand back exactly the oracle that work removed.
 *
 * The `?next=` restore goes through {@link safeNextPath}: it is attacker-writable, so only a
 * same-origin path is ever followed.
 *
 * With no `next=`, the landing page comes from {@link homeFor} — the principal's permissions decide
 * it. A hardcoded "/admin" here worked only while the console was the only area; once the tenant
 * screens shipped it walked every tenant into the no-access wall.
 */
function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const form = new FormData(event.currentTarget);
      const session = await login(
        String(form.get("username") ?? ""),
        String(form.get("password") ?? ""),
      );
      if (!session) {
        setError("Sign-in failed. Check your details and try again.");
        return;
      }
      const next = safeNextPath(searchParams.get("next"));
      // login() loaded the profile into the store before returning; read the grants from there.
      const home = homeFor(getSnapshot().me?.permissions) ?? "/";
      router.replace(session.mustChangePassword ? "/change-password" : (next ?? home));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="min-h-screen flex flex-col items-center justify-center gap-6 bg-surface-sunken p-8">
      <BrandMark />
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg bg-surface-raised p-8 shadow-raised"
        noValidate
      >
        <h1 className="text-xl font-semibold">Sign in</h1>

        {error && (
          <p role="alert" className="mt-4 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {error}
          </p>
        )}

        <div className="mt-4">
          <label htmlFor="username" className="block text-sm font-medium">
            Username
          </label>
          <input
            id="username"
            name="username"
            type="text"
            required
            autoComplete="username"
            autoFocus
            className="mt-1 w-full rounded-md border border-border px-3 py-2"
          />
        </div>

        <div className="mt-4">
          <label htmlFor="password" className="block text-sm font-medium">
            Password
          </label>
          <input
            id="password"
            name="password"
            type="password"
            required
            autoComplete="current-password"
            className="mt-1 w-full rounded-md border border-border px-3 py-2"
          />
        </div>

        <button
          type="submit"
          disabled={submitting}
          className="mt-6 w-full rounded-md bg-brand px-4 py-2 text-brand-contrast hover:bg-brand-hover disabled:opacity-60"
        >
          {submitting ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </main>
  );
}

export default function LoginPage() {
  // useSearchParams requires a Suspense boundary under the App Router.
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
