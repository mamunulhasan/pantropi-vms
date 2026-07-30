"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { AccessDenied } from "@/components/AccessDenied";
import { BrandMark } from "@/components/BrandMark";
import { homeFor } from "@/lib/nav";
import { useAuth } from "@/lib/use-auth";

/**
 * The root is a router, not a page: it sends a restored session to the area its permissions
 * actually open, and everyone else to sign-in. The brief branded state below is what shows while
 * the one resume attempt settles.
 *
 * Before VJ-1 this always went to /admin, which was fine while /admin was the only area — but a
 * TENANT holds none of the admin permissions, so the moment the tenant area existed that redirect
 * would have walked them into the no-access wall. Routing by permission uses the same registry the
 * navigation and the shells use, so an area cannot be reachable in the nav and unreachable from
 * the front door.
 */
export default function Home() {
  const auth = useAuth();
  const router = useRouter();

  const home = homeFor(auth.me?.permissions);

  useEffect(() => {
    if (auth.resuming) {
      return;
    }
    if (!auth.accessToken) {
      router.replace("/login");
    } else if (auth.mustChangePassword) {
      router.replace("/change-password");
    } else if (home) {
      router.replace(home);
    }
    // No home: the render below says so rather than looping through a redirect.
  }, [auth.resuming, auth.accessToken, auth.mustChangePassword, home, router]);

  // Signed in, session settled, and no area to enter — an account whose grants open nothing.
  if (!auth.resuming && auth.accessToken && auth.me && !auth.mustChangePassword && !home) {
    return (
      <AccessDenied
        username={auth.me.username}
        description="Your account has no areas assigned yet. Ask an administrator to review your role."
      />
    );
  }

  return (
    <main className="min-h-screen flex flex-col items-center justify-center gap-6 bg-surface-sunken p-8">
      <BrandMark />
      <p role="status" className="text-text-muted">
        Loading…
      </p>
    </main>
  );
}
