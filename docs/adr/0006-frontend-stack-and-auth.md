# ADR-0006 — Frontend stack, token handling, and the authorisation of the portal shell

**Status:** accepted · **Date:** 2026-07-30 · **Owner decision:** admin console first, Tailwind

## Context

The backend is complete through the visitor decision loop and the start of EPIC-08, and the owner
has asked to start the UI. The backlog specifies remarkably little about the frontend on purpose:
**Next.js**, TypeScript (implied by typed-accessor ACs), CSS custom properties as the single token
source (F-06.1), and the repository location `apps/vms-web` with role route-groups. No Next.js
version, React version, CSS framework, component library, state-management library, form library,
test runner or accessibility scanner is named anywhere — verified by search across every project
document. Those are open choices, and unrecorded choices become accidents. This ADR records them.

## Decisions

| Choice | Value | Reasoning |
|---|---|---|
| Framework | Next.js 15 (App Router) + TypeScript `strict` | the only stack the backlog names; App Router because the route-group layout in `09-repository-structure.md` is written in its idiom |
| Package manager | npm (bundled with Node 20.18.0) | no workspace convention exists to satisfy; the smallest surface that works. The machine's nvm-managed Node 20 shipped a **partially-extracted npm** (`@npmcli/config` missing — the likely reason the earlier Node 20 attempt was abandoned); repaired by replacing `node_modules/npm` from the official 10.8.2 tarball, original kept as `npm.broken-partial` |
| Styling | Tailwind, configured to **consume** the F-06.1 CSS custom properties rather than define its own palette | owner's choice. The single-token-source AC (US-06.1.1 AC-1) stays honest: tokens live in `styles/tokens.css`, Tailwind maps them, and a rebrand is a token edit |
| Component primitives | hand-rolled on native elements | F-06.2's ACs are behavioural (focus traps, focus-not-on-destructive-default, server-driven tables) and owning that behaviour is the point; no component library was authorised and none is needed for the admin console's shapes |
| Data fetching | a typed `lib/api.ts` over `fetch`, plus a small hook | react-query and friends earn their weight with caching/polling stories, which are later milestones (US-07.6.2's hook); until then they are a dependency without a job |
| Tests | Vitest + Testing Library; Playwright + axe-core for the F-06.4 scan | nothing specified; these are the current boring defaults, and axe satisfies "fails naming the element and the success criterion" |
| Accessibility target | **WCAG 2.1 AA, provisional** | the SRS states usability but names no standard; the backlog itself flags AA as derived and awaiting client confirmation (phase-1:2916). Adopted so there is a bar to test against; recorded as provisional so nobody mistakes it for a client requirement |

## Token handling (US-06.3.1 AC-3/AC-6)

The ACs are prescriptive: refresh token unreadable by page JS; access token in memory only; nothing
in `localStorage`, URLs, query strings or history; exactly one refresh in flight. The API returns
the refresh token **in the login response body** (`AuthController.TokenResponse`), so a purely
browser-side client cannot satisfy AC-3 — whatever JS receives, JS can read.

**Decision: Next.js route handlers as an auth-only BFF.**

- `app/api/session/login|refresh|logout` run on the portal server. They call the API, and keep the
  refresh token in an **HttpOnly / Secure / SameSite=Strict** cookie scoped to the portal origin
  and the `/api/session` path. Page JS never sees it.
- The browser holds the **access token in memory only** and calls the API **directly** with
  `Authorization: Bearer` — CORS already allows the portal origin, and proxying every data request
  through Next would add a hop and a second place to enforce nothing.
- A **single-flight refresh interceptor**: concurrent 401s queue behind one
  `POST /api/session/refresh`; on failure, state is cleared and there is exactly one redirect to
  `/login?next=…`. No retry storms (AC-5/AC-6).
- Logout calls the API's `/auth/logout` (server-side session revocation — the security event),
  then clears the cookie and all client state (T-06.3.1.3).

The alternative — changing the API to set the cookie itself — was rejected because the API serves
more clients than this portal (the slave display, potentially kiosk hardware), and a cookie
contract with one caller's origin does not belong in it.

One small backend change **is** taken: `GET /api/v1/auth/me` gains the caller's **permission set**
and display name (T-06.3.2.1 requires permissions to travel with the session; the
`EffectivePermissions` use case already computes them). Navigation binds to permission codes from a
typed constant set mirroring `Permissions.java` — an unknown code is a compile error on both sides.

## Authorisation note (TODO-01)

F-06.2 (component library) and F-06.3 (shell/navigation) are `TDD-DERIVED` — backlog-only, pending
TODO-01, exactly the position EPIC-03/04 were in before ADR-0004. The same disposition applies: the
admin console the owner has asked for cannot exist without a shell to sit in, so F-06.2/F-06.3 are
treated as **approved enablers**, bounded to what the Phase 1 screens need. The portal never makes
an authorization decision that matters (phase-1:2635): hiding navigation is usability, and every
role-driven UI behaviour keeps its server-side denial test.

## Consequences

- A rebrand is an edit to `styles/tokens.css` + environment config — no component changes (AC-3).
- The BFF is three small route handlers; if the API ever standardises cookie-based auth, they
  collapse into configuration.
- Deferred and tracked, not dropped: the token-literal lint rule (T-06.1.1.3), visual regression
  baseline (T-06.2.1.3), and the client-tamper E2E (T-06.3.2.3, lands with the UI-5 a11y story).
