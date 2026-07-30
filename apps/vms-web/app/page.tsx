import Link from "next/link";
import { BrandMark } from "@/components/BrandMark";

/*
 * Placeholder landing. UI-1 replaces this with a redirect: authenticated users go to their
 * role's home, everyone else to /login. It exists now so the scaffold renders something
 * token-styled end to end.
 */
export default function Home() {
  return (
    <main className="min-h-screen flex flex-col items-center justify-center gap-6 bg-surface-sunken p-8">
      <BrandMark />
      <p className="text-text-muted max-w-md text-center">
        Visitor management portal. Sign-in arrives with the next story; the design tokens,
        branding and build pipeline land here first.
      </p>
      <Link
        href="/login"
        className="rounded-md bg-brand px-6 py-2 text-brand-contrast hover:bg-brand-hover"
      >
        Sign in
      </Link>
    </main>
  );
}
