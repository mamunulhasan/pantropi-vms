# API Authorization — Deny-by-Default

**Stories:** US-03.2.1, US-03.2.2 · **Requirement:** NFR-SEC-01 (SRS B1) · OWASP ASVS V4, V7 · TDD §7

How every request to the VMS API is authenticated, authorized, denied and recorded.

---

## The rule

> **A route is reachable only if it says so.** Everything else is denied.

There are exactly three ways for a request to succeed:

| | Condition | Declared by |
|---|---|---|
| 1 | The path is public | [`PublicRoutes`](../../apps/vms-api/vms-interfaces/src/main/java/com/pantropi/vms/interfaces/rest/security/PublicRoutes.java) |
| 2 | Valid token + live session | `@RequiresAuthentication` |
| 3 | Valid token + live session + permission | `@RequiresPermission("...")` |

A handler that declares nothing is **refused at runtime even for an administrator**, and fails the
build via the coverage test. This replaced an earlier design in which two path-scoped interceptors
guarded `/api/v1/auth/**` and `/api/v1/admin/**`: any controller added on an unlisted path would
have been entirely public. Deny-by-default removes that whole class of mistake.

## Decision order

```
OPTIONS (CORS preflight)         → allow, CORS config decides
path in PublicRoutes             → allow
no / malformed Bearer token      → 401
token fails signature or expiry  → 401
session revoked or expired       → 401   ← the session store, not the token, is the authority
session owes a password change   → 403   ← unless the path is a password-change route
handler declares nothing         → 403   ← deny-by-default
declared permission not held     → 403
otherwise                        → allow, principal published from the token
```

Authentication failures are **401**; an authenticated principal lacking a permission is **403**.
The distinction is deliberate and tested: a revoked session must not masquerade as an authorization
problem (US-03.2.1 AC-6).

The decision happens in `preHandle` — **before the handler and before any read of the target
resource** — so a denial performs no work and cannot reveal whether the resource exists.

## Forced password change (US-02.3.1)

An account marked `must_change_password` — set by an administrator reset, or by rotation — gets a
session that can reach only three routes:

| Path | Why |
|---|---|
| `POST /api/v1/auth/password` | The way out |
| `GET /api/v1/auth/me` | So a client can render who is being asked |
| `POST /api/v1/auth/logout` | Abandoning is always allowed |

Everything else is **403**, including routes the user's role would otherwise permit.

Two design points worth keeping:

- The flag is read from the **session row**, which `preHandle` already fetches — so the gate costs
  no extra query.
- It is **not** a token claim. A claim could not be withdrawn before the token expired, so a user
  who completed the change would stay confined until then. Refreshing carries the flag forward from
  the session, so rotation is not an escape either. Both are tested.

## Tenant and floor scoping (US-03.4.1)

Authorization answers *may you call this route*. Scoping answers *which rows may you see* — a
separate question, decided in `ScopePolicy` and nowhere else.

The interceptor opens a request's scope only **after** the authorization decision passes, and clears
it in `afterCompletion` — which runs even when the handler threw. Servlet threads are pooled, so a
scope left behind would be inherited by the next request on that thread: one user's tenant isolation
silently applied to another user's query, with every query still looking correct.

The shipped policy is strict own-scope per ADR-0004; everything undeterminable resolves to seeing
nothing. [ADR-0005](../adr/0005-tenant-and-floor-scoping-seam.md) records the mechanism, the
divergence from the backlog's deny-all, and what changes when TODO-14 is answered.

`ScopingRulesTest` fails the build naming any adapter that reads scope-sensitive data without
consulting the policy, and forbids anything outside the policy from constructing an unrestricted
filter.

## The principal

`AuthenticatedPrincipal` is derived **solely** from the validated token. No request body, query
parameter or other header can influence it, so a client cannot assert a different acting user
(AC-4). A test posts a body carrying someone else's id and asserts the audit records the token's
owner.

## Public route allowlist

Four exact paths and one prefix, each with its reason in the source. Widening it is a security
decision that belongs in a pull request:

| Path | Why it must be public |
|---|---|
| `/api/v1/auth/login` | Mints the first token; cannot require one |
| `/api/v1/auth/refresh` | Access token may already be expired |
| `/api/v1/auth/activate` | Reached with a single-use activation token (US-02.2.2) |
| `/error` | Container error dispatch; never a business route |
| `/actuator/health` | Liveness; exposure limited to health by configuration |

A test asserts the allowlist's exact contents, so widening it cannot pass unnoticed.

## Denial handling

Every error body is an RFC 7807 problem detail of a fixed shape, rendered through one writer
(`ProblemDetails`) used by both the interceptor and the exception advice, so the two paths cannot
drift:

```json
{ "type": "...", "title": "...", "status": 403, "detail": "...", "correlationId": "..." }
```

It never carries a stack trace, an internal class name, a SQL fragment, or any indication of whether
the target exists. A denied request for an **existing** resource and for a **non-existent** one
produce identical bodies — asserted by test.

An unhandled exception becomes a generic 500 whose detail exists only in the server log, joined to
the response by the correlation id.

## Denial recording

One audit row per denial in `vms.audit_logs`, carrying actor (or `anonymous`), attempted permission,
route, method, outcome and source IP — and **never** request body, query string or header values. A
denial for a request containing a visitor email records none of it (asserted by test).

A Micrometer counter `vms.security.denials` is dimensioned by **outcome and route class only** — no
user identifier in any label, so the metric cannot become a side channel. Crossing the configured
per-principal threshold increments the unlabelled `vms.security.denial_threshold_exceeded`.

**Recording never changes the decision.** An audit or metrics failure is logged and swallowed: a
denial still denies, and never escalates into a 500. This was found in testing — the first
implementation turned an audit-table outage into a 500 on every unauthenticated request.

## CORS and CSRF

CORS is restricted to the portal origins configured per environment
(`vms.security.cors.allowed-origins`). **No profile configures a wildcard origin**, and credentials
are not allowed because the API is bearer-token based, not cookie based.

CSRF protection is deliberately absent and not required: the API is stateless, issues no session
cookie, and rejects any request without an `Authorization` header, so a browser cannot be induced to
authenticate a cross-site request ambiently.

## Deviations

| Deviation | Reason | Resolution |
|---|---|---|
| Interceptor instead of the Spring Security filter chain (T-03.2.1.1) | Spring Security is not available in this build — only its BOM is cached and Maven Central is blocked. Same documented deviation as US-02.1.1. | Decision points are isolated in `AuthorizationInterceptor`; adopting Spring Security replaces that one class. |
| No cached effective-permission set (T-03.2.1.2) | No Redis **client** is obtainable — see the note below; the permission check is a direct query. | `PermissionChecker` is a port — a caching decorator drops in without touching callers. |
| Lockout counters in PostgreSQL, not Redis (T-03.2.1.2 / US-02.3.1 T-02.3.1.2) | No Redis **client** is obtainable — see the note below. Same substitution already accepted for sessions in US-02.1.2. | `LoginAttemptStore` is a port; a Redis adapter replaces it without touching the use case. |
| PBKDF2-HMAC-SHA256 (210k) instead of Argon2id (US-02.3.1 AC-2) | No Argon2 or bcrypt library is obtainable — Maven Central returns 403. | Hashes carry a versioned prefix (`pbkdf2_sha256$…`), so rehash-on-login can migrate them silently once a library is available. |

### Note on Redis (corrected 2026-07-28)

Earlier revisions of this table said "Redis is unavailable". **That is no longer the accurate
reason, and the distinction matters for whoever picks these up.**

A Redis server *is* running locally and answers `PING`. What is missing is a usable Java client:

- `spring-data-redis` — not in the Gradle cache, and Maven Central returns 403 from this network.
- `lettuce-core` — present in `~/.m2` (5.2.0, 5.3.4), but its transitive `netty` there is
  **4.0.37** (2016) while Lettuce 5.3.x requires netty 4.1.x. The stack will not assemble offline.

So the blocker is a **jar, not a server**. Anyone revisiting `US-04.9.1`, the permission cache or the
lockout counters should check dependency resolution first — on a network where Maven Central is
reachable (GitHub Actions runners, for instance) these become straightforwardly buildable, and each
already sits behind a port precisely so the adapter is the only thing that changes.

## Adding an endpoint

1. Annotate the handler or its controller with `@RequiresPermission("...")` or
   `@RequiresAuthentication`.
2. If it genuinely must be public, add the path to `PublicRoutes` **with a reason**, and update the
   allowlist assertion in `RouteAuthorizationCoverageIT`.
3. Run the build. `RouteAuthorizationCoverageIT` fails naming the route if you forgot.
