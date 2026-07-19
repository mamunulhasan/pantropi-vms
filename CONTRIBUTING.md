# Contributing to Project Pinnacle VMS

Read this before your first commit. The full workflow reference lives in
[`docs/project/05-workflow-and-branching.md`](docs/project/05-workflow-and-branching.md); this page
is the short version plus the non-negotiables.

---

## The ten rules

1. **Never implement functionality that is not in the SRS.**
   Check the [requirements catalogue](docs/project/01-requirements-catalogue.md). If it is not there
   with an `SRS` provenance marker, it is not buildable — even if the TDD or the database schema
   implies it. See [discrepancies](docs/project/08-source-document-discrepancies.md) for why.
2. **If a requirement is ambiguous, raise a clarification. Never guess.**
   Check [open questions](docs/project/07-open-questions.md) first; it may already be logged.
3. **One user story at a time.** No parallel stories per developer.
4. **Never modify unrelated code.** Found a separate problem? Raise an issue.
5. **Every change has tests.** Unit for logic, integration for every acceptance criterion.
6. **Every PR updates documentation** — at minimum the traceability matrix delivery log.
7. **Every feature is independently deployable** — behind a flag, backward compatible, rollback-able.
8. **Feature branches only.** Never commit directly to `develop` or `main`.
9. **Conventional Commits**, naming the user story id.
10. **Follow Clean Architecture, SOLID, DDD, REST and OWASP practices.** Enforced in review and,
    where possible, in CI.

---

## Workflow

### 1. Pick up a story

- It must be `status/ready` and meet the [Definition of Ready](docs/project/05-workflow-and-branching.md#5-definition-of-ready-user-story).
- Confirm you have no other story in `status/in-progress`.
- If it is `status/blocked`, do not start it. Blocked means we would have to invent a requirement.
- Assign yourself; move it to `status/in-progress`.

### 2. Branch

```bash
git checkout develop && git pull
git checkout -b feature/US-04.1.1-tenant-submit-visitor-request
```

### 3. Build it

- Write the test first where practical; every acceptance criterion needs an integration test.
- Stay inside the story. Scope creep is the most common review rejection on this project.
- Keep `domain` free of frameworks. Keep ACS types inside the ACS module.

### 4. Commit

```
feat(visitor): submit visitor entry request (US-04.1.1)

Tenants can submit a visitor entry request with visitor details and a
requested time window.

Requirement: FR-VMS-01 (SRS B1)
Closes #56
```

Requirement ids are **qualified with the baseline** — `FR-VMS-01 (SRS B1)` — because IDs were
renumbered between SRS versions and a bare id is ambiguous (discrepancy D-02).

### 5. Open a PR

Target `develop`. The template checklist is not decoration — a PR that cannot name its requirement
does not merge. Fill in the traceability table, tick the rule checks honestly, and say plainly if
something is not done.

### 6. Review

- ≥1 approval; ≥2 for `area/acs-integration` and anything labelled `security`
- CODEOWNERS approval required
- All status checks green
- Reviewers check the diff implements **exactly one story and nothing else**

### 7. Merge

Squash-merge to `develop`, keeping the Conventional Commit subject. Update the traceability matrix
delivery log if you have not already. Move the issue to done.

---

## Local setup

Arrives with EPIC-01 (M1). It will be:

```bash
docker compose up -d      # PostgreSQL, Redis, Kafka
./mvnw verify             # build + unit + integration tests
```

---

## Security expectations

Every contributor is expected to apply OWASP practices, not defer them to a review gate:

- Endpoints deny by default; authorisation is explicit
- Validate all input; encode all output
- **No secrets in source, configuration, or logs** — secrets live in a secrets manager (FR-API-03)
- **No visitor personal data in logs, error messages, or issue descriptions**
- Emit audit events for every state change
- Parameterised queries only

Anything touching visitor personal data gets the `privacy/pii` label and a data-protection review.

---

## Raising a clarification

Use the **Spike / Clarification** issue template. State precisely what is unclear, quote the
ambiguous source text, say what is blocked, and present the readings you can see with a
recommendation. Then add it to [open questions](docs/project/07-open-questions.md).

You are not slowing the project down by doing this. You are preventing a wrong assumption from
becoming an undocumented requirement that someone discovers in UAT.

---

## Questions

- **Requirements or scope** → Technical Product Manager
- **Architecture** → Lead Architect
- **Process or ceremonies** → Scrum Master
- **ACS integration** → Lead Architect (do not contact UAL directly without coordination)
