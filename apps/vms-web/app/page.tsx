"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { BrandMark } from "@/components/BrandMark";
import { useAuth } from "@/lib/use-auth";

/**
 * The root is a router, not a page: a restored session goes to its home, everyone else to
 * sign-in. The brief branded state below is what shows while the one resume attempt settles.
 */
export default function Home() {
  const auth = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (auth.resuming) {
      return;
    }
    if (!auth.accessToken) {
      router.replace("/login");
    } else if (auth.mustChangePassword) {
      router.replace("/change-password");
    } else {
      router.replace("/admin");
    }
  }, [auth.resuming, auth.accessToken, auth.mustChangePassword, router]);

  return (
    <main className="min-h-screen flex flex-col items-center justify-center gap-6 bg-surface-sunken p-8">
      <BrandMark />
      <p role="status" className="text-text-muted">
        Loading…
      </p>
    </main>
  );
}
