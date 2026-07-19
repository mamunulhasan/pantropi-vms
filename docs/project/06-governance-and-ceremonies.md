# Governance, Roles & Ceremonies

---

## Roles

| Role | Accountable for | Decides |
|---|---|---|
| **Technical Product Manager** | Requirements integrity, backlog priority, client liaison | Scope, priority, descope decisions |
| **Lead Architect** | Architecture, traceability, technical dependencies, UAL liaison | Technical design, ADRs, boundary rules |
| **Scrum Master** | Flow, ceremonies, impediment removal, WIP discipline | Process, sprint mechanics |
| **Senior Java Architect** | Back-end design, Clean Architecture / DDD / SOLID adherence | Implementation patterns, code standards |
| **Developers** | Delivering stories to the Definition of Done | How a story is implemented within the standards |
| **Client — CPG Corporation** | Requirements sign-off, clarifications, acceptance | Scope, TODO dispositions, UAT sign-off |
| **ACS Vendor — UAL** | The ACS API contract and its test endpoint | ACS capabilities and constraints |

### Decision escalation

| Question type | Decided by | Recorded in |
|---|---|---|
| "Is this in scope?" | Technical PM, against the requirements catalogue | Issue disposition |
| "The documents disagree" | Lead Architect raises; client dispositions | Discrepancy register (D-nn) |
| "The requirement is ambiguous" | Client or UAL answers | Open questions register (TODO-nn) |
| "How do we build it?" | Lead / Senior Java Architect | ADR |
| "Can we ship without X?" | Technical PM + client | Milestone exit review |

---

## Ceremonies

| Ceremony | Cadence | Purpose | Key output |
|---|---|---|---|
| **Backlog refinement** | Weekly | Decompose features to stories; apply Definition of Ready | Stories moved to `status/ready` |
| **Sprint planning** | Per sprint | Commit to a sprint goal and story set | Sprint backlog |
| **Daily stand-up** | Daily | Flow and impediments | Blockers surfaced same-day |
| **Sprint review** | Per sprint | Demonstrate to the client; **run the reverse trace** | Accepted stories; unbacked artefacts closed |
| **Sprint retrospective** | Per sprint | Improve the process | Actions with owners |
| **Traceability audit** | Per milestone | Verify the chain end to end | Signed traceability matrix |
| **Dependency review** | Weekly | Chase TODO-01, TODO-02, TODO-19 with client and UAL | Updated open-questions register |

> The **dependency review** is not optional on this project. Two external blockers gate 54% of the
> requirements. Chasing them weekly, in writing, is the single highest-leverage activity available
> to us while they remain open.

---

## Sprint review — the reverse trace

At every sprint review, walk the reverse-trace table in the
[traceability matrix](02-traceability-matrix.md#reverse-trace--is-anything-being-built-without-a-requirement).

For each artefact delivered this sprint, ask: **which requirement asked for this?**

- Names an SRS requirement → accepted.
- Approved enabler → accepted, justification re-confirmed.
- Neither → **closed as out of scope, and the code is removed.**

This is the control that makes project rule 2 real rather than aspirational. Requirements creep into
a system through good intentions, not bad ones.

---

## Definition of Ready / Done

Canonical versions live in
[05-workflow-and-branching.md](05-workflow-and-branching.md#5-definition-of-ready-user-story) and are
mirrored as checkboxes in the user story issue template. They are not restated here — one source of
truth per rule.

---

## Milestone exit review

A milestone closes only when:

- [ ] Every story in the milestone meets the Definition of Done
- [ ] Every requirement mapped to the milestone is verified by an integration test
- [ ] The traceability matrix is populated through the PR column for all milestone rows
- [ ] The reverse trace shows no unbacked artefacts
- [ ] The release is deployed to staging and demonstrated to the client
- [ ] Open questions arising in the milestone are logged and dispositioned
- [ ] Documentation is current

---

## Requirements change control

Requirements change. It is managed, not resisted.

1. Change requested → logged as a **Spike / Clarification** issue.
2. Technical PM assesses scope, cost, and schedule impact.
3. Lead Architect assesses technical impact and dependencies.
4. Client approves in writing.
5. **The SRS is updated and re-baselined** (B1 → B2). The requirements catalogue and traceability
   matrix are updated in the same change.
6. Affected epics, features and stories are revised.

**A requirement that is not in the SRS is not a requirement**, regardless of who asked for it or how
reasonable it sounds. Verbal scope changes are the most common way traceability dies.

---

## Metrics

Tracked per sprint; reported at milestone exit.

| Metric | Why |
|---|---|
| Requirement coverage (traced / total) | Proves nothing was missed |
| Requirement readiness (ready / total) | Shows how much of the SRS we can actually act on |
| Requirements verified by test | The only honest measure of "done" |
| Unbacked artefacts found in reverse trace | Detects scope creep early |
| Open blocking TODOs, and their age | Makes external dependencies visible to the client |
| Stories completed vs committed | Predictability |
| Escaped defects by severity | Quality |
| Coverage trend | Test-discipline erosion |

The metric to watch on this project is **age of open blocking TODOs**. It is the number that
explains the schedule, and it belongs in front of the client every week.
