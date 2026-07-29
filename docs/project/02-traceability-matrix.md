# Requirements Traceability Matrix (RTM) — Baseline B1

**Chain:** Requirement → Milestone → Epic → Feature → User Story → Task → Code → Test → PR → Release

This matrix is the project's contractual record that every requirement is accounted for, and that
nothing is built without a requirement. **It is a living document** — the Task, Code, Test, PR and
Release columns are populated as work completes, and no PR may merge without updating its row.

**Baseline:** B1 — 2026-07-19
**Scope:** 28 SRS functional requirements · 8 NFRs · 3 constraints
**Backlog:** 4 phases · 19 epics · 93 features · **178 user stories** · **524 tasks** · **743 story points**

**Status key**

- ✅ **Ready** — every story authorized and unblocked
- 🟠 **Sim-only** — buildable now against the ACS simulator, but not *verifiable* until TODO-02
- 🟡 **Partial (n/m)** — n of m stories are workable; the rest are blocked
- 🔴 **Blocked** — every story blocked

A requirement is **Blocked** only when *all* of its stories are. Most are **Partial**: the core
behaviour is buildable while a dependent slice — usually master data (TODO-01) — waits.

---

## Forward trace — Requirement → delivery

| Requirement | Phase | Epic(s) | Stories | Status | Gating |
|---|---|---|---|---|---|
| **FR-VMS-01** | 2 | EPIC-07 | 7 | ✅ Ready | — |
| **FR-VMS-02** | 2, 4 | EPIC-07, EPIC-17 | 7 | 🟡 Partial (6/7) | TODO-01 |
| **FR-VMS-03** | 2 | EPIC-08 | 5 | ✅ Ready | — |
| **FR-VMS-04** | 2, 3 | EPIC-08, EPIC-12 | 4 | ✅ Ready | — |
| **FR-VMS-05** | 2 | EPIC-09 | 4 | 🟠 Sim-only | TODO-02 |
| **FR-VMS-06** | 2 | EPIC-09, EPIC-10 | 4 | 🟡 Partial (2/4) | TODO-01, TODO-02 |
| **FR-VMS-07** | 2 | EPIC-09 | 5 | 🟡 Partial (4/5) | TODO-02, TODO-17 |
| **FR-VMS-08** | 3 | EPIC-12, EPIC-13 | 5 | 🟡 Partial (2/5) | TODO-12, TODO-13, TODO-18 |
| **FR-VMS-09** | 3 | EPIC-13 | 2 | 🟡 Partial (1/2) | TODO-07 |
| **FR-VMS-10** | 3 | EPIC-13 | 2 | 🔴 Blocked | TODO-02, TODO-03, TODO-19 |
| **FR-VMS-11** | 1, 2, 4 | EPIC-04, EPIC-08, EPIC-09, EPIC-17 | 7 | 🟡 Partial (5/7) | TODO-01, TODO-02 |
| **FR-VMS-12** | 3 | EPIC-14 | 2 | 🔴 Blocked | TODO-02, TODO-09 |
| **FR-VMS-13** | 1, 2 | EPIC-04, EPIC-09 | 3 | 🟡 Partial (2/3) | TODO-01, TODO-02 |
| **FR-VMS-14** | 3 | EPIC-14 | 2 | 🟡 Partial (1/2) | TODO-07 |
| **FR-VMS-15** | 2, 3 | EPIC-09, EPIC-12 | 3 | 🔴 Blocked | TODO-02, TODO-17 |
| **FR-CRD-01** | 3 | EPIC-15 | 2 | ✅ Ready | — |
| **FR-CRD-02** | 3 | EPIC-15 | 2 | 🔴 Blocked | TODO-02, TODO-11 |
| **FR-CRD-03** | 3, 4 | EPIC-12, EPIC-15, EPIC-17 | 8 | 🟡 Partial (1/8) | TODO-01, TODO-02, TODO-10, TODO-11 |
| **FR-NOT-01** | 2, 4 | EPIC-09, EPIC-16, EPIC-17 | 9 | 🟡 Partial (7/9) | TODO-02, TODO-05, TODO-10 |
| **FR-NOT-02** | 3, 4 | EPIC-11, EPIC-12, EPIC-17 | 4 | 🟡 Partial (3/4) | TODO-01, TODO-02 |
| **FR-REP-01** | 3, 4 | EPIC-11, EPIC-13, EPIC-18 | 8 | 🟡 Partial (6/8) | TODO-02, TODO-03, TODO-12, TODO-13 |
| **FR-REP-02** | 3, 4 | EPIC-15, EPIC-18 | 2 | 🔴 Blocked | TODO-11 |
| **FR-API-01** | 2, 3, 4 | EPIC-09, EPIC-11, EPIC-13, EPIC-17 | 10 | 🟡 Partial (7/10) | TODO-01, TODO-02, TODO-07 |
| **FR-API-02** | 1, 3 | EPIC-05, EPIC-11, EPIC-14 | 4 | 🟡 Partial (2/4) | TODO-01, TODO-12 |
| **FR-API-03** | 1, 3 | EPIC-05, EPIC-11 | 4 | 🟡 Partial (1/4) | TODO-02, TODO-12 |
| **FR-ADM-01** | 1 | EPIC-02, EPIC-04 | 2 | 🟡 Partial (1/2) | TODO-01 |
| **FR-ADM-02** | 1 | EPIC-02, EPIC-04, EPIC-06 | 7 | 🟡 Partial (2/7) | TODO-01, TODO-08, TODO-15 |
| **FR-ADM-03** | 1, 4 | EPIC-06, EPIC-19 | 3 | 🟡 Partial (1/3) | TODO-01, TODO-16 |

**Coverage: 28 / 28 SRS functional requirements decomposed to user story level (100%).**
Full story and task detail lives in [`backlog/`](backlog/); this table is the index into it.

---

## Non-functional requirement trace

| Requirement | Phase | Epic(s) | Verification method | Status |
|---|---|---|---|---|
| NFR-PRF-01 | 3 | EPIC-11 | Performance test at the reception UI | 🔴 **Untestable — no target (TODO-07)** |
| NFR-REL-01 | 3 | EPIC-11 | Chaos test: ACS unavailable during issuance | ✅ Ready |
| NFR-AVL-01 | 1, 3 | EPIC-01, EPIC-11 | Uptime monitoring; ACS-independence test | 🟡 Operating hours undefined |
| NFR-SEC-01 | 1 | EPIC-02, EPIC-03, EPIC-05 | OWASP ASVS review + RBAC integration tests | ✅ Ready |
| NFR-SCL-01 | 1 | EPIC-01 | Load test: 500 visitors/day, 120 concurrent users | ✅ Ready |
| NFR-USA-01 | 1 | EPIC-06 | Usability review with reception staff | 🟡 No acceptance criteria; WCAG target is derived |
| NFR-MNT-01 | 1, 3 | EPIC-01, EPIC-11 | **Architecture fitness test — no ACS type outside the integration module** | ✅ Ready |
| NFR-CMP-01 | 1 | EPIC-05 | Data protection impact assessment | 🔴 Blocked — TODO-06, TODO-12 |

| Constraint | Enforced by | Verification |
|---|---|---|
| CON-01 | EPIC-11 | Fitness test — no hardware-specific type anywhere in the codebase |
| CON-02 | EPIC-11 (F-11.1) | Fitness test — no credential mutation path bypasses the ACS port |
| CON-03 | EPIC-06 (F-06.1) | Design review against client brand guidelines |

---

## Reverse trace — is anything being built without a requirement?

Run at **every sprint review**. Any row that cannot name a requirement is either an approved enabler
or is closed as out of scope. This is the control that makes project rule 2 real rather than
aspirational.

### Measured provenance across all 178 user stories

| Provenance | Stories | Share | Implementable? |
|---|---|---|---|
| `SRS` | 67 | 38% | ✅ Yes |
| `SRS-NFR` | 19 | 11% | ✅ Yes |
| `SRS-CON` | 3 | 2% | ✅ Yes |
| `ENABLER` | 16 | 9% | ✅ Yes — justified against an NFR |
| `TDD-DERIVED` | 62 | 35% | ⚠️ **No — backlog only, pending TODO-01** |
| `BLOCKED` | 11 | 6% | ⛔ No |

**105 stories (59%) are authorized. 73 (41%) are not.**

### Concentration by phase

| Phase | Stories | TDD-derived | Blocked | Authorized |
|---|---|---|---|---|
| 1 — Platform Foundation | 42 | 18 | 2 | 22 (52%) |
| 2 — Visitor Registration & Pass Generation | 45 | 12 | 0 | **33 (73%)** |
| 3 — Entry Verification & ACS Integration | 55 | 13 | 5 | 37 (67%) |
| 4 — Reporting & Notification | 36 | 19 | 4 | 13 (36%) |

Phase 2 is the least contaminated by unbacked scope, which is exactly why it is the phase that can
be delivered and demonstrated while TODO-01 remains open. Phase 4 is the most — only a third of it
is authorized today.

### Named unbacked artefacts

| Artefact | Backing | Verdict |
|---|---|---|
| EPIC-01 Engineering Platform | *none* | ✅ Approved enabler — NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 |
| EPIC-04 Configuration & Master Data | *none* | 🔴 **Unbacked** — TODO-01, D-05 |
| EPIC-10 Host Management | *none* | 🔴 **Unbacked** — D-13 |
| EPIC-19 Analytics & Export | *none* | 🔴 **Unbacked** — TODO-16, D-03 |
| F-14.4 Manual override | *none* | 🔴 **Must not be built** — TODO-04, D-06 |
| F-05.1 Append-only audit log | *none* | ⚠️ TDD-only — TODO-01 |
| F-03.1 / F-03.3 Role & permission model | *none* | ⚠️ TDD-only; **but NFR-SEC-01 does require RBAC** |
| F-17.3/4/5 Host notify, reminders, alerts | *none* | ⚠️ TDD-only — TODO-01 |
| `visitors.id_document_ref` | *none* | 🔴 **Unbacked** — TODO-13 |
| `visitor_status.no_show`, `notify_channel.in_app` | *none* | 🔴 **Unbacked** — D-07 |
| `credential.override`, `report.export` permissions | *none* | 🔴 **Unbacked** — D-12 |
| Edge gateway gate verification | *contradicts SRS §1.2* | 🔴 **Out of scope** — D-08, TODO-19 |

---

## PR-time traceability contract

Every pull request must:

1. Name its user story in the title — `feat(visitor): submit entry request (US-07.1.1)`
2. Link the story issue → feature → epic → requirement
3. Add a row to the delivery log below
4. Update documentation

**A PR that cannot name a requirement does not merge.**

## Delivery log

*Populated as work completes. One row per merged user story.*

| Story | Requirement | Tasks | Key modules | Tests | PR | Merged | Release |
|---|---|---|---|---|---|---|---|
| US-01.1.1 | ENABLER — NFR-MNT-01 (SRS B1) | T-01.1.1.1/2/3 | `.gitattributes`, `.github/CODEOWNERS`, `.github/branch-protection/`, `scripts/{apply,verify,test}-branch-protection.sh` | unit 22/22; integration 2/2 branches; AC-4 proven live (GH006) | [#27](https://github.com/mamunulhasan/pantropi-vms/pull/27) | 2026-07-19 | 0.1.0 |
| US-01.1.2 | ENABLER — NFR-MNT-01 (SRS B1) | T-01.1.2.1/2 | `scripts/lint-commit-message.sh`, `scripts/install-git-hooks.sh`, `.github/workflows/commit-lint.yml`, `.github/branch-protection/` | unit 22/22; check required + exercised on own PR | [#29](https://github.com/mamunulhasan/pantropi-vms/pull/29) | 2026-07-19 | 0.1.0 |
| US-01.2.1 | ENABLER — NFR-MNT-01, CON-01, CON-02 (SRS B1) | T-01.2.1.1/2/3 | `apps/vms-api/` — 5 Gradle modules, 34 context packages, ClockPort/SystemClockAdapter | unit 8/8 (domain purity); integration 3/3 (health UP, no business endpoints, port wiring) | [#30](https://github.com/mamunulhasan/pantropi-vms/pull/30) | 2026-07-19 | 0.1.0 |
| US-01.2.2 | ENABLER — NFR-MNT-01, CON-01, CON-02 (SRS B1) | T-01.2.2.1/2/3 | `vms-architecture-tests` (13 ArchUnit rules), `.github/workflows/architecture-fitness.yml` | 13/13 rules pass; AC-4 violation injected → build failed naming class → removed → green; check required on both branches | [#31](https://github.com/mamunulhasan/pantropi-vms/pull/31) | 2026-07-19 | 0.1.0 |
| US-01.5.1 | ENABLER — NFR-MNT-01 (SRS B1) | T-01.5.1.1/2/3 | `db/migration/V1__baseline.sql`, `V2__reference_seed.sql`, Flyway wiring in `vms-bootstrap` | 5/5 ITs on real PostgreSQL (Zonky): schema-diff, no-op rerun, idempotent seed, failure naming, checksum tamper abort | [#32](https://github.com/mamunulhasan/pantropi-vms/pull/32) | 2026-07-19 | 0.1.0 |
| US-01.4.1 | ENABLER — NFR-MNT-01 (SRS B1) | T-01.4.1.1/2/3 | `.github/workflows/{ci,codeql}.yml`, JaCoCo gate `gradle/coverage.gradle` | 9 required checks live; coverage gate green at 80/60; seeded-violation proofs on draft PR | #33 | pending | 0.1.0 |
| **US-02.1.1** | **FR-ADM-02 (SRS B1)** · NFR-SEC-01 | login use case + PBKDF2/HMAC-JWT adapters + JDBC user directory + `/api/v1/auth/login`,`/me` | 7 crypto unit + 4 login ITs on real PostgreSQL (success, /me, tampered token, uniform failure) | #37 | pending | 0.2.0 |
| **US-02.4.1** | **FR-ADM-01 (SRS B1)** | V3 MASTER_ADMIN grants + BootstrapAdministrator + MasterAdminPolicy + first-run runner | 6 unit + BootstrapAdminIT (migrate→bootstrap→login end-to-end) + MigrationIT V3 grants; 48/48 green | #38 | pending | 0.1.0 |
| **US-02.1.2** | **FR-ADM-02 (SRS B1)** · NFR-SEC-01 | V4 sessions + SessionManager (rotate/replay/logout) + SessionStore/AuditTrail JDBC adapters + `/auth/refresh`,`/logout` + revocation in interceptor | 5 unit + SessionIT (rotate, replay→family-revoke+audit, logout→401); 55/55 green | #39 | pending | 0.1.0 |
| **US-02.2.1** | **FR-ADM-02 (SRS B1)** · NFR-SEC-01 | V5 SYSTEM_ADMIN grant + UserAdministration use case + JDBC user/permission adapters + `/api/v1/admin/users` CRUD + AdminAuthorizationInterceptor (user.manage) | 6 unit + UserAdminIT (create/list, dup 409, deactivate→session revoke, non-admin 403+audit); 65/65 green | #40 | pending | 0.1.0 |
| **US-02.2.2** | **FR-ADM-02 (SRS B1)** | V6 activation_tokens + UserImport (preview/execute) + AccountActivation + TransactionRunner + `/admin/users/import`,`/auth/activate` | 7 unit + UserImportIT (preview→confirm→activate→login, password-column 422, conflict 409); 74/74 green | *(local, unpushed)* | pending | 0.1.0 |
| **US-03.2.1** | **NFR-SEC-01 (SRS B1)** · OWASP ASVS V4 | `AuthorizationInterceptor` on `/**` + `@RequiresPermission`/`@RequiresAuthentication` + `PublicRoutes` allowlist + CORS (no wildcard) | RouteAuthorizationCoverageIT (2) — planted route caught by name; ApiAuthorizationIT: 401 unauth, 403 tenant + no write, principal-from-token, revoked→401 | *(local)* | pending | 0.1.0 |
| **US-03.2.2** | **NFR-SEC-01 (SRS B1)** · OWASP ASVS V4/V7 | RFC 7807 `ProblemDetails` + `GlobalExceptionHandler` + `CorrelationIdFilter` + denial audit (`recordSecurityDenial`) + Micrometer denial metric | ApiAuthorizationIT: uniform body, 403≡404 indistinguishable, audit w/o PII, anonymous denial, correlation id; 86/86 green | *(local)* | pending | 0.1.0 |
| **US-02.3.1** | **FR-ADM-02 (SRS B1)** · NFR-SEC-01 · OWASP ASVS V2 | V8 `login_attempts` + rotation columns · `PasswordPolicy` (length-primary, denylist, predictability) · `ChangePassword` + `AccountRecovery` · `LoginAttemptStore`/`CredentialStore` JDBC adapters · lockout in `AuthenticateUser` · `POST /auth/password`, `/admin/users/{id}/{unlock,reset-password}` · forced-change gate in `AuthorizationInterceptor` | 30 unit (policy, lockout, change, recovery) + AccountSecurityIT (13): lock at threshold, locked≡wrong≡unknown byte-identical, unknown username counted, success resets, change revokes all sessions, weak 422 w/o echo, admin unlock 403/204, reset issues token and leaves the hash untouched, forced change confines the session and survives refresh; 162/162 green | *(local)* | pending | 0.2.0 |
| **US-04.8.1** | `TDD-DERIVED` FR-SET-01 — authorised as an approved enabler by [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `SettingsCatalogue` (fixed key list, types, defaults) · `SystemSettings` use case with WhatsApp guard + before/after audit · `JdbcSettingsStore` (jsonb type preserved on write) · `GET/PUT /api/v1/admin/settings` split across `masterdata.view` / `settings.manage` · `vms.masterdata.enabled` feature flag | 22 unit + SystemSettingsIT (10): catalogue≡seed both directions, seeded listing, change→jsonb+actor+audit, jsonb keeps number/boolean, WhatsApp enable 409 citing TODO-05 and audited, disable still works, `masterdata.edit`→403+audit, unknown key 404, wrong type 400; 198/198 green | *(local)* | pending | 0.2.0 |
| **US-04.8.2** | `TDD-DERIVED` FR-SET-01 · `ENABLER` vs NFR-SEC-01 (SRS B1) — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `SettingValues` typed accessor (fails loudly on a bad stored value rather than falling back) · `CachingSettingsStore` decorator, invalidated on write · secret-valued marker + `SettingsCatalogue.disclose` redacting API **and** audit · `CredentialShape` heuristic refusing credential-shaped writes ahead of the type check | 22 unit (accessor, cache, credential shapes, redaction) + 3 IT: cache invalidation through the real stack, credential value 409 and absent from audit, nothing redacted today; 242/242 green | *(local)* | pending | 0.2.0 |
| **US-04.1.1** | `TDD-DERIVED` FR-CFG-02 — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | **The shared master data pattern, built once**: `MasterDataText` domain rules · `MasterDataStore<T>` port (no delete anywhere) · `MasterDataDefinition<T>` · generic `MasterDataAdministration<T>` with audit built in · applied to `Building` · `JdbcBuildingStore` (constraint-driven conflicts, allow-listed sort) · `/api/v1/admin/buildings`. Also fixes the catch-all turning `HttpRequestMethodNotSupportedException` into 500 | 9 domain + 9 pattern unit + BuildingAdminIT (10): create→audit, paged/filtered/searchable list, deactivate retains row, **duplicate code under a 6-thread race**, rename conflict, no delete route, viewer 403+audit, invalid code 400, 401, 404s; 275/275 green | *(local)* | pending | 0.2.0 |
| **US-04.2.1** | `TDD-DERIVED` FR-CFG-03 — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `Floor` domain record (building required, level bounded) · `FloorDefinition` · `JdbcFloorStore` — **active-parent enforced inside the INSERT** via `WHERE EXISTS`, composite `(building_id, code)` conflict translation, `level_no NULLS LAST, code` ordering, update scoped by building · nested `/api/v1/admin/buildings/{buildingId}/floors`. Port gained `Query.parentId` and `InvalidParent`, both reused by the next child entity | 7 domain + FloorAdminIT (12): create→audit w/ parent, ordering w/ unnumbered last, list scoped to building, same code in another building OK, deactivated parent 400, missing parent 400, no delete route, wrong parent 404, update cannot relocate, viewer 403, level bounds; 299/299 green | *(local)* | pending | 0.2.0 |
| **US-04.5.1** | `TDD-DERIVED` FR-CFG-06 — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `VisitorType` + `VisitorTypeDefinition` + `JdbcVisitorTypeStore` + `/api/v1/admin/visitor-types` — the pattern's cheapest entity, ~3 small files. Also extracts `MasterDataQuery` (filter/paging/args assembly) at the third adapter and refactors the building and floor stores onto it | 3 domain + 7 `MasterDataQueryTest` + VisitorTypeAdminIT (9): seeded four present, create→audit, dup 409, deactivate, no delete route despite `ON DELETE SET NULL`, search, size cap, viewer 403+audit, 400/401/404; 319/319 green | *(local)* | pending | 0.2.0 |
| **US-04.6.1** | `TDD-DERIVED` FR-CFG-07; consumed by **FR-VMS-11 (SRS B1)** and **FR-VMS-13 (SRS B1)** in Phase 2 — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `CredentialType`/`RestrictionType` domain enums with **two distinct entry points** — `parse` (input, lists permitted values) and `fromDatabase` (throws `UnknownDatabaseValue`, never defaults) · `PassType` validating positive+bounded hours in the domain, not only the DB `CHECK` · `JdbcPassTypeStore` with `::vms.<enum>` casts · full-state audit · `/api/v1/admin/pass-types` | 12 domain + PassTypeAdminIT (8): seeded round-trip field by field, create/update audit complete states, unpermitted enum 400 listing permitted, non-positive hours 400, deactivate keeps row, dup 409 / viewer 403 / no delete / 401, and **enum drift — `ALTER TYPE … ADD VALUE 'nfc'` then read fails loudly instead of silently reading as `qr`**; 343/343 green | *(local)* | pending | 0.2.0 |
| **US-04.7.1** | `TDD-DERIVED` FR-CFG-08 — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | **Deliberately off the shared pattern**: no code, no `is_active`, keyed by date, genuinely deletes · `Holiday` + `HolidayCalendarStore` + `HolidayCalendar` use case · delete audited **before** the row is removed, since the audit is then the only record it existed · validate-then-apply bulk import in one transaction with per-row report · `/api/v1/admin/holidays` incl. the only `DELETE` in master data | 12 unit + HolidayCalendarIT (10): add→audit, working exception distinguishable, year+range ends inclusive, delete removes row and keeps prior state, dup date 409, clean import applies 3, one clash writes nothing, in-set duplicate caught pre-DB, viewer 403 on add+delete, 400/401/404; 365/365 green | *(local)* | pending | 0.2.0 |
| **US-04.4.1** | `TDD-DERIVED` FR-CFG-05; consumed by **FR-ADM-01 (SRS B1)** and **FR-ADM-02 (SRS B1)** — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `Reception` + floor-scoped store · `ReceptionAdministration` — atomic central transfer under `FOR UPDATE`, auditing the **previous holder**; deactivation guarded and dependent count reported · **`MasterAdminPolicy` finally wired into `UserAdministration.deactivate`** (it was a bean, unit-tested, and called by nothing) · both guards re-expressed as one invariant over the post-state and reading through the same locked row set | 9 `MasterAdminInvariantTest` + 3 `UserAdministrationTest` + ReceptionAdminIT (9): create never central, floor-scoped dup 409, transfer needs confirm + audits predecessor + exactly one central, filters/total, deactivation keeps `users.reception_id`, stranding 409, **last-admin guard now actually refuses**, **two concurrent deactivations → exactly one 204 and one 409**; 420/420 green | *(local)* | pending | 0.2.0 |
| **US-03.1.1** | `TDD-DERIVED` FR-USR-02 · `ENABLER` vs NFR-SEC-01 (SRS B1) — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | **`V9__role_permission_grants.sql` — the authoritative matrix, completing the lift of D-15** · `Permissions` curated constants + `PermissionSet` (`NONE` is an answer, not a failure) · `RoleGrantStore` + `EffectivePermissions` (inactive user → empty set) · dev grant matrix **removed** from `LocalDevSeeder` · seven ITs re-pointed at the real matrix with their hand-seeded grants deleted | 8 unit + RoleGrantMatrixIT (7): roles/permissions exact, **constants ≡ `vms.permissions` both directions**, matrix exact role-by-role, unbacked three granted to nobody, idempotent re-run, no grant of an unknown code; 435/435 green | *(local)* | pending | 0.2.0 |
| **US-03.3.1** | `TDD-DERIVED` FR-USR-02 · `ENABLER` vs NFR-SEC-01 (SRS B1) — [ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md) | `RoleAdministration` — grant edits on the five seeded roles only (no create-role, no create-permission; TODO-01) · `GrantVersion` **content-derived** optimistic token · administrative-lockout guard evaluated against the *resulting* matrix under a row lock · `credential.override` refused at the API as well as the migration · `GET/PUT /api/v1/admin/roles` | 16 unit + RoleAdminIT (10): overview w/ versions+counts+catalogue, change audited both sets, **live session sees revoked grants on its next request without re-login**, lockout 409 changes nothing, stale version 409 carrying current state, unbacked 400 naming TODO-04, unknown code 400, 403 w/o `user.manage` + audit, 401/404, creation not routed; 460/460 green | *(local)* | pending | 0.2.0 |
| **US-07.1.1** | **FR-VMS-01 (SRS B1)** ⭐ *first visitor requirement* | V7 grant + outbox · `VisitorRequest` aggregate + `Visitor`/`TimeWindow` (framework-free domain) · `SubmitVisitorRequest` · JDBC adapters · `POST /api/v1/visitor-requests` | 8 domain + 8 use-case unit + VisitorRequestIT (6): submit→row+visitor+event+audit, invalid window 400, tenant override ignored, foreign host, 403 w/o permission, 401 | *(local)* | pending | 0.2.0 |

---

## Coverage dashboard

| Metric | Count | % |
|---|---|---|
| SRS FRs traced to an epic | 28 / 28 | **100%** |
| SRS FRs traced to a feature | 28 / 28 | **100%** |
| SRS FRs decomposed to user stories | 28 / 28 | **100%** |
| SRS FRs decomposed to tasks | 28 / 28 | **100%** |
| SRS FRs fully ready | 4 / 28 | 14% |
| SRS FRs sim-only (build now, verify after TODO-02) | 1 / 28 | 4% |
| SRS FRs partially workable | 18 / 28 | 64% |
| SRS FRs blocked outright | 5 / 28 | 18% |
| SRS FRs implemented | 0 / 28 | 0% |
| NFRs with a testable acceptance criterion | 5 / 8 | 63% |
| User stories authorized | 105 / 178 | 59% |
| Open discrepancies | 15 | — |
| Open questions | 19 (4 blocking) | — |

**Read this honestly.** Requirement *coverage* is complete — every SRS requirement is decomposed to
task level and nothing has been missed. Requirement *readiness* is not. Only **4 of 28 are fully ready**; **18 are partially workable** —
their core behaviour can be built while a dependent slice waits — and **5 are blocked outright**.

The dominant cause is TODO-01, not TODO-02. The missing SRS v2 touches almost every requirement,
because master data (EPIC-04) is unbacked and nearly everything references a tenant, floor or
reception. TODO-02 blocks less than it appears to: the anti-corruption layer (ADR-0002) converts it
from a build blocker into a *verification* blocker for most credential work.

That is a supplier and client dependency, not a planning gap — and the phase ordering is built
around it, front-loading everything that can proceed without them.
