# Pull Request

<!--
Title must follow Conventional Commits and name the user story:
  feat(visitor): submit visitor entry request (US-04.1.1)
  fix(acs): retry queue drops request after dead-letter (US-12.3.1)
-->

## Traceability

| Link | Value |
|---|---|
| **SRS requirement** | <!-- FR-VMS-01 (SRS B1) --> |
| **Epic** | <!-- #12 EPIC-04 --> |
| **Feature** | <!-- #34 F-04.1 --> |
| **User story** | Closes # |
| **Tasks** | <!-- #57, #58 --> |
| **Milestone** | <!-- M2 --> |

> A PR that cannot name a requirement does not merge. If this is an approved enabler
> (EPIC-01 / EPIC-14), write `ENABLER` and link the justification.

## What changed

<!-- Describe the change and why. Reference the acceptance criteria it satisfies. -->

## Acceptance criteria satisfied

<!-- Copy the AC ids from the user story and mark each. -->

- [ ] AC-1 —
- [ ] AC-2 —

---

## Project rules

- [ ] **One user story only.** This PR implements exactly one user story.
- [ ] **No unrelated code modified.** Every changed file is required by this story.
- [ ] **No invented functionality.** Nothing here goes beyond the named requirement (project rule 2).
- [ ] **Feature branch.** Not committed directly to `develop` or `main`.
- [ ] **Conventional Commits** used for every commit in this branch.

## Tests

- [ ] Unit tests added or updated
- [ ] Integration tests added or updated
- [ ] Negative and edge cases from the user story are covered
- [ ] All tests pass locally and in CI
- [ ] Coverage has not regressed

<!-- Name the key tests and what they prove. -->

## Architecture

- [ ] Clean Architecture layer boundaries respected — no inward dependency violations
- [ ] SOLID principles applied
- [ ] DDD boundaries respected where applicable (aggregate consistency, no cross-aggregate writes)
- [ ] **No ACS-specific type appears outside the ACS integration module** (CON-01, CON-02, NFR-MNT-01)

## API (if a REST contract changed)

- [ ] Resource-oriented paths, correct HTTP verbs and status codes
- [ ] Request and response schemas documented (OpenAPI)
- [ ] Backward compatible, or a versioning/deprecation plan is stated below
- [ ] Pagination, filtering and error format consistent with existing endpoints

## Security (OWASP)

- [ ] Authorisation enforced at the API boundary; endpoint denies by default
- [ ] Input validated and output encoded
- [ ] No secrets in source, configuration files, or logs
- [ ] Personal data handling reviewed — no visitor PII in logs or error messages
- [ ] Audit events emitted for state changes
- [ ] Dependency scan clean

## Accessibility (if a screen changed)

> The CI `accessibility` job fails on critical/serious axe violations. Automation catches at most
> half of WCAG — the manual list is in
> [docs/project/16-accessibility.md](../docs/project/16-accessibility.md).

- [ ] `npm run e2e` passes locally (or N/A — no screen changed)
- [ ] New or changed routes are covered by the scan list in `e2e/a11y.spec.ts`
- [ ] Keyboard-only walk done: focus order, visible focus, dialog trap and restore
- [ ] Screen-reader pass on the changed screen: labels, announced errors, table headers
- [ ] 200% zoom and 320 px reflow checked
- [ ] Colour is not the only carrier of meaning
- [ ] Any axe suppression is narrowed to rule + element and carries a written reason

## Database

- [ ] Migration is versioned, forward-only, and reversible or documented as irreversible
- [ ] Migration tested against a populated database
- [ ] No breaking change to a column another service reads

## Documentation

> Project rule: **every PR must update documentation.**

- [ ] `docs/project/02-traceability-matrix.md` — delivery log row added, coverage dashboard updated
- [ ] API documentation updated (if the contract changed)
- [ ] ADR added for any significant architectural decision
- [ ] README / runbook updated if operational behaviour changed
- [ ] If this resolves a TODO, `docs/project/07-open-questions.md` updated

## Deployability

- [ ] Independently deployable
- [ ] Feature flag: `<!-- name, or N/A -->`
- [ ] Rollback plan stated

## Reviewer notes

<!-- Anything a reviewer should look at closely. Call out deliberate trade-offs. -->
