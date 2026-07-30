/**
 * Validates the `?next=` redirect target (US-06.3.1 AC-1).
 *
 * The parameter restores where an unauthenticated user was heading, and anything a URL parameter
 * controls is attacker-writable: a crafted link with `next=https://evil.example/login` would turn
 * our own login page into a phishing hop. So the rule is an allowlist of shape, not a blocklist
 * of schemes — a same-origin absolute path, nothing else.
 *
 * `//host` is the case people forget: it is scheme-relative, and `location.assign("//evil.example")`
 * leaves the site while starting with a slash.
 */
export function safeNextPath(next: string | null | undefined): string | null {
  if (!next) {
    return null;
  }
  if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return null;
  }
  // A path with an embedded scheme or control characters is nothing we ever generate.
  if (next.includes("://") || /[\r\n\t]/.test(next)) {
    return null;
  }
  return next;
}
