import type { Metadata } from "next";
import { BrandMark } from "@/components/BrandMark";

export const metadata: Metadata = { title: "Sign in" };

/*
 * Placeholder. US-06.3.1 (story UI-1) replaces this with the real form, the auth BFF and the
 * session store. It exists now so the (auth) route group and the branded login surface
 * (US-06.1.1 AC-2: logo on the login screen) are in place from the first PR.
 */
export default function LoginPage() {
  return (
    <main className="min-h-screen flex flex-col items-center justify-center gap-6 bg-surface-sunken p-8">
      <BrandMark />
      <div className="w-full max-w-sm rounded-lg bg-surface-raised p-8 shadow-raised">
        <h1 className="text-xl font-semibold">Sign in</h1>
        <p className="mt-2 text-sm text-text-muted">
          The sign-in form is delivered by the next story (US-06.3.1).
        </p>
      </div>
    </main>
  );
}
