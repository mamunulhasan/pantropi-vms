/**
 * The access-denied page state (US-06.3.2 AC-4/AC-5).
 *
 * Shown when a signed-in user reaches a screen their permissions don't cover — a typed URL, a
 * stale bookmark, or a grant edit that landed mid-session. It says who they are signed in as
 * (the commonest cause is the right person on the wrong account) and deliberately nothing about
 * what the screen would have shown: the server's 403 discloses nothing and neither does this.
 */
export function AccessDenied({
  username,
  description = "Your account doesn't have access to this area.",
}: {
  username?: string;
  description?: string;
}) {
  return (
    <main
      id="main-content"
      className="min-h-screen flex flex-col items-center justify-center gap-3 bg-surface p-8 text-center"
    >
      <h1 className="text-xl font-semibold">You don&rsquo;t have access</h1>
      <p className="max-w-md text-sm text-text-muted">{description}</p>
      {username && (
        <p className="text-sm text-text-muted">
          Signed in as <span className="font-medium text-text">{username}</span>. If you expected
          access, ask an administrator to review your role.
        </p>
      )}
    </main>
  );
}
