# Project Pinnacle VMS — Working Agreement

Read this before doing anything in this repository.

## What this project is

An enterprise Visitor Management System for CPG Corporation at Westgate Tower, built by Pantropi
Limited. VMS owns the visitor lifecycle. The Access Control System (ACS) — barriers, readers,
physical enforcement — is an **external system reached only through an API**, supplied by Universal
Automations Ltd.

## Source of truth

| Document | Path |
|---|---|
| SRS | `docs/VMS_Only_SRS_Project_Pinnacle.docx` |
| TDD | `docs/VMS Technical Design Document.docx` |
| Schema | `docs/vms_schema_postgresql.sql` |

**The SRS is authoritative for requirements. The TDD and schema are design input only** — they may
inform *how* we build something, never *whether* it may be built. See `docs/adr/0003-requirements-baseline-b1.md`.

The requirements catalogue at `docs/project/01-requirements-catalogue.md` is the working index. Every
requirement there carries a provenance marker. Only `SRS`, `SRS-NFR` and `SRS-CON` items are buildable.

## Hard rules

1. **Never implement functionality that is not in the SRS.** Not from the TDD, not from the schema,
   not because it seems obviously needed. The schema in particular contains ~14 tables with no
   requirement backing.
2. **Never guess at an ambiguous requirement.** Add it to `docs/project/07-open-questions.md` and
   raise a Spike issue. There are already 19 open questions — check there first.
3. **One user story at a time.** Never more.
4. **Never modify unrelated code.** Found something else broken? Raise an issue.
5. **Every change has tests** — unit for logic, integration for every acceptance criterion.
6. **Every PR updates documentation**, minimally the traceability matrix delivery log.
7. **Feature branches only.** Never commit to `develop` or `main`.
8. **Conventional Commits**, naming the user story id.
9. **Qualify every requirement reference** — `FR-VMS-01 (SRS B1)`, never a bare id. IDs were
   renumbered between SRS versions (discrepancy D-02) and a bare id is genuinely ambiguous.
10. **No ACS-specific type outside the ACS integration module.** This is the load-bearing
    architectural rule — see `docs/adr/0002-acs-anti-corruption-layer.md`.

## Project state

Phase M0. **No application code exists yet, deliberately.** Two blocking external dependencies:

- **TODO-01** — the TDD is written against an "SRS v2" with 96 requirements. We have 28. ~50
  referenced requirement IDs are undefined.
- **TODO-02** — the ACS API contract has not been published by UAL. Blocks 15 of 28 requirements.

9 of 28 requirements are ready to implement and depend on neither. That work is EPIC-01, EPIC-02,
EPIC-04, EPIC-12 (partial), EPIC-13, and the EPIC-10 email path.

## Where things live

```
docs/project/01-requirements-catalogue.md    what may be built
docs/project/02-traceability-matrix.md       requirement → … → release; update on every PR
docs/project/03-epics-and-backlog.md         epics, features, 33 written user stories
docs/project/04-milestones-and-release-plan.md  sequence and dependencies
docs/project/05-workflow-and-branching.md    branching, commits, DoR/DoD, architecture rules
docs/project/06-governance-and-ceremonies.md roles, ceremonies, change control
docs/project/07-open-questions.md            the 19 open TODOs
docs/project/08-source-document-discrepancies.md  where the documents disagree (D-01 … D-09)
docs/adr/                                    architecture decisions
scripts/bootstrap-github.sh                  creates labels, milestones, epic issues (needs gh)
```

## Architecture

Clean Architecture, Java + Spring Boot, PostgreSQL, Redis, Kafka, Next.js front end (TDD §3).
Dependencies point inward. `domain` has no framework imports. Bounded contexts follow TDD §4.

Layer rules and the ACS-boundary rule are enforced by architecture fitness tests in CI, not by
convention — because on this project the ACS boundary is the entire mitigation for the missing API
contract.

## Before you claim something is done

If it touches ACS, "passing tests against our simulator" is **not** done. It is *done against
simulator*. Nothing ACS-dependent is verified until it runs against the UAL non-production endpoint.
Say which one you mean.
