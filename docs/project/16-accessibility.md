# Accessibility — the automated gate and the manual checklist

**Target:** WCAG 2.1 level AA, **provisional**. The SRS states usability but names no standard, and
the backlog flags AA as derived and awaiting client confirmation. Adopted so there is a bar to test
against; recorded as provisional so nobody mistakes it for a client requirement
([ADR-0006](../adr/0006-frontend-stack-and-auth.md)).

Story: **UI-5**, feature **F-06.4**.

---

## 1. The automated gate (CI job `accessibility`)

Playwright drives a **production build** of the portal with the API stubbed at the browser's
network boundary; axe-core scans every route and both dialog states.

| | |
|---|---|
| Runs on | every PR touching `apps/vms-web/**` |
| Rule set | `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` |
| **Fails the build on** | **critical** and **serious** violations |
| Reports but does not fail on | moderate and minor (printed as `[a11y advisory]` lines) |
| Also runs | the client-tamper check (T-06.3.2.3) and the session-exposure checks |

Scanned: `/login` (clean and with a failed sign-in), `/change-password`, and every `/admin` route —
`/admin`, `settings`, `buildings`, `buildings/{id}/floors`, `tenants`, `receptions`,
`visitor-types`, `pass-types`, `holidays`, `users`, `roles` — plus a form dialog, a destructive
confirm dialog and the settings edit dialog **while open**, because a modal's markup is where
labelling and focus problems actually live.

Structural assertions beyond axe: exactly one `h1` per screen, a `main` and a named `navigation`
landmark on every admin route, and the skip link reachable on the first `Tab` and visible when
focused.

### Why critical/serious and not everything

A gate that fires on every advisory finding — including ones nobody has triaged and some that are
false in context — gets disabled or routinely overridden within a month, and a gate people switch
off protects nobody. Critical and serious are the impacts that correspond to a user being
*blocked*. Raising the bar later is a one-line change in `apps/vms-web/e2e/a11y.spec.ts`.

### Run it locally

```bash
cd apps/vms-web && npm run build && npm run e2e
```

First run only, to fetch the browser:

```bash
cd apps/vms-web && npx playwright install chromium
```

The report after a failure (CI also uploads it as an artefact):

```bash
cd apps/vms-web && npm run e2e:report
```

### What the gate does *not* prove

The API is stubbed, so the tamper check establishes that **the client** reveals nothing and issues
no request it should not. **Server-side authorization is proven where it lives:**
`ApiAuthorizationIT` exercises the real interceptor against a real database. Do not read the E2E
suite as evidence about the API.

---

## 2. Manual checklist

Automation catches perhaps half of WCAG at best — it cannot judge whether a label is *meaningful*,
whether focus order is *logical*, or whether an error message *tells you what to do*. Walk this
list when a PR adds or changes a screen. Tick it in the PR body.

### Keyboard only (unplug the mouse)

- [ ] Every control is reachable by `Tab`, and the order follows the visual order.
- [ ] Nothing is reachable that shouldn't be — no focus stops on decorative elements.
- [ ] Focus is **always visible**, on every control, against its background.
- [ ] Dialogs: focus moves in on open, `Tab` cycles inside, `Escape` closes, focus returns to the
      control that opened it.
- [ ] A destructive confirm does **not** default-focus the destructive button.
- [ ] No keyboard trap: you can always get out of every widget.
- [ ] Skip-to-content works and lands in `main`.

### Screen reader (NVDA on Windows, VoiceOver on macOS)

- [ ] Every form control announces a label — and the label says what the field wants.
- [ ] A validation error is announced, and it says how to fix the problem, not just "invalid".
- [ ] Tables announce their caption, and column headers are read with the cells.
- [ ] A toast is announced without interrupting the current utterance.
- [ ] Loading and error states are announced (`status` / `alert`), not silent.
- [ ] Icon-only controls have a name that isn't "button".

### Visual and reflow

- [ ] Zoom to **200%**: nothing is clipped, no horizontal page scroll.
- [ ] Zoom to **400%** / 320 px wide: content reflows to one column and stays usable.
- [ ] Colour is never the only carrier of meaning — status, validity and selection each have text
      or shape as well.
- [ ] Text spacing override (line-height 1.5, letter-spacing 0.12em) breaks nothing.
- [ ] `prefers-reduced-motion` is honoured by any transition worth noticing.

### Content

- [ ] The page `title` says which screen you are on.
- [ ] Headings describe the sections; levels are not skipped for styling.
- [ ] Link text makes sense read out of context (no bare "click here").
- [ ] A confirm prompt **names the record** it is about.

---

## 3. When a finding is genuinely false

Do not add a blanket exclusion. Narrow it to the rule and the element, and write the reason next to
it in `e2e/a11y.spec.ts` — a suppression without a stated reason is indistinguishable from a bug
someone gave up on. If the finding is real but out of the PR's scope, raise it as a task and link
it in the PR body instead of suppressing it.
