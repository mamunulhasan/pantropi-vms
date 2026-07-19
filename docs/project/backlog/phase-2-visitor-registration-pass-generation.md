# Phase 2 — Visitor Registration & Pass Generation

**Baseline:** B1 · **Milestone:** Phase 2 · **Status:** Ready for sprint planning (subject to DoR per [05-workflow-and-branching.md](../05-workflow-and-branching.md) §5)
**Decomposes:** [00-epic-feature-index.md](00-epic-feature-index.md) — Phase 2 section, exactly as listed. No additions, no renames, no renumbering.

---

## Phase goal

Deliver the **complete pre-arrival visitor journey**: a tenant submits a visitor entry request from the
web portal; an FM Admin reviews and approves or rejects it with a recorded reason; a floor receptionist
pre-registers visitor details to the central server ahead of arrival; the system requests a time-bound
or one-time credential from ACS, stores the returned credential reference, and renders and delivers the
pass to the visitor by email, on-screen, or print.

This is the **client-demonstrable** phase. At the end of it, the client can watch a visitor go from
"nobody has heard of them" to "holding a scannable pass" without a developer touching a database.

**TDD alignment:** §4.2 Visitor & Approval Service, §4.3 Pass & Credential Service. Bounded contexts
*Visitor & Approval* (aggregate root `VisitorRequest`) and *Pass & Credential* (aggregate root
`Credential`), per §8 of the workflow document. One aggregate per transaction; cross-aggregate
consistency is eventual, via Kafka domain events.

**Technology (TDD §3):** Java 21 + Spring Boot (Clean Architecture modules: `domain` → `application`
→ `infrastructure` → `interfaces`), PostgreSQL 14+ (`vms` schema), Redis (read caching, idempotency
keys), Kafka (domain events), Next.js portal, JWT/OAuth2 bearer authentication.

| Metric | Value |
|---|---|
| Epics | **4** (EPIC-07, EPIC-08, EPIC-09, EPIC-10) |
| Features | **19** (6 + 4 + 7 + 2) |
| User stories | **45** |
| Development tasks | **146** |
| Total story points | **190** |
| SRS requirements delivered | FR-VMS-01, FR-VMS-02, FR-VMS-03, FR-VMS-05, FR-VMS-06, FR-VMS-07, FR-VMS-11, FR-VMS-13 (8 of 28) |

---

## The ACS dependency — read this before planning EPIC-09

EPIC-09 exists to satisfy **FR-VMS-05 (SRS B1)**: *"VMS shall call the ACS API to request creation of
a time-bound credential."* The real UAL ACS API contract **does not exist yet** — that is **TODO-02**,
one of the four blocking open questions.

Per **ADR-0002**, the mitigation is already built into the plan: the **ACS port (F-11.1)** and the
**ACS simulator (F-11.2)** are pulled forward out of Phase 3 into Phase 1. EPIC-09 is therefore built
against a VMS-owned port interface and exercised against a VMS-owned simulator.

The distinction that matters, and the one most likely to be got wrong in a sprint review:

| | |
|---|---|
| **Implementable now?** | ✅ **Yes.** Every EPIC-09 story can be coded, unit tested, integration tested and contract tested against the simulator. Nothing in EPIC-09 waits on TODO-02 to *start*. |
| **Verifiable now?** | ⛔ **No.** No ACS-touching story may be marked *verified* in the traceability matrix. Its terminal state this phase is **"done against simulator"**, not "done". |

Every ACS-touching story below therefore carries:

> **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002)

This is **not** a `BLOCKED` provenance marker. `BLOCKED` means *do not build*. These stories are
`SRS`-backed and must be built. Only the verification gate is deferred. Any story whose provenance is
`SRS` but whose **Blocked by** cell names TODO-02 is a build-now / verify-later item, and the sprint
review reverse trace must not close it as verified.

**Consequence for the phase demo:** the client demo runs end-to-end against the simulator. State this
explicitly in the demo script. Do not let a green demo be read as ACS integration acceptance.

---

## Security posture for this phase

Phase 2 is the first phase that handles **visitor personally identifiable information** — names, emails,
phone numbers, employers, and optionally `visitors.id_document_ref`. OWASP thinking applies throughout,
and every story records its security considerations per DoR.

| Control | Applied where |
|---|---|
| **Authorization on every endpoint** (OWASP A01) | Every controller in EPIC-07/08/09/10 declares a permission from `vms.permissions` — `visitor.request`, `visitor.approve`, `visitor.register`, `credential.issue`. Enforced at the API boundary by F-03.2, not in the UI. |
| **Object-level authorization** (OWASP A01 — IDOR) | A tenant user may read only `vms.visitor_requests` rows whose `tenant_id` matches their own `vms.users.tenant_id`. Enforced in the repository/specification layer, tested with a negative integration test per story. |
| **No PII in logs** (OWASP A09) | Visitor name, email, phone, `id_document_ref` and `qr_payload` are never written to application logs, Kafka event payloads, `vms.acs_api_log.payload`, or error responses. Log the `visitor_id` UUID and correlate. A CI log-scrubbing test asserts this. |
| **Audit events** | `vms.audit_logs` rows written on request submission, approval, rejection, cancellation, pre-registration, credential issuance, credential state transition, and pass delivery. `before_state`/`after_state` are PII-redacted projections. |
| **Tenant data isolation** | ⚠️ **TODO-14** — no document states whether Tenant A may see Tenant B's visitor data, or whether a floor receptionist is scoped to their floor. This phase implements the **restrictive default** (own tenant only; floor receptionist scoped to `vms.users.reception_id` → `floors`) behind a scoping strategy interface so the rule can be relaxed without a schema change. Recorded as a provisional ADR assumption. Every story touching tenant-scoped data notes TODO-14. |
| **Input validation** | Bean Validation on every request DTO; email and phone normalised; `purpose`, `company`, `full_name` length-bounded and stored as text (never interpolated into SQL or HTML). |
| **Mass assignment** | Inbound DTOs never bind `status`, `approved_by`, `acs_credential_id`, or `state`. State changes only through the aggregate's state machine (F-07.5, F-09.7). |
| **PII retention** | ⚠️ **TODO-12** — no retention or purge rule exists. Phase 2 stores PII indefinitely. Flagged, not solved here. |
| **ID document capture** | ⚠️ **TODO-13** — `visitors.id_document_ref` is written by no Phase 2 story. Column left null pending a scope decision and a privacy assessment. |

---

## Phase exit criteria

- [ ] A tenant user authenticates to the portal and submits a visitor entry request with one or more visitors and a requested time window — **FR-VMS-01 (SRS B1)**
- [ ] The request appears on the FM Admin approval dashboard, filterable and paginated, scoped to the admin's authority
- [ ] An FM Admin approves or rejects the request with a recorded, mandatory reason on rejection — **FR-VMS-02 (SRS B1)**
- [ ] The submitting tenant sees the resulting status and the rejection reason without contacting anyone
- [ ] A floor receptionist enters visitor details and submits them to the central server as a pre-registration record ahead of arrival — **FR-VMS-03 (SRS B1)**
- [ ] Each visitor carries an appointment window; validity windows are derived and validated — **FR-VMS-11 (SRS B1)**
- [ ] On approval, VMS requests credential creation through the ACS port with a validity window and a restriction type of `time_bound` or `one_time` — **FR-VMS-05, FR-VMS-11, FR-VMS-13 (SRS B1)** — *against the simulator*
- [ ] The credential reference returned by ACS is persisted in `vms.credentials.acs_credential_id` / `qr_payload` and is displayable — **FR-VMS-06 (SRS B1)**
- [ ] The pass renders as a scannable QR and is deliverable by email, on-screen, and print — **FR-VMS-07 (SRS B1)**
- [ ] ACS unavailability degrades gracefully: no visitor request or approval data is lost, and the receptionist is told — **NFR-REL-01 (SRS B1)**
- [ ] Hosts can be maintained and assigned to requests — *(TDD-DERIVED, backlog-only pending TODO-01)*
- [ ] Architecture fitness tests pass — **no ACS-specific type exists outside the ACS integration module** (CON-01, CON-02, NFR-MNT-01)
- [ ] Every acceptance criterion has at least one integration test; negative cases included
- [ ] No PII appears in application logs, Kafka payloads, or `vms.acs_api_log` — verified by an automated check
- [ ] Traceability matrix delivery log updated; ACS-touching rows recorded as **"done against simulator"**, not verified
- [ ] ⚠️ Phase cannot be declared *requirement-verified* until TODO-02 lands and F-11.7 (wire-level adapter) replaces the simulator

---

## EPIC-07 — Visitor Request & Approval

| | |
|---|---|
| **Provenance** | `SRS` — FR-VMS-01 (SRS B1), FR-VMS-02 (SRS B1) |
| **Requirement IDs** | FR-VMS-01 (SRS B1), FR-VMS-02 (SRS B1); NFR-SEC-01 (SRS B1), NFR-USA-01 (SRS B1) |
| **Priority** | **P0** — the phase starts here; nothing downstream has an input without it |
| **Features** | 6 (F-07.1 … F-07.6) |
| **Stories / Tasks / Points** | 15 stories · 52 tasks · **65 points** |
| **Bounded context** | Visitor & Approval — aggregate root `VisitorRequest` |
| **Primary tables** | `vms.visitor_requests`, `vms.visitors`, `vms.hosts`, `vms.tenants`, `vms.audit_logs` |

**Goal.** Give a tenant a way into the building for their guests, and give facilities management a way to
say no. A tenant user logs into the portal, submits a visitor entry request naming one or more visitors,
a host, a purpose and a requested time window; the request lands in `submitted` state on an FM Admin
dashboard; the FM Admin approves or rejects it with a recorded reason; the decision is audited, published
as a domain event, and made visible back to the tenant who raised it. This epic owns the `VisitorRequest`
aggregate and its state machine — every downstream epic in the phase consumes the events it emits, so its
state transitions are the contract the rest of Phase 2 is written against.

---

### F-07.1 — Tenant visitor request submission

A tenant user submits a visitor entry request through the web portal, naming the visitor(s), the host,
the purpose, and the requested time window.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Priority** | P0 · **Stories** 3 · **Tasks** 12 · **Points** 13 |
| **Depends on** | EPIC-02 (identity), EPIC-03 (RBAC), EPIC-04 (master data), EPIC-06 (portal shell) |

---

#### US-07.1.1 — Submit a visitor entry request

**As a** Tenant **I want** to submit a visitor entry request from the VMS web portal **so that** my guest is expected and can be granted building access without me phoning reception.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Dependencies** | F-02.1, F-03.2, F-04.3, F-06.3, US-10.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am authenticated as a Tenant holding the `visitor.request` permission, **When** I submit a request with a visitor name, a host, a purpose, and a `scheduled_from`/`scheduled_to` window, **Then** a `vms.visitor_requests` row is created with `status = 'submitted'`, `visit_kind = 'pre_scheduled'`, `tenant_id` set from my own user record, and `requested_by` set to my user id, and a `vms.visitors` row is created for the named visitor with `status = 'pending'`.
- **AC-2 — Given** a request is created, **When** the transaction commits, **Then** a `VisitorRequestSubmitted` domain event is published to Kafka carrying `request_id`, `tenant_id`, `host_id` and the window — and carrying **no** visitor name, email or phone.
- **AC-3 — Given** a request is created, **When** I query it back, **Then** a `vms.audit_logs` row exists with `action = 'visitor_request.submit'`, `entity_type = 'visitor_request'`, my `user_id`, and a PII-redacted `after_state`.
- **AC-4 (negative) — Given** I submit a request whose `scheduled_to` is earlier than or equal to `scheduled_from`, **When** the API validates it, **Then** the request is rejected with HTTP 400 and a field-level error, nothing is persisted, and the database `CHECK (scheduled_to > scheduled_from)` constraint is never reached.
- **AC-5 (negative) — Given** I am authenticated as a Tenant, **When** I submit a request naming a `tenant_id` other than my own, **Then** the supplied value is ignored (never bound from the DTO) and the request is created against my own tenant — asserted by an integration test that attempts the override.
- **AC-6 (negative) — Given** I am authenticated but lack the `visitor.request` permission, **When** I call the submission endpoint, **Then** the API returns HTTP 403, nothing is persisted, and an authorization-denied audit entry is written.

**Security considerations:** `visitor.request` permission enforced at the API boundary (F-03.2); `tenant_id` derived server-side from the session, never from the payload (OWASP A01); visitor PII validated and length-bounded; no visitor PII in the emitted Kafka event or in logs; audit entry on every submission. Tenant scoping per TODO-14 restrictive default.

**Development Tasks**

**T-07.1.1.1 — `VisitorRequest` aggregate and domain model** · `P0` · `1 pt` · deps: `—`
- **Description:** Create the `VisitorRequest` aggregate root and `Visitor` entity in the `domain` module of the visitor service — value objects for `TimeWindow` (invariant: `to > from`), `RequestStatus` mirroring `vms.request_status`, and `VisitKind` mirroring `vms.visit_kind`. No Spring, no JPA annotations.
- **Acceptance Criteria:** aggregate compiles with zero framework imports; `TimeWindow` rejects a non-positive duration at construction; adding a visitor to a non-`submitted` request throws a domain exception; architecture fitness test confirms `domain` imports nothing outward.
- **Dependencies:** `—`

**T-07.1.1.2 — Persistence mapping and repository for `visitor_requests` / `visitors`** · `P0` · `1 pt` · deps: `T-07.1.1.1`
- **Description:** JPA entities and Spring Data repository in `infrastructure` mapping the aggregate to `vms.visitor_requests` and `vms.visitors`, including the PostgreSQL enum types `vms.request_status`, `vms.visit_kind`, `vms.visitor_status`. Cascade persist of visitors with the request in one transaction (one aggregate, one transaction).
- **Acceptance Criteria:** Testcontainers integration test round-trips an aggregate with two visitors; enum values map both directions without string drift; the `CHECK (scheduled_to > scheduled_from)` violation surfaces as a domain-level exception, not a raw SQL error.
- **Dependencies:** `T-07.1.1.1`

**T-07.1.1.3 — `SubmitVisitorRequest` use case** · `P0` · `1 pt` · deps: `T-07.1.1.2`
- **Description:** Application-layer use case in the `application` module: resolve the caller's `tenant_id` from the authenticated principal, validate the host belongs to that tenant, construct the aggregate in `submitted` state, persist, write the audit entry, and publish `VisitorRequestSubmitted`.
- **Acceptance Criteria:** unit tests with mocked ports cover the happy path and each validation failure; `tenant_id` is never read from the command DTO; audit and event publication happen within the same transactional boundary as the write (outbox or transactional listener); use case has no HTTP or JPA types in its signature.
- **Dependencies:** `T-07.1.1.2`

**T-07.1.1.4 — `POST /api/v1/visitor-requests` endpoint with validation and authorization** · `P0` · `1 pt` · deps: `T-07.1.1.3`
- **Description:** REST controller in `interfaces` exposing submission, guarded by `@PreAuthorize` on the `visitor.request` permission. Request DTO with Bean Validation; response returns the created request id and status. DTO deliberately omits `status`, `tenant_id`, `approved_by`.
- **Acceptance Criteria:** 201 on success with a `Location` header; 400 with field-level errors on invalid window or missing visitor name; 403 without the permission; 401 unauthenticated; OpenAPI spec generated; a mass-assignment integration test proves `status` and `approved_by` in the body are ignored.
- **Dependencies:** `T-07.1.1.3`

**T-07.1.1.5 — Tenant request submission form (Next.js)** · `P0` · `1 pt` · deps: `T-07.1.1.4`
- **Description:** Portal page under the tenant route group using the F-06.2 component library: visitor detail fields, host selector (F-10.2), purpose, and a date/time range picker. Client-side validation mirrors server rules; server errors render against the offending field.
- **Acceptance Criteria:** submits successfully and routes to the request detail view; invalid window blocked client-side and, if bypassed, handled from the server response; WCAG 2.1 AA — labelled inputs, keyboard-operable picker, errors announced to assistive technology; no PII written to browser console or analytics.
- **Dependencies:** `T-07.1.1.4`

---

#### US-07.1.2 — Capture visitor details on a request

**As a** Tenant **I want** to record each visitor's name, email, phone, company and visitor type **so that** reception and the host know who is arriving and the credential can be delivered to the right person.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Dependencies** | US-07.1.1, F-04.5 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am creating or editing a request in `submitted` state, **When** I supply visitor `full_name`, `email`, `phone`, `company` and a `visitor_type_id` drawn from `vms.visitor_types`, **Then** the values persist to the `vms.visitors` row and `email` is stored case-insensitively via the `citext` column.
- **AC-2 — Given** a visitor has an email address, **When** the record is saved, **Then** the address is normalised (trimmed, lower-cased for comparison) and is available as the delivery target for F-09.6 pass delivery.
- **AC-3 (negative) — Given** I supply a `visitor_type_id` that does not exist or is `is_active = false`, **When** I save, **Then** the request is rejected with HTTP 400 naming the field, and no partial visitor row is written.
- **AC-4 (negative) — Given** I supply a malformed email or an oversized `full_name`, **When** I save, **Then** validation rejects it with a field-level error and the rejected value does not appear in any log line.

**Security considerations:** all four PII fields are validated and bounded; `id_document_ref` is **not** exposed by any endpoint in this story (TODO-13); PII excluded from log output and from the request-submitted event.

**Development Tasks**

**T-07.1.2.1 — Visitor detail value objects and validation** · `P0` · `1 pt` · deps: `T-07.1.1.1`
- **Description:** Add `EmailAddress` and `PhoneNumber` value objects to the visitor `domain` module with normalisation and format rules; bound `full_name` and `company` lengths to the practical maxima the UI enforces.
- **Acceptance Criteria:** unit tests cover normalisation, equality of differently-cased emails, and rejection of malformed input; `toString()` on both value objects returns a redacted form so accidental logging cannot leak PII.
- **Dependencies:** `T-07.1.1.1`

**T-07.1.2.2 — Visitor type reference lookup and active-state validation** · `P1` · `1 pt` · deps: `T-07.1.1.3`
- **Description:** Read-side port to the master data service for `vms.visitor_types`, resolving by id and asserting `is_active`. Served from the Redis master-data cache (F-04.9) rather than a cross-service table read.
- **Acceptance Criteria:** unknown or inactive type produces a domain validation failure; no direct SQL join from the visitor service into master-data tables — asserted by the service-boundary fitness test; cache miss falls through to the owning service.
- **Dependencies:** `T-07.1.1.3`

**T-07.1.2.3 — Visitor detail sub-form in the request UI** · `P1` · `1 pt` · deps: `T-07.1.1.5, T-07.1.2.2`
- **Description:** Extend the submission form with the per-visitor field set and a visitor-type select populated from the master-data endpoint.
- **Acceptance Criteria:** type select shows active types only; inline validation matches server rules; email/phone fields marked `autocomplete="off"` and excluded from any client-side error-reporting payload.
- **Dependencies:** `T-07.1.2.2`

---

#### US-07.1.3 — Edit or cancel a submitted request

**As a** Tenant **I want** to amend or cancel my visitor request while it is still awaiting approval **so that** a changed plan does not become a wasted approval or an unexpected visitor at the gate.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Dependencies** | US-07.1.1, US-07.5.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** my request is in `submitted` state, **When** I amend the window, purpose, host, or visitor details, **Then** the changes persist, an audit entry with `action = 'visitor_request.amend'` records before/after (PII-redacted), and a `VisitorRequestAmended` event is published.
- **AC-2 — Given** my request is in `submitted` state, **When** I cancel it, **Then** `status` becomes `cancelled`, every attached `vms.visitors` row moves to `cancelled`, the request leaves the FM Admin approval queue, and a `VisitorRequestCancelled` event is published.
- **AC-3 — Given** my request has already been `approved`, **When** I attempt to cancel it, **Then** the cancellation is permitted, the credential (if any) is signalled for revocation via a domain event consumed by F-09.7, and the audit entry records that the cancellation followed approval.
- **AC-4 (negative) — Given** my request has been `rejected` or is already `cancelled`, **When** I attempt to amend or cancel it, **Then** the API returns HTTP 409 with the current state, and no write occurs.
- **AC-5 (negative — concurrency) — Given** an FM Admin is approving my request in one transaction, **When** I cancel it concurrently, **Then** optimistic locking on the aggregate causes exactly one of the two to succeed; the loser receives HTTP 409 and the aggregate is never left in a state where it is both `approved` and `cancelled`.
- **AC-6 (negative) — Given** a request belonging to a different tenant, **When** I attempt to amend or cancel it by id, **Then** the API returns HTTP 404 (not 403 — do not confirm existence), and an authorization-denied audit entry is written.

**Security considerations:** object-level authorization on every read and write by request id (OWASP A01/IDOR); 404-not-403 to avoid an enumeration oracle; optimistic locking to prevent a lost-update race with approval; audit on amend and cancel.

**Development Tasks**

**T-07.1.3.1 — Amend and cancel transitions on the aggregate** · `P1` · `2 pts` · deps: `T-07.1.1.1, T-07.5.1.1`
- **Description:** Add `amend()` and `cancel()` to `VisitorRequest`, delegating legality to the F-07.5 state machine. `cancel()` cascades visitor status to `cancelled` and records whether the prior state was `approved`.
- **Acceptance Criteria:** unit tests assert every legal and illegal source state; cancelling from `approved` raises the credential-revocation signal, cancelling from `submitted` does not; no transition mutates state before the legality check.
- **Dependencies:** `T-07.5.1.1`

**T-07.1.3.2 — Optimistic locking and concurrency handling** · `P1` · `1 pt` · deps: `T-07.1.1.2`
- **Description:** Add a `@Version` column to the request aggregate root mapping (migration adds `version bigint NOT NULL DEFAULT 0` to `vms.visitor_requests`), and translate the optimistic-lock failure into a 409 at the API boundary.
- **Acceptance Criteria:** a concurrent approve-vs-cancel integration test with two threads shows exactly one success and one 409; the aggregate never persists a state combination excluded by the state machine; migration is forward-only and applied via the F-01.5 framework.
- **Dependencies:** `T-07.1.1.2`

**T-07.1.3.3 — Amend and cancel endpoints with object-level authorization** · `P1` · `1 pt` · deps: `T-07.1.3.1, T-07.1.3.2`
- **Description:** `PATCH /api/v1/visitor-requests/{id}` and `POST /api/v1/visitor-requests/{id}/cancel`, both resolving the aggregate through a tenant-scoped repository specification.
- **Acceptance Criteria:** 200 on legal transition, 409 on illegal state or version conflict, 404 for another tenant's request; an integration test proves a cross-tenant id returns 404 with no timing or body difference from a genuinely missing id.
- **Dependencies:** `T-07.1.3.2`

**T-07.1.3.4 — Amend/cancel controls in the portal** · `P2` · `1 pt` · deps: `T-07.1.3.3, T-07.6.1.2`
- **Description:** Add edit and cancel actions to the tenant request detail view, shown only for states where they are legal, with a confirmation dialog on cancel that states the consequence when the request was already approved.
- **Acceptance Criteria:** controls hidden for terminal states; a stale page attempting an illegal transition surfaces the 409 as a readable message and refreshes state; confirmation dialog is keyboard-accessible and focus-trapped.
- **Dependencies:** `T-07.1.3.3`

---

### F-07.2 — Group & multi-visitor requests

One request carries many visitors, each with their own details, appointment window and eventual credential.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Priority** | P1 · **Stories** 2 · **Tasks** 7 · **Points** 10 |
| **Depends on** | F-07.1 |

---

#### US-07.2.1 — Add multiple visitors to a single request

**As a** Tenant **I want** to name several visitors on one request **so that** I raise one approval for a group meeting instead of one per person.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Dependencies** | US-07.1.1, US-07.1.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am composing a request, **When** I add N visitors (N ≥ 1), **Then** N `vms.visitors` rows are created against the single `vms.visitor_requests` row in one transaction, each with `status = 'pending'`.
- **AC-2 — Given** a multi-visitor request, **When** it is approved, **Then** all attached visitors transition together and each becomes independently eligible for its own credential in EPIC-09 — one credential per visitor, never one per request.
- **AC-3 — Given** a multi-visitor request, **When** one visitor is later cancelled individually, **Then** only that visitor's row moves to `cancelled` and the request itself remains `approved` while at least one visitor is still active.
- **AC-4 (negative) — Given** I add a visitor list exceeding the configured group size ceiling (`vms.system_settings` key `visitor_request.max_group_size`), **When** I submit, **Then** the request is rejected with HTTP 400 naming the limit, and nothing is persisted.
- **AC-5 (negative) — Given** I add two visitors with the same email address on the same request, **When** I submit, **Then** the duplicate is rejected with a field-level error identifying both positions, preventing two credentials being issued to one person.
- **AC-6 (negative) — Given** a request with 20 visitors, **When** the transaction fails on the 20th, **Then** no visitor row and no request row is persisted — the whole aggregate write is atomic.

**Security considerations:** group size ceiling is a resource-exhaustion control (OWASP A04) — it bounds the fan-out of downstream ACS credential requests from a single authenticated call; duplicate-email detection prevents unintended double credential issuance; per-visitor PII validated identically to the single case.

**Development Tasks**

**T-07.2.1.1 — Multi-visitor aggregate invariants** · `P1` · `2 pts` · deps: `T-07.1.1.1`
- **Description:** Enforce the visitor collection invariants on `VisitorRequest`: at least one visitor, no duplicate email within the request, and a maximum group size read from system settings and injected as a policy value.
- **Acceptance Criteria:** unit tests cover zero visitors, duplicate email, boundary at max and max+1; invariants are checked in the aggregate, not only in the controller; the ceiling is injected, not hard-coded.
- **Dependencies:** `T-07.1.1.1`

**T-07.2.1.2 — Group size setting and configuration read** · `P2` · `1 pt` · deps: `F-04.8`
- **Description:** Add the `visitor_request.max_group_size` key to `vms.system_settings` via a forward-only migration with a documented default, and read it through the settings port with a Redis-cached lookup.
- **Acceptance Criteria:** migration seeds the key with a description; changing the value takes effect without redeploy after cache TTL or explicit invalidation; a missing key falls back to the documented default rather than failing open to unbounded.
- **Dependencies:** `F-04.8`

**T-07.2.1.3 — Atomic multi-visitor persistence** · `P1` · `1 pt` · deps: `T-07.1.1.2, T-07.2.1.1`
- **Description:** Batch-insert visitors with the request in a single transaction; confirm the `ON DELETE CASCADE` from `vms.visitor_requests` to `vms.visitors` behaves as designed for the cancellation path.
- **Acceptance Criteria:** Testcontainers test with a forced mid-batch failure leaves zero rows in both tables; batch insert used rather than N round-trips; a 20-visitor request completes within the interaction budget implied by NFR-PRF-01 (⚠️ TODO-07 — no agreed numeric target; provisional p95 ≤ 3 s used as the working assumption).
- **Dependencies:** `T-07.2.1.1`

**T-07.2.1.4 — Repeatable visitor rows in the submission UI** · `P1` · `1 pt` · deps: `T-07.1.2.3, T-07.2.1.3`
- **Description:** Add/remove visitor rows in the request form with per-row validation, a running count against the ceiling, and duplicate-email highlighting across rows.
- **Acceptance Criteria:** rows add and remove without losing entered data; ceiling reached disables the add control with an explanatory message; duplicate emails flagged on both rows before submission; row controls are keyboard-reachable and screen-reader-labelled with their index.
- **Dependencies:** `T-07.2.1.3`

---

#### US-07.2.2 — Bulk visitor entry for large groups

**As a** Tenant **I want** to paste or upload a list of visitors **so that** registering a 30-person delegation does not mean 30 rounds of typing.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Dependencies** | US-07.2.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I have a list of visitors in CSV form (name, email, phone, company), **When** I upload it to a submitted-state request, **Then** each valid row becomes a `vms.visitors` row and a per-row result summary is returned.
- **AC-2 — Given** an upload containing both valid and invalid rows, **When** it is processed, **Then** the operation is all-or-nothing: nothing is persisted and every invalid row is reported with its line number and the failing field.
- **AC-3 (negative) — Given** an uploaded file exceeding the configured size or row ceiling, or whose declared content type is not CSV, **When** it is submitted, **Then** it is rejected before parsing with HTTP 413 or 415 and no temporary file survives the request.
- **AC-4 (negative) — Given** an uploaded CSV whose cell begins with `=`, `+`, `-` or `@`, **When** the data is later exported or re-rendered, **Then** the value is neutralised so it cannot execute as a formula in a spreadsheet client (CSV injection).
- **AC-5 (negative) — Given** an upload whose parsing throws, **When** the error is logged, **Then** the log records the row index and failing field name only — never the row's PII content.

**Security considerations:** file upload is an attack surface (OWASP A03/A08) — enforce content type, size ceiling, row ceiling, and stream parsing without writing to disk; CSV formula-injection neutralisation on ingest; no PII in parse-error logs; the same `visitor.request` permission and tenant scoping as manual entry.

**Development Tasks**

**T-07.2.2.1 — Streaming CSV parser with bounded input** · `P2` · `1 pt` · deps: `—`
- **Description:** Infrastructure-layer CSV reader that streams rather than buffering the whole file, enforces the row ceiling mid-stream, rejects non-CSV content types, and neutralises formula-leading characters on every cell.
- **Acceptance Criteria:** a file exceeding the row ceiling aborts at the ceiling without reading further; a `=cmd|...` cell is neutralised in the parsed output; no temporary file is created; unit tests cover quoting, embedded commas, BOM, and CRLF.
- **Dependencies:** `—`

**T-07.2.2.2 — Bulk import use case with all-or-nothing semantics** · `P2` · `2 pts` · deps: `T-07.2.2.1, T-07.2.1.1`
- **Description:** Use case that parses, validates every row against the same value objects as manual entry, and either persists the whole set or none, returning a structured per-row result.
- **Acceptance Criteria:** one invalid row prevents all writes; the result identifies each failure by line number and field; group-size and duplicate-email invariants apply to the combined existing-plus-imported set, not the import alone.
- **Dependencies:** `T-07.2.2.1`

**T-07.2.2.3 — Bulk upload endpoint and UI with per-row error report** · `P2` · `2 pts` · deps: `T-07.2.2.2, T-07.2.1.4`
- **Description:** `POST /api/v1/visitor-requests/{id}/visitors:import` accepting a multipart CSV, plus a portal upload panel with a downloadable template and a per-row error table.
- **Acceptance Criteria:** 400 with the row-level report on validation failure, 413 oversize, 415 wrong type, 403 without permission, 404 for another tenant's request; the error table is navigable by keyboard and each error links to its row; template download contains headers only, no sample PII.
- **Dependencies:** `T-07.2.2.2`

---

### F-07.3 — FM Admin approval dashboard

The queue an FM Admin works from: pending requests, filterable, sortable, paginated, scoped to their authority.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Priority** | P0 · **Stories** 3 · **Tasks** 10 · **Points** 11 |
| **Depends on** | F-07.1, F-03.2, F-06.3 |

---

#### US-07.3.1 — View the pending approval queue

**As an** FM Admin **I want** a dashboard listing visitor requests awaiting my decision **so that** I can work through them without hunting for what needs attention.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Dependencies** | US-07.1.1, F-03.2, F-06.3 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am authenticated as an FM Admin holding `visitor.approve`, **When** I open the approval dashboard, **Then** I see `vms.visitor_requests` rows in `submitted` status showing tenant, host, requested window, visitor count and submission time, newest first.
- **AC-2 — Given** the queue contains more rows than one page, **When** I page through it, **Then** results are server-side paginated with a stable ordering, and page size is bounded by a server-enforced maximum regardless of the requested value.
- **AC-3 — Given** a request is approved, rejected or cancelled by anyone, **When** I next load or refresh the queue, **Then** it no longer appears in the pending view.
- **AC-4 (negative) — Given** I am authenticated as a Tenant, **When** I request the approval dashboard endpoint, **Then** I receive HTTP 403 and an authorization-denied audit entry is written — the dashboard is never merely hidden in the navigation.
- **AC-5 (negative) — Given** a caller requests a page size of 10,000, **When** the endpoint handles it, **Then** the page size is clamped to the server maximum rather than executing an unbounded query (resource exhaustion).
- **AC-6 (negative) — ⚠️ TODO-14 — Given** the tenant-isolation model is undecided, **When** the queue is assembled, **Then** it is assembled through the scoping strategy interface so that restricting an FM Admin to a subset of tenants or floors later requires no change to the query-building code — asserted by a test that swaps in a restrictive strategy and observes a filtered result.

**Security considerations:** `visitor.approve` enforced server-side (OWASP A01); pagination bounded (OWASP A04); list projection excludes visitor email, phone and `id_document_ref` — the queue shows counts and names only, with detail behind a separate authorized read; scoping strategy pluggable pending TODO-14.

**Development Tasks**

**T-07.3.1.1 — Approval queue read model and query** · `P0` · `1 pt` · deps: `T-07.1.1.2`
- **Description:** A dedicated read projection for the queue — request id, tenant name, host name, window, visitor count, submitted-at — built with a bounded, indexed query over `vms.visitor_requests` joined to counts from `vms.visitors`. Add a covering index on `(status, created_at DESC)`.
- **Acceptance Criteria:** query plan uses the index and does not sequential-scan at 50,000 rows; the projection contains no visitor email, phone or document reference; ordering is deterministic under equal timestamps via a tiebreak on id.
- **Dependencies:** `T-07.1.1.2`

**T-07.3.1.2 — Scoping strategy interface for approval visibility** · `P1` · `1 pt` · deps: `T-07.3.1.1`
- **Description:** An `ApprovalScopeStrategy` port that contributes predicates to the queue query. Ship a permissive default (all tenants for FM Admin) and a restrictive implementation, selectable by configuration, so the TODO-14 decision is a config change.
- **Acceptance Criteria:** swapping the strategy in a test filters results without touching the query builder; the active strategy is logged at startup so the deployed isolation posture is discoverable; documented as a provisional ADR assumption pending TODO-14.
- **Dependencies:** `T-07.3.1.1`

**T-07.3.1.3 — `GET /api/v1/visitor-requests/pending` endpoint** · `P0` · `1 pt` · deps: `T-07.3.1.2`
- **Description:** Paginated queue endpoint guarded by `visitor.approve`, with clamped page size and a documented maximum.
- **Acceptance Criteria:** 200 with page metadata; page size above the maximum is clamped, not rejected, and the response states the applied size; 403 for a Tenant principal; OpenAPI documents the ceiling.
- **Dependencies:** `T-07.3.1.2`

**T-07.3.1.4 — Approval dashboard page (Next.js)** · `P0` · `2 pts` · deps: `T-07.3.1.3`
- **Description:** FM Admin dashboard route rendering the queue as a table with pagination, empty state, and a row action opening the request detail. Route registered in the role-driven navigation shell (F-06.3).
- **Acceptance Criteria:** table renders with pagination and a meaningful empty state; the route is absent from the navigation for non-approver roles and returns 403 from the API if reached directly; table has proper header semantics and a caption for screen readers; no visitor contact detail rendered in the list view.
- **Dependencies:** `T-07.3.1.3`

---

#### US-07.3.2 — Filter and search the approval queue

**As an** FM Admin **I want** to filter the queue by tenant, date range and status **so that** I can find a specific request instead of paging through the day's volume.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Dependencies** | US-07.3.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the queue is displayed, **When** I filter by tenant, by a `scheduled_from` date range, or by status, **Then** the result set narrows accordingly and the filters combine conjunctively.
- **AC-2 — Given** I search by visitor name or host name, **When** the search executes, **Then** matching requests return, matching is case-insensitive, and the search is executed as a parameterised query.
- **AC-3 — Given** filters are applied, **When** I page or share the URL, **Then** filter state is preserved in query parameters and reproduces the same result set.
- **AC-4 (negative) — Given** I submit a filter value containing SQL metacharacters or a wildcard-heavy pattern, **When** the query runs, **Then** it is parameterised and the pattern is escaped so neither injection nor a pathological scan occurs.
- **AC-5 (negative) — Given** I filter by a tenant outside my scope, **When** the query runs, **Then** the scoping strategy applies before the filter and returns an empty result — the filter can only ever narrow, never widen, my visibility.

**Security considerations:** parameterised queries only, no string-concatenated SQL (OWASP A03); `LIKE` patterns escaped and anchored to prevent expensive scans; filters compose *after* the scope predicate so a filter cannot escalate visibility; filter values excluded from logs as they may contain visitor names.

**Development Tasks**

**T-07.3.2.1 — Filter specification composition** · `P1` · `1 pt` · deps: `T-07.3.1.2`
- **Description:** Build filters as composable JPA specifications applied on top of the scope predicate, covering tenant, date range, status and a name search across visitor and host.
- **Acceptance Criteria:** unit tests confirm the scope predicate is always present regardless of filter combination; `LIKE` special characters escaped; no filter path produces concatenated SQL; supporting index added for the name search.
- **Dependencies:** `T-07.3.1.2`

**T-07.3.2.2 — Filter parameters on the queue endpoint** · `P1` · `1 pt` · deps: `T-07.3.2.1, T-07.3.1.3`
- **Description:** Extend the pending-queue endpoint with validated, optional filter query parameters and document them in OpenAPI.
- **Acceptance Criteria:** invalid date range (from after to) returns 400; unknown status value returns 400 rather than silently ignoring; filter values do not appear in access logs.
- **Dependencies:** `T-07.3.2.1`

**T-07.3.2.3 — Filter and search controls in the dashboard** · `P2` · `1 pt` · deps: `T-07.3.2.2, T-07.3.1.4`
- **Description:** Filter bar with tenant select, date range, status select and a debounced search box, with filter state reflected in the URL.
- **Acceptance Criteria:** filters apply and clear; URL round-trips filter state; search is debounced to avoid a request per keystroke; controls are labelled and the result count is announced on change.
- **Dependencies:** `T-07.3.2.2`

---

#### US-07.3.3 — Open a request for review

**As an** FM Admin **I want** the full detail of a request, including every visitor **so that** I have what I need to make an approval decision.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Dependencies** | US-07.3.1, US-07.1.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I hold `visitor.approve`, **When** I open a request by id, **Then** I see the tenant, host, purpose, requested window, current status, and every attached visitor with name, company and visitor type.
- **AC-2 — Given** I am viewing a request, **When** the detail is rendered, **Then** a `vms.audit_logs` entry with `action = 'visitor_request.view'` records that visitor PII was accessed, with my user id and the request id.
- **AC-3 — Given** a request has already been decided, **When** I open it, **Then** the decision, the deciding user and the recorded reason are shown, and the approve/reject controls are absent.
- **AC-4 (negative) — Given** a request id that does not exist or lies outside my scope, **When** I open it, **Then** the API returns HTTP 404 with an identical response shape in both cases.
- **AC-5 (negative) — Given** I lack `visitor.approve`, **When** I request the detail endpoint directly, **Then** I receive 403 and no PII is included in the error body or the access log.

**Security considerations:** PII access is itself an audited event (AC-2) — this is the read that exposes visitor contact detail, so it is logged; 404 for both missing and out-of-scope to avoid enumeration; error bodies never echo the requested entity's contents.

**Development Tasks**

**T-07.3.3.1 — Request detail read model with PII access audit** · `P0` · `1 pt` · deps: `T-07.3.1.1`
- **Description:** Detail projection loading the request with its visitors, host and tenant, wrapped by an audit-on-read interceptor writing a `visitor_request.view` entry.
- **Acceptance Criteria:** one query loads visitors without an N+1; the audit entry is written even when the response is later discarded by the client; audit `after_state` is null for a read and does not duplicate the PII being audited.
- **Dependencies:** `T-07.3.1.1`

**T-07.3.3.2 — `GET /api/v1/visitor-requests/{id}` endpoint** · `P0` · `1 pt` · deps: `T-07.3.3.1, T-07.3.1.2`
- **Description:** Detail endpoint resolving through the scope strategy, guarded by `visitor.approve` for the FM Admin path and by tenant ownership for the requester path (F-07.6).
- **Acceptance Criteria:** 200 for an in-scope request; 404 for missing and out-of-scope alike, verified byte-identical; 403 without any relevant permission.
- **Dependencies:** `T-07.3.3.1`

**T-07.3.3.3 — Request review detail page** · `P0` · `1 pt` · deps: `T-07.3.3.2, T-07.3.1.4`
- **Description:** Detail view showing request metadata, the visitor table, and a decision panel that renders the recorded outcome when the request is already decided.
- **Acceptance Criteria:** decision controls render only for `submitted` state; decided requests show outcome, decider and reason; page reachable from a queue row and by direct URL; headings are hierarchical and the visitor table is properly associated with its caption.
- **Dependencies:** `T-07.3.3.2`

---

### F-07.4 — Approve / reject with recorded reason

The decision itself: an auditable, single-writer state transition with a mandatory reason on rejection.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Priority** | P0 · **Stories** 3 · **Tasks** 11 · **Points** 13 |
| **Depends on** | F-07.3, F-07.5 |

---

#### US-07.4.1 — Approve a visitor request

**As an** FM Admin **I want** to approve a visitor request **so that** the visitor becomes eligible for a credential and reception knows to expect them.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Dependencies** | US-07.3.3, US-07.5.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a request in `submitted` state and I hold `visitor.approve`, **When** I approve it, **Then** `vms.visitor_requests.status` becomes `approved`, `approved_by` is set to my user id, and every attached `vms.visitors` row moves from `pending` to `approved`.
- **AC-2 — Given** the approval commits, **When** the transaction completes, **Then** a `VisitorRequestApproved` domain event is published to Kafka carrying `request_id`, the visitor ids, and the approved window — and **no** visitor PII — for consumption by EPIC-09 credential orchestration.
- **AC-3 — Given** the approval commits, **When** I inspect the audit trail, **Then** a `vms.audit_logs` row exists with `action = 'visitor_request.approve'`, my `user_id`, the request id, and PII-redacted before/after states.
- **AC-4 — Given** I may add an optional note when approving, **When** I supply one, **Then** it is stored with the decision and is visible to the requesting tenant.
- **AC-5 (negative) — Given** a request already in `approved`, `rejected` or `cancelled` state, **When** I attempt to approve it, **Then** the API returns HTTP 409 naming the current state, nothing is written, and no event is published.
- **AC-6 (negative — concurrent approval) — Given** two FM Admins approve the same request simultaneously, **When** both transactions run, **Then** exactly one succeeds and the other receives 409 via optimistic locking; exactly one `VisitorRequestApproved` event is published, so EPIC-09 cannot be triggered twice for the same request.
- **AC-7 (negative) — Given** the approved window has already fully elapsed, **When** I approve, **Then** the request is rejected with HTTP 422 explaining that a past window cannot be approved, preventing an immediately-expired credential downstream.

**Security considerations:** `visitor.approve` enforced at the boundary; approval is a privileged state change and is audited unconditionally; single-event guarantee (AC-6) is the safeguard against duplicate ACS credential creation — a direct cost and security concern; the emitted event carries ids only.

**Development Tasks**

**T-07.4.1.1 — Approve transition on the aggregate** · `P0` · `2 pts` · deps: `T-07.5.1.1`
- **Description:** `approve(userId, note)` on `VisitorRequest`, validating the source state through the state machine, cascading visitor status, rejecting a fully-elapsed window, and registering the `VisitorRequestApproved` domain event on the aggregate.
- **Acceptance Criteria:** unit tests cover every source state, the elapsed-window rule, and cascade to all visitors including a mixed set where one visitor was individually cancelled; the event is registered on the aggregate, not published directly from the domain.
- **Dependencies:** `T-07.5.1.1`

**T-07.4.1.2 — `ApproveVisitorRequest` use case with single-event guarantee** · `P0` · `1 pt` · deps: `T-07.4.1.1, T-07.1.3.2, T-07.5.2.1`
- **Description:** Use case loading the aggregate under optimistic lock, applying the transition, writing the audit entry, and enqueuing the domain event to the transactional outbox so publication is exactly-once with respect to the state change.
- **Acceptance Criteria:** a two-thread concurrent-approval integration test yields one success, one 409, and exactly one outbox row; a simulated crash between commit and publish still results in the event being published on recovery; no event is published when the transition fails.
- **Dependencies:** `T-07.4.1.1, T-07.5.2.1`

**T-07.4.1.3 — Approve endpoint** · `P0` · `1 pt` · deps: `T-07.4.1.2, T-07.3.3.2`
- **Description:** `POST /api/v1/visitor-requests/{id}/approve` guarded by `visitor.approve`, accepting an optional note, translating domain failures to 409 and 422.
- **Acceptance Criteria:** 200 with the new state; 409 on illegal source state or version conflict; 422 on an elapsed window; 403 without permission; 404 out of scope; note length-bounded and stored.
- **Dependencies:** `T-07.4.1.2`

**T-07.4.1.4 — Approve action in the review UI** · `P0` · `1 pt` · deps: `T-07.4.1.3, T-07.3.3.3`
- **Description:** Approve control on the review page with a confirmation step, optional note field, optimistic UI update and reconciliation against the server response.
- **Acceptance Criteria:** control disabled while the call is in flight so a double-click cannot double-submit; a 409 refreshes the view and explains that the request was decided elsewhere; success routes back to the queue with a confirmation message; confirmation dialog is focus-trapped and dismissible by keyboard.
- **Dependencies:** `T-07.4.1.3`

---

#### US-07.4.2 — Reject a visitor request with a mandatory reason

**As an** FM Admin **I want** to reject a request and be required to say why **so that** the tenant understands the decision and the refusal is defensible after the fact.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-02 (SRS B1) |
| **Dependencies** | US-07.3.3, US-07.5.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a request in `submitted` state, **When** I reject it with a reason, **Then** `status` becomes `rejected`, `approved_by` records me as the deciding user, every attached visitor moves to `cancelled`, and the reason is persisted.
- **AC-2 — Given** rejection requires a reason, **When** I submit a rejection with an empty, whitespace-only or absent reason, **Then** the API returns HTTP 400 and nothing changes — the reason is mandatory at the API, not merely required by the form.
- **AC-3 — Given** a rejection commits, **When** the transaction completes, **Then** a `VisitorRequestRejected` domain event is published and an audit entry with `action = 'visitor_request.reject'` records the deciding user and the reason.
- **AC-4 — Given** a rejected request, **When** no credential exists for its visitors, **Then** no ACS call is ever made for that request — rejection is a terminal branch that never reaches EPIC-09.
- **AC-5 (negative) — Given** a request in `approved` state, **When** I attempt to reject it, **Then** the API returns 409; reversing an approval is a cancellation (US-07.1.3), not a rejection, and the two are not interchangeable.
- **AC-6 (negative) — Given** a reason containing HTML or script content, **When** it is stored and later rendered to the tenant, **Then** it is stored as text and output-encoded at render, so it cannot execute in the tenant's browser (stored XSS).

**Security considerations:** mandatory reason is an accountability control — enforced server-side so a modified client cannot omit it; reason is stored as text and output-encoded on render (OWASP A03); rejection audited with the reason; rejection guarantees no downstream ACS spend.

**Development Tasks**

**T-07.4.2.1 — Reject transition and reason persistence** · `P0` · `2 pts` · deps: `T-07.5.1.1`
- **Description:** `reject(userId, reason)` on the aggregate with a non-blank reason invariant, plus a forward-only migration adding `decision_reason text` and `decided_at timestamptz` to `vms.visitor_requests`.
- **Acceptance Criteria:** blank, whitespace-only and null reasons all rejected at the domain level; migration is forward-only and additive; visitors cascade to `cancelled`; unit tests cover rejection from every source state.
- **Dependencies:** `T-07.5.1.1`

**T-07.4.2.2 — `RejectVisitorRequest` use case** · `P0` · `1 pt` · deps: `T-07.4.2.1, T-07.5.2.1`
- **Description:** Use case mirroring approval: optimistic-locked load, transition, audit with the reason, outbox event.
- **Acceptance Criteria:** unit and integration tests cover the happy path, blank reason, illegal source state and concurrent decision; asserts that no credential-creation event is produced on this path.
- **Dependencies:** `T-07.4.2.1`

**T-07.4.2.3 — Reject endpoint with server-side reason enforcement** · `P0` · `1 pt` · deps: `T-07.4.2.2`
- **Description:** `POST /api/v1/visitor-requests/{id}/reject` requiring a bounded, non-blank reason.
- **Acceptance Criteria:** 400 on missing or blank reason proven by a direct API call bypassing the UI; 409 on illegal state; 403/404 as elsewhere; reason length bounded and the limit documented in OpenAPI.
- **Dependencies:** `T-07.4.2.2`

**T-07.4.2.4 — Reject action with reason capture in the UI** · `P0` · `1 pt` · deps: `T-07.4.2.3, T-07.3.3.3`
- **Description:** Reject control opening a dialog with a required reason field, optionally offering common reasons while still permitting free text.
- **Acceptance Criteria:** submit disabled until a non-blank reason is entered; the stored reason renders escaped wherever it is displayed; dialog is focus-trapped, the field is labelled, and the required state is announced.
- **Dependencies:** `T-07.4.2.3`

---

#### US-07.4.3 — Record and expose the decision trail

**As a** System Administrator **I want** every approval decision recorded immutably with who, when and why **so that** a disputed entry can be reconstructed months later.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-02 (SRS B1); `TDD-DERIVED` FR-AUD-01 (TDD §4.6) for the audit store itself |
| **Dependencies** | US-07.4.1, US-07.4.2, F-05.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** any decision on a request, **When** it commits, **Then** exactly one `vms.audit_logs` row exists carrying `user_id`, `action`, `entity_type = 'visitor_request'`, `entity_id`, `before_state`, `after_state` and the decision reason where applicable.
- **AC-2 — Given** an audit entry, **When** it is written, **Then** `before_state` and `after_state` contain status, window and decision fields only — never visitor name, email, phone or `id_document_ref`.
- **AC-3 — Given** an audit entry exists, **When** any actor attempts to update or delete it, **Then** the operation fails — the audit table is append-only at the database grant level, not merely by convention.
- **AC-4 — Given** a request's history, **When** an authorized administrator views it, **Then** the ordered decision trail renders from the audit store.
- **AC-5 (negative) — Given** the audit write fails, **When** the decision transaction runs, **Then** the whole transaction rolls back — a state change that cannot be audited does not happen.
- **AC-6 (negative) — Given** a user without audit-read authority, **When** they request the history endpoint, **Then** they receive 403.

**Security considerations:** append-only enforced by grant (no UPDATE/DELETE for the application role); PII-redacted state snapshots; audit failure is fail-closed (AC-5); audit read is itself permission-gated.

**Development Tasks**

**T-07.4.3.1 — PII-redacting audit state serializer** · `P1` · `1 pt` · deps: `F-05.1`
- **Description:** A serializer producing the `before_state`/`after_state` JSONB projections for visitor-domain entities, with an explicit allow-list of fields rather than a deny-list.
- **Acceptance Criteria:** allow-list based, so a newly added PII column cannot leak by default; a test adds a synthetic PII field to the entity and asserts it is absent from the projection; output is stable and diffable.
- **Dependencies:** `F-05.1`

**T-07.4.3.2 — Transactional audit write on decision paths** · `P1` · `1 pt` · deps: `T-07.4.3.1, T-07.4.1.2, T-07.4.2.2`
- **Description:** Wire the audit write into the approve, reject, amend and cancel use cases inside the same transaction as the state change.
- **Acceptance Criteria:** a forced audit-write failure rolls back the state change, proven by an integration test; exactly one audit row per decision, verified under the concurrent-approval test.
- **Dependencies:** `T-07.4.3.1`

**T-07.4.3.3 — Request history endpoint and view** · `P2` · `1 pt` · deps: `T-07.4.3.2`
- **Description:** `GET /api/v1/visitor-requests/{id}/history` returning the ordered audit trail, plus a history panel on the request detail page.
- **Acceptance Criteria:** 200 ordered chronologically for an authorized reader; 403 otherwise; the rendered trail shows actor, action, timestamp and reason with no PII; entries render escaped.
- **Dependencies:** `T-07.4.3.2`

---

### F-07.5 — Request state machine & domain events

> ⚠️ **`TDD-DERIVED` — backlog only.** Sourced from **TDD §4.2**, with no backing SRS functional
> requirement. Planned, estimated and sequenced so the phase has a realistic shape and cost, but
> **not authorized for implementation until TODO-01 is dispositioned.** Entering these stories into a
> sprint without that disposition is a process failure. *Pragmatic note for the client conversation:
> F-07.4 (`SRS` FR-VMS-02) cannot be built correctly without a transition guard of some kind, so this
> is the strongest candidate in the phase for being absorbed into an SRS requirement when TODO-01 is
> answered. It is called out here rather than smuggled in as an implementation detail.*

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` TDD §4.2 — pending TODO-01 |
| **Priority** | P0 *(if authorized — it is on the critical path of F-07.4 and EPIC-09)* · **Stories** 2 · **Tasks** 6 · **Points** 10 |
| **Depends on** | F-07.1, F-01.3 (Kafka) |

---

#### US-07.5.1 — Enforce legal request state transitions

**As a** System Administrator **I want** request status changes constrained to a defined state machine **so that** a request cannot be approved twice, rejected after approval, or resurrected from a terminal state.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §4.2 — **backlog only pending TODO-01** |
| **Dependencies** | US-07.1.1 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** the `vms.request_status` enum (`submitted`, `approved`, `rejected`, `cancelled`), **When** the state machine is defined, **Then** the legal transitions are exactly: `submitted → approved`, `submitted → rejected`, `submitted → cancelled`, `approved → cancelled`. All others are illegal.
- **AC-2 — Given** a legal transition, **When** it is applied, **Then** the aggregate's status changes and the corresponding domain event is registered for publication.
- **AC-3 — Given** the `vms.visitor_status` enum, **When** a request transitions, **Then** attached visitors cascade deterministically: `approved → approved`, `rejected → cancelled`, `cancelled → cancelled` — and a visitor already individually `cancelled` is not resurrected by a subsequent request-level transition.
- **AC-4 (negative) — Given** an illegal transition such as `rejected → approved` or `cancelled → approved`, **When** it is attempted through any path — use case, event handler, or direct repository call, **Then** a domain exception is raised and no state is mutated.
- **AC-5 (negative) — Given** a transition is attempted on a stale aggregate version, **When** it is saved, **Then** the optimistic lock rejects it, so the state machine cannot be defeated by a concurrent read-modify-write.
- **AC-6 (negative) — Given** a developer adds a new value to `vms.request_status` without extending the transition table, **When** the build runs, **Then** an exhaustiveness test fails rather than the new state silently permitting everything.

**Security considerations:** the state machine is the integrity control behind the approval workflow — it is what prevents an attacker or a bug from turning a rejection into an approval; enforced in the domain layer so no interface-layer path can bypass it (AC-4); exhaustiveness test prevents silent erosion (AC-6).

**Development Tasks**

**T-07.5.1.1 — Transition table and guard in the domain layer** · `P0` · `2 pts` · deps: `T-07.1.1.1`
- **Description:** Declarative transition table for `RequestStatus` plus a guard invoked by every mutating aggregate method. Framework-free, in the `domain` module.
- **Acceptance Criteria:** a parameterised unit test asserts every source×target cell, legal and illegal; the guard is the single place transitions are decided; no aggregate method mutates status without passing through it.
- **Dependencies:** `T-07.1.1.1`

**T-07.5.1.2 — Visitor status cascade rules** · `P0` · `2 pts` · deps: `T-07.5.1.1`
- **Description:** Cascade mapping from request transition to `vms.visitor_status`, honouring individually cancelled visitors as terminal.
- **Acceptance Criteria:** unit tests cover a mixed visitor set with one already cancelled; the cascade never moves a visitor out of a terminal state; cascade applied within the same transaction as the request change.
- **Dependencies:** `T-07.5.1.1`

**T-07.5.1.3 — Enum exhaustiveness and architecture fitness tests** · `P1` · `1 pt` · deps: `T-07.5.1.1`
- **Description:** A build-time test asserting every `request_status` and `visitor_status` enum value appears in the transition and cascade tables, and a fitness test asserting no code outside the aggregate assigns status directly.
- **Acceptance Criteria:** adding an unmapped enum value fails the build; a direct status assignment from a service or repository class fails the fitness test; both run on every commit.
- **Dependencies:** `T-07.5.1.1`

---

#### US-07.5.2 — Publish request lifecycle domain events

**As a** System Administrator **I want** each request lifecycle change published as a Kafka domain event **so that** credential generation, notification and reporting react without reaching into the visitor service's tables.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §4.2 — **backlog only pending TODO-01** |
| **Dependencies** | US-07.5.1, F-01.3 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** a request transition commits, **When** the transaction completes, **Then** the corresponding event — `VisitorRequestSubmitted`, `VisitorRequestApproved`, `VisitorRequestRejected`, `VisitorRequestCancelled`, `VisitorRequestAmended` — is published to its Kafka topic with a versioned schema.
- **AC-2 — Given** an event is published, **When** its payload is inspected, **Then** it carries `request_id`, `tenant_id`, visitor ids, the window, the actor id, a `correlation_id` and an `event_id` — and **no** visitor name, email, phone, company or `id_document_ref`.
- **AC-3 — Given** the state change and the publication, **When** either the database or the broker fails, **Then** the transactional outbox guarantees the event is published exactly once for a committed state change and never for a rolled-back one.
- **AC-4 — Given** a consumer receives the same `event_id` twice, **When** it processes the duplicate, **Then** the consumer contract requires idempotent handling and the event carries the key needed to achieve it.
- **AC-5 (negative) — Given** Kafka is unavailable, **When** a request is approved, **Then** the approval still commits, the outbox row is retained, and publication resumes when the broker returns — approval is never lost because messaging is down (NFR-REL-01 (SRS B1)).
- **AC-6 (negative) — Given** an event schema change removes or renames a field, **When** CI runs, **Then** the schema compatibility check fails, preventing a breaking change reaching consumers.

**Security considerations:** events are a PII exfiltration path if built carelessly — AC-2 makes ids-only the contract and a serialization test enforces it; the outbox prevents the "approved but nobody told the credential service" failure mode; schema compatibility gate protects downstream consumers.

**Development Tasks**

**T-07.5.2.1 — Transactional outbox for the visitor service** · `P0` · `2 pts` · deps: `T-07.1.1.2`
- **Description:** Outbox table and a relay publishing to Kafka, with at-least-once delivery, ordered per aggregate id, and a forward-only migration creating the table with an index on unpublished rows.
- **Acceptance Criteria:** a broker-down integration test shows the state change committing and the event publishing on recovery; a rolled-back transaction leaves no outbox row; relay is idempotent across restarts; per-aggregate ordering preserved via the partition key.
- **Dependencies:** `T-07.1.1.2`

**T-07.5.2.2 — Versioned event schemas with a no-PII contract test** · `P0` · `2 pts` · deps: `T-07.5.2.1`
- **Description:** Define the five lifecycle event schemas with explicit versioning and register them; add a serialization test asserting no PII field name or value appears in any serialized payload.
- **Acceptance Criteria:** the no-PII test fails if a visitor name is added to any event; schema registry compatibility set to backward and enforced in CI; each event carries `event_id`, `correlation_id`, `occurred_at`.
- **Dependencies:** `T-07.5.2.1`

**T-07.5.2.3 — Event publication wiring and consumer documentation** · `P1` · `1 pt` · deps: `T-07.5.2.2`
- **Description:** Wire event registration on the aggregate through to the outbox for every transition, and document the topic names, keys, schemas and idempotency expectations for downstream consumers (EPIC-09, Phase 4 notification).
- **Acceptance Criteria:** every legal transition produces exactly one event, verified by a table-driven integration test; the consumer contract document is committed alongside the schemas; topic naming follows the project convention.
- **Dependencies:** `T-07.5.2.2`

---

### F-07.6 — Request status visibility for tenants

The tenant who raised a request can see what happened to it, without asking anyone.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Priority** | P1 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-07.1, F-07.4 |

---

#### US-07.6.1 — View my tenant's requests and their status

**As a** Tenant **I want** to see the requests my organisation has raised and their current status **so that** I know whether my visitor is expected before they turn up at the door.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) |
| **Dependencies** | US-07.1.1, US-07.4.1, US-07.4.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am authenticated as a Tenant, **When** I open my requests list, **Then** I see only requests whose `tenant_id` matches my own user's tenant, with status, window, host, visitor count and decision outcome.
- **AC-2 — Given** the list is displayed, **When** I filter by status or date range, **Then** the filters apply within my tenant scope and cannot widen it.
- **AC-3 — Given** a request was rejected, **When** I view it, **Then** the recorded rejection reason is shown, output-encoded, alongside the decision timestamp.
- **AC-4 (negative) — Given** a request belonging to another tenant, **When** I request it by id, **Then** I receive HTTP 404 identical in shape and timing to a genuinely missing id.
- **AC-5 (negative) — Given** I am a Tenant, **When** I attempt to call the FM Admin pending-queue endpoint to see all tenants' requests, **Then** I receive 403 and an authorization-denied audit entry is written.
- **AC-6 (negative) — ⚠️ TODO-14 — Given** the isolation model is unconfirmed, **When** this story is implemented, **Then** the restrictive default (own tenant only) is applied and recorded as a provisional assumption, so a later decision to widen visibility is a deliberate change rather than a discovered gap.

**Security considerations:** this is the phase's primary IDOR surface (OWASP A01) — every read is scoped by the authenticated user's `tenant_id` in the repository specification, never in the controller and never in the UI; 404-not-403 to avoid enumeration; TODO-14 restrictive default recorded as a provisional ADR.

**Development Tasks**

**T-07.6.1.1 — Tenant-scoped request repository specification** · `P1` · `1 pt` · deps: `T-07.1.1.2, T-07.3.1.2`
- **Description:** A repository specification that unconditionally applies the caller's `tenant_id` predicate for tenant-role reads, applied at the repository rather than the service layer so no caller can forget it.
- **Acceptance Criteria:** an integration test suite calls every tenant-facing read with a foreign id and asserts an empty result or 404 in all cases; a fitness test asserts tenant-facing repository methods cannot be invoked without the scope parameter; the predicate is applied before any user-supplied filter.
- **Dependencies:** `T-07.3.1.2`

**T-07.6.1.2 — Tenant request list and detail endpoints** · `P1` · `2 pts` · deps: `T-07.6.1.1, T-07.3.3.2`
- **Description:** `GET /api/v1/visitor-requests` (own tenant, paginated, filterable) and the tenant view of the detail endpoint, guarded by `visitor.request`.
- **Acceptance Criteria:** 200 scoped to own tenant; 404 for a foreign id; page size clamped; rejection reason included in the detail payload; response excludes `approved_by`'s personal details beyond a display name.
- **Dependencies:** `T-07.6.1.1`

**T-07.6.1.3 — Tenant "My requests" page** · `P1` · `2 pts` · deps: `T-07.6.1.2`
- **Description:** Portal page listing the tenant's requests with a status chip, filters, and a detail view showing the decision and reason.
- **Acceptance Criteria:** status chips are distinguishable without relying on colour alone (WCAG 1.4.1); rejection reason renders escaped; empty and error states handled; filters reflected in the URL.
- **Dependencies:** `T-07.6.1.2`

---

#### US-07.6.2 — See status changes without refreshing

**As a** Tenant **I want** the status of my request to update as decisions are made **so that** I am not refreshing a page to find out whether my visitor is approved.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-01 (SRS B1) — visibility of request status |
| **Dependencies** | US-07.6.1, US-07.5.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am viewing my requests, **When** an FM Admin decides one of them, **Then** the displayed status updates within the configured polling interval without a manual refresh.
- **AC-2 — Given** the update mechanism, **When** it fetches, **Then** it reuses the same tenant-scoped, authorized endpoint as the initial load — no separate unauthenticated or less-strictly-scoped channel exists.
- **AC-3 — Given** the page is backgrounded or hidden, **When** the polling timer fires, **Then** polling pauses, resuming on visibility, so an idle tab does not generate indefinite load.
- **AC-4 (negative) — Given** my session expires while the page is open, **When** the next poll runs, **Then** it receives 401, polling stops, and I am prompted to re-authenticate rather than the page silently retrying forever.
- **AC-5 (negative) — Given** the backend is unavailable, **When** successive polls fail, **Then** the interval backs off exponentially to a ceiling and the UI shows a stale-data indicator rather than an empty list.

**Security considerations:** no separate push channel is introduced in this phase, so no new authentication surface; polling reuses the authorized, tenant-scoped endpoint; 401 stops the loop rather than looping on an expired session; backoff prevents a client-side thundering herd against the API.

**Development Tasks**

**T-07.6.2.1 — Polling hook with visibility awareness and backoff** · `P2` · `1 pt` · deps: `T-07.6.1.3`
- **Description:** A reusable client data hook wrapping the request list/detail fetch with an interval, page-visibility pausing, exponential backoff on failure, and termination on 401.
- **Acceptance Criteria:** timer pauses when the document is hidden and resumes on show; consecutive failures back off to the configured ceiling; a 401 stops polling and raises the re-authentication prompt; the hook is unit tested with fake timers.
- **Dependencies:** `T-07.6.1.3`

**T-07.6.2.2 — Stale-data and reconnect indicators** · `P3` · `1 pt` · deps: `T-07.6.2.1`
- **Description:** Visual treatment for stale data and reconnection state on the tenant request views.
- **Acceptance Criteria:** stale state is conveyed by text and icon, not colour alone; the indicator is announced politely to assistive technology without stealing focus; recovering from staleness clears the indicator.
- **Dependencies:** `T-07.6.2.1`

**T-07.6.2.3 — Cached read path for status polling** · `P3` · `1 pt` · deps: `T-07.6.1.2`
- **Description:** Add a short-TTL Redis cache and an `ETag`/`If-None-Match` path on the tenant request list endpoint so repeated polls are cheap.
- **Acceptance Criteria:** an unchanged list returns 304 with no database query; the cache key includes the caller's `tenant_id` so no cross-tenant cache bleed is possible — asserted by a two-tenant integration test; TTL is short enough that a decision is visible within the polling interval.
- **Dependencies:** `T-07.6.1.2`

---

## EPIC-08 — Visitor Pre-Registration

| | |
|---|---|
| **Provenance** | `SRS` — FR-VMS-03 (SRS B1), FR-VMS-11 (SRS B1) |
| **Requirement IDs** | FR-VMS-03 (SRS B1), FR-VMS-11 (SRS B1); NFR-REL-01 (SRS B1), NFR-USA-01 (SRS B1), NFR-SCL-01 (SRS B1) |
| **Priority** | **P0** — FR-VMS-03 is a named phase requirement and the second input path into the credential pipeline |
| **Features** | 4 (F-08.1 … F-08.4) |
| **Stories / Tasks / Points** | 10 stories · 32 tasks · **40 points** |
| **Bounded context** | Visitor & Approval — `VisitorRequest` aggregate, `Visitor` entity |
| **Primary tables** | `vms.visitors`, `vms.visitor_requests`, `vms.receptions`, `vms.floors`, `vms.visitor_types`, `vms.audit_logs` |

**Goal.** Cover the second way a visitor gets into the system: not the tenant raising a request from
their desk, but a floor receptionist typing the visitor's details straight into VMS and submitting them
to the central server as a pre-registration record ahead of arrival — **FR-VMS-03 (SRS B1)**. This is a
high-volume, low-training workflow performed by up to 120 floor reception users (**NFR-SCL-01**,
**NFR-USA-01**), so it must be fast to complete, forgiving of interruption, and honest about whether the
record actually reached the central server. The epic also owns the appointment window
(**FR-VMS-11 (SRS B1)**) that EPIC-09 later turns into a credential validity window, and the visitor
record's own status lifecycle.

---

### F-08.1 — Floor receptionist pre-registration

A floor receptionist enters visitor details in VMS ahead of the visitor's arrival.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-03 (SRS B1) |
| **Priority** | P0 · **Stories** 3 · **Tasks** 10 · **Points** 13 |
| **Depends on** | EPIC-02, EPIC-03, F-04.4 (receptions), F-04.5 (visitor types), F-06.3 |

---

#### US-08.1.1 — Pre-register a visitor from the floor reception desk

**As a** Floor Receptionist **I want** to enter a visitor's details in VMS before they arrive **so that** the visitor is already known to the system and central reception is not typing while the visitor stands waiting.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-03 (SRS B1) |
| **Dependencies** | F-02.1, F-03.2, F-04.4, F-04.5, F-06.3, US-10.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am authenticated as a Floor Receptionist holding `visitor.register`, **When** I submit visitor details (name, email, phone, company, visitor type), a host, a purpose and an appointment window, **Then** a `vms.visitor_requests` row is created with `visit_kind = 'pre_scheduled'`, and a `vms.visitors` row is created with `appointment_from` / `appointment_to` populated.
- **AC-2 — Given** I am a Floor Receptionist attached to a reception point, **When** the record is created, **Then** its `tenant_id` is derived from my `vms.users.reception_id` → `vms.receptions.floor_id` → tenant association, never from the submitted payload.
- **AC-3 — Given** a pre-registration is created, **When** it commits, **Then** an audit entry with `action = 'visitor.pre_register'` records my user id, my reception point and the visitor id, and a `VisitorPreRegistered` domain event is published carrying ids only.
- **AC-4 — Given** the workflow must require minimal training (**NFR-USA-01 (SRS B1)**), **When** I complete a pre-registration, **Then** the entire flow is a single screen with no more than the required fields, and the form is completable by keyboard alone from first field to submit.
- **AC-5 (negative) — Given** an appointment window whose `appointment_to` is not after `appointment_from`, or that starts in the past beyond a configured grace period, **When** I submit, **Then** the API returns 400 or 422 with a field-level error and nothing is persisted.
- **AC-6 (negative) — Given** I am authenticated as a Floor Receptionist, **When** I attempt to pre-register a visitor against a floor or reception point other than my own, **Then** the request is rejected with 403 and an authorization-denied audit entry is written — ⚠️ **TODO-14**: the floor-scoping rule is the restrictive provisional default pending the isolation decision.

**Security considerations:** `visitor.register` permission at the boundary; tenant and floor derived server-side from the session (OWASP A01); full visitor PII captured here, so field validation, no-PII-in-logs and audit-on-create all apply; `id_document_ref` deliberately not captured (⚠️ TODO-13); floor scoping is the restrictive default pending TODO-14.

**Development Tasks**

**T-08.1.1.1 — `PreRegisterVisitor` use case** · `P0` · `1 pt` · deps: `T-07.1.1.3`
- **Description:** Application use case constructing a `VisitorRequest` in `submitted` state with a single visitor carrying an appointment window, resolving tenant and floor from the authenticated receptionist's `reception_id`.
- **Acceptance Criteria:** unit tests cover the happy path, an unresolvable reception association, and an out-of-scope floor; the tenant and floor are never read from the command DTO; reuses the F-07.1 aggregate rather than introducing a parallel model.
- **Dependencies:** `T-07.1.1.3`

**T-08.1.1.2 — Reception-to-floor-to-tenant resolution port** · `P0` · `1 pt` · deps: `F-04.4`
- **Description:** Read port resolving a user's `reception_id` to its floor and the tenants on that floor, served from the master-data cache rather than a cross-service join.
- **Acceptance Criteria:** resolution is cached with explicit invalidation on master-data change; a user with a null `reception_id` produces a clear domain error rather than a null-pointer path; service-boundary fitness test confirms no direct read of `vms.receptions` from the visitor service.
- **Dependencies:** `F-04.4`

**T-08.1.1.3 — `POST /api/v1/pre-registrations` endpoint** · `P0` · `1 pt` · deps: `T-08.1.1.1, T-08.1.1.2`
- **Description:** REST endpoint guarded by `visitor.register`, with Bean Validation on the visitor and window fields and a DTO that omits tenant, floor and status.
- **Acceptance Criteria:** 201 with the created visitor and request ids; 400 on invalid window or missing name; 403 for a foreign floor or a missing permission; 401 unauthenticated; OpenAPI documented; mass-assignment test proves tenant/floor overrides are ignored.
- **Dependencies:** `T-08.1.1.2`

**T-08.1.1.4 — Single-screen pre-registration form (Next.js)** · `P0` · `2 pts` · deps: `T-08.1.1.3`
- **Description:** Floor reception route with a compact, keyboard-first form: visitor fields, visitor type, host, purpose, appointment window, submit. Field order and tab order optimised for repetitive data entry.
- **Acceptance Criteria:** the whole form is completable without a mouse and submits on Enter from the last field; the first field is focused on load; validation errors are announced and focus moves to the first offending field; WCAG 2.1 AA; no PII in console or client error reporting.
- **Dependencies:** `T-08.1.1.3`

---

#### US-08.1.2 — Reuse a returning visitor's details

**As a** Floor Receptionist **I want** to look up a visitor I have registered before **so that** a regular contractor does not have their details retyped every visit.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-03 (SRS B1) — efficiency of the pre-registration workflow; NFR-USA-01 (SRS B1) |
| **Dependencies** | US-08.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am pre-registering and start typing a name or email, **When** at least the configured minimum characters are entered, **Then** matching prior visitors within my authorized scope are suggested with name and company.
- **AC-2 — Given** I select a suggestion, **When** the form populates, **Then** name, email, phone, company and visitor type are copied into a **new** `vms.visitors` row — the prior record is never mutated and the two visits remain independently auditable.
- **AC-3 — Given** a suggestion is selected, **When** I amend a copied field before submitting, **Then** the amendment applies to the new record only.
- **AC-4 (negative) — Given** lookup would return visitors from outside my authorized scope, **When** the query executes, **Then** it does not return them — the same scope predicate as every other read applies, so a receptionist cannot enumerate another floor's or tenant's visitors through the autocomplete.
- **AC-5 (negative) — Given** a short or empty search term, **When** it is submitted, **Then** no query runs and no results are returned, preventing a full-table PII dump via a one-character search.
- **AC-6 (negative) — Given** repeated lookups from one session, **When** the rate exceeds the configured threshold, **Then** requests are throttled with 429, bounding bulk PII harvesting through the autocomplete.

**Security considerations:** an autocomplete over visitor PII is a directory-harvesting surface (OWASP A01/A04) — minimum term length, scope predicate, result-count ceiling, rate limiting, and a distinct audit action `visitor.lookup` so harvesting is detectable after the fact; search terms excluded from logs.

**Development Tasks**

**T-08.1.2.1 — Scoped visitor lookup query with harvesting controls** · `P2` · `2 pts` · deps: `T-07.6.1.1, T-08.1.1.2`
- **Description:** Read query over prior `vms.visitors` records, scope-predicated, with a minimum term length, a hard result ceiling, and a trigram or prefix index to keep it cheap.
- **Acceptance Criteria:** below-minimum terms short-circuit without a query; results capped regardless of matches; cross-scope records never returned, proven by a two-floor integration test; query plan uses the index at 100,000 visitor rows.
- **Dependencies:** `T-08.1.1.2`

**T-08.1.2.2 — Lookup endpoint with rate limiting and audit** · `P2` · `2 pts` · deps: `T-08.1.2.1`
- **Description:** `GET /api/v1/visitors:lookup` guarded by `visitor.register`, rate limited per user via Redis, writing a `visitor.lookup` audit entry recording the actor and result count but not the search term.
- **Acceptance Criteria:** 429 with `Retry-After` beyond the threshold; audit entry records count only; search term absent from access logs and audit; 403 without permission.
- **Dependencies:** `T-08.1.2.1`

**T-08.1.2.3 — Autocomplete and copy-forward in the form** · `P2` · `1 pt` · deps: `T-08.1.2.2, T-08.1.1.4`
- **Description:** Debounced autocomplete on the pre-registration form that populates fields from a selection while leaving all fields editable.
- **Acceptance Criteria:** suggestions are keyboard-navigable with proper combobox semantics and announced result counts; selecting populates without locking fields; the created record is new, verified by an integration test asserting two distinct visitor ids; suggestions are not persisted in browser storage.
- **Dependencies:** `T-08.1.2.2`

---

#### US-08.1.3 — Amend or cancel a pre-registration before arrival

**As a** Floor Receptionist **I want** to correct or withdraw a pre-registration before the visitor arrives **so that** a typo or a cancelled meeting does not become a failed check-in at the desk.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-03 (SRS B1) |
| **Dependencies** | US-08.1.1, US-07.1.3, US-08.4.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a pre-registration whose visitor is in `pending` or `approved` status, **When** I amend visitor details or the appointment window, **Then** the change persists and an audit entry records before and after (PII-redacted).
- **AC-2 — Given** a pre-registration I created, **When** I cancel it, **Then** the visitor moves to `cancelled`, and if it was the only visitor the parent request moves to `cancelled` too.
- **AC-3 — Given** a credential has already been issued for the visitor, **When** I cancel the pre-registration, **Then** a revocation signal is raised for F-09.7 and the audit entry records that cancellation followed issuance.
- **AC-4 (negative) — Given** a visitor already `checked_in` or `inside`, **When** I amend or cancel, **Then** the API returns 409 — a visitor already in the building is not editable from the pre-arrival workflow.
- **AC-5 (negative) — Given** a pre-registration created by a receptionist on another floor, **When** I attempt to amend it, **Then** I receive 404 and an authorization-denied audit entry is written.

**Security considerations:** object-level authorization by floor scope; terminal and in-progress states protected against retroactive edit, which preserves the integrity of the entry record; audit on both amend and cancel; cancellation-after-issuance explicitly linked to credential revocation so a withdrawn visitor does not retain a working pass.

**Development Tasks**

**T-08.1.3.1 — Visitor-level amend and cancel transitions** · `P1` · `1 pt` · deps: `T-08.4.1.1`
- **Description:** Visitor-scoped amend and cancel operations on the `VisitorRequest` aggregate, guarded by the visitor status state machine and cascading to the parent request when the last active visitor is cancelled.
- **Acceptance Criteria:** unit tests cover amend and cancel from every `vms.visitor_status` value; `checked_in` and `inside` reject with a domain exception; cancelling the last active visitor cancels the request, cancelling one of several does not.
- **Dependencies:** `T-08.4.1.1`

**T-08.1.3.2 — Pre-registration amend and cancel endpoints** · `P1` · `1 pt` · deps: `T-08.1.3.1, T-08.1.1.3`
- **Description:** `PATCH /api/v1/pre-registrations/{visitorId}` and `POST /api/v1/pre-registrations/{visitorId}/cancel`, floor-scoped.
- **Acceptance Criteria:** 200 on legal transition, 409 on `checked_in`/`inside` or version conflict, 404 for another floor's record; cancellation after issuance emits the revocation signal exactly once.
- **Dependencies:** `T-08.1.3.1`

**T-08.1.3.3 — Pre-registration list and edit UI** · `P1` · `1 pt` · deps: `T-08.1.3.2`
- **Description:** Floor reception view of today's pre-registrations with inline status, and edit/cancel actions shown only where legal.
- **Acceptance Criteria:** actions hidden for `checked_in`, `inside` and terminal states; a 409 from a stale view refreshes and explains; cancel confirmation states the credential-revocation consequence when a credential exists.
- **Dependencies:** `T-08.1.3.2`

---

### F-08.2 — Central server submission & synchronisation

The pre-registration reaches the central server reliably, and the receptionist knows whether it did.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-03 (SRS B1) — *"submit them to the central server"*; `SRS-NFR` NFR-REL-01 (SRS B1), NFR-AVL-01 (SRS B1) |
| **Priority** | P1 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-08.1 |

---

#### US-08.2.1 — Confirm central submission with a durable receipt

**As a** Floor Receptionist **I want** unambiguous confirmation that a pre-registration reached the central server **so that** I am not sending a visitor to a central desk that has never heard of them.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-03 (SRS B1); `SRS-NFR` NFR-REL-01 (SRS B1) |
| **Dependencies** | US-08.1.1, US-07.5.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I submit a pre-registration, **When** the central server has durably committed it, **Then** I receive an explicit confirmation carrying the server-assigned visitor id and the commit timestamp — not merely an HTTP 200.
- **AC-2 — Given** a submission is confirmed, **When** central reception later searches for the visitor, **Then** the record is present and visible to them, so the pre-arrival handoff between floor and central reception actually works.
- **AC-3 — Given** I submit the same pre-registration twice because I was unsure the first succeeded, **When** the second submission carries the same client-generated idempotency key, **Then** the server returns the original record rather than creating a duplicate visitor.
- **AC-4 (negative) — Given** the submission fails or times out, **When** the failure is surfaced, **Then** the UI states clearly that the record was **not** saved centrally, retains my entered data, and offers retry — it never shows an ambiguous success.
- **AC-5 (negative) — Given** the database is unavailable mid-submission, **When** the transaction fails, **Then** nothing partial is persisted and no confirmation is issued (NFR-REL-01 (SRS B1) — pre-registration and approval data are not lost or half-written).
- **AC-6 (negative) — Given** a replayed idempotency key from a different user or with a different payload, **When** it is submitted, **Then** the server rejects it with 409 rather than returning another user's record.

**Security considerations:** idempotency keys are scoped to the submitting user and bound to a payload hash so a guessed or replayed key cannot return someone else's visitor record (AC-6); keys held in Redis with a bounded TTL; failure messaging avoids leaking internal error detail while still being actionable.

**Development Tasks**

**T-08.2.1.1 — Idempotent submission with user-scoped keys** · `P1` · `2 pts` · deps: `T-08.1.1.1`
- **Description:** `Idempotency-Key` header handling on the pre-registration endpoint, storing key → (user id, payload hash, result reference) in Redis with a TTL, returning the stored result on replay.
- **Acceptance Criteria:** identical replay returns the original 201 body with no second row; same key with a different payload or a different user returns 409; key TTL configurable; concurrent identical submissions resolve to one record via an atomic set-if-absent.
- **Dependencies:** `T-08.1.1.1`

**T-08.2.1.2 — Durable-commit confirmation contract** · `P1` · `2 pts` · deps: `T-08.2.1.1`
- **Description:** Response contract returning the server-assigned ids and commit timestamp only after the transaction commits, with the outbox event enqueued in the same transaction.
- **Acceptance Criteria:** an integration test forcing a post-write, pre-commit failure yields no confirmation and no persisted row; the response is never produced from a pre-commit state; the commit timestamp is the database time, not the application clock.
- **Dependencies:** `T-08.2.1.1`

**T-08.2.1.3 — Unambiguous submission feedback and retry in the UI** · `P1` · `1 pt` · deps: `T-08.2.1.2, T-08.1.1.4`
- **Description:** Submission state machine in the form — submitting, confirmed, failed — generating the idempotency key client-side, preserving entered data on failure, and offering an explicit retry that reuses the same key.
- **Acceptance Criteria:** a simulated network failure shows the not-saved state with data intact; retry reuses the key and cannot create a duplicate; the confirmed state displays the server id; states are announced to assistive technology.
- **Dependencies:** `T-08.2.1.2`

---

#### US-08.2.2 — Make pre-registrations visible to central reception

**As a** Central Receptionist (Master Admin) **I want** to see the pre-registrations submitted by all floors **so that** I can prepare for the day's arrivals and confirm a visitor is expected.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-03 (SRS B1); enables FR-VMS-04 (SRS B1) in Phase 3 |
| **Dependencies** | US-08.2.1, F-03.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am authenticated as a Master Admin, **When** I open the central pre-registration view, **Then** I see pre-registrations across all floors for a selected date, showing visitor name, company, host, floor, appointment window and status.
- **AC-2 — Given** the list is displayed, **When** I filter by floor, status or appointment window, **Then** it narrows accordingly, server-side paginated and bounded.
- **AC-3 — Given** a new pre-registration is submitted by any floor, **When** I refresh or the view polls, **Then** it appears without any manual synchronisation step.
- **AC-4 (negative) — Given** I am a Floor Receptionist, **When** I request the all-floors endpoint, **Then** I receive 403 — the cross-floor view is a Master Admin capability, and the restriction is enforced at the API, not by hiding the menu item.
- **AC-5 (negative) — Given** the list is rendered, **When** its payload is inspected, **Then** visitor email, phone and `id_document_ref` are absent from the list projection; contact detail requires opening the audited detail view.

**Security considerations:** the broadest PII view in the phase, so it is the most tightly permissioned — Master Admin only, list projection minimised, detail read audited (reusing US-07.3.3's audit-on-read); ⚠️ **TODO-14** — whether a Master Admin genuinely sees all tenants is exactly the isolation question, so the view is built through the same scoping strategy and the permissive setting is explicit and logged, not implicit.

**Development Tasks**

**T-08.2.2.1 — Cross-floor pre-registration read model** · `P1` · `1 pt` · deps: `T-07.3.1.1, T-08.1.1.2`
- **Description:** Read projection joining visitors to their request, floor and host for a date range, with a covering index on `(appointment_from, status)` and a minimised, PII-light field set.
- **Acceptance Criteria:** no visitor email, phone or document reference in the projection; indexed query at 500 visitors/day × 90 days without a sequential scan; ordering deterministic.
- **Dependencies:** `T-08.1.1.2`

**T-08.2.2.2 — Central pre-registration endpoint** · `P1` · `1 pt` · deps: `T-08.2.2.1, T-07.3.1.2`
- **Description:** `GET /api/v1/pre-registrations` scoped by the strategy, guarded by a Master Admin permission, paginated and filterable by floor, date and status.
- **Acceptance Criteria:** 200 for Master Admin; 403 for Floor Receptionist and Tenant; page size clamped; the active scope strategy is reflected in the results and logged at startup.
- **Dependencies:** `T-08.2.2.1`

**T-08.2.2.3 — Central reception day view** · `P1` · `1 pt` · deps: `T-08.2.2.2`
- **Description:** Master Admin page listing the day's expected visitors with filters and a link to the audited detail view.
- **Acceptance Criteria:** defaults to today; filters reflected in the URL; empty and error states handled; table semantics and caption present; contact detail reachable only through the detail view, which writes the PII-access audit entry.
- **Dependencies:** `T-08.2.2.2`

---

### F-08.3 — Appointment scheduling & validity windows

The appointment window on the visitor record — the value EPIC-09 turns into a credential validity window.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-11 (SRS B1) |
| **Priority** | P0 · **Stories** 3 · **Tasks** 10 · **Points** 11 |
| **Depends on** | F-08.1, F-04.6 (pass types), F-04.7 (holiday calendar) |

---

#### US-08.3.1 — Set an appointment window on a visitor

**As a** Floor Receptionist **I want** each visitor to carry an explicit appointment date and time slot **so that** the credential VMS later requests is valid for exactly that period and no longer.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-11 (SRS B1) |
| **Dependencies** | US-08.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I set an appointment window, **When** the visitor is saved, **Then** `vms.visitors.appointment_from` and `appointment_to` are persisted as `timestamptz`, with the entered local time converted to an absolute instant on the way in.
- **AC-2 — Given** a visitor has no explicit window, **When** the record is saved, **Then** the window is derived from the parent request's `scheduled_from` / `scheduled_to`, and if that is also absent, from the pass type's `default_valid_hours` or the `default_pass_valid_hours` system setting.
- **AC-3 — Given** a window is set or derived, **When** it is displayed anywhere in the portal, **Then** it renders in the building's configured timezone with the zone shown explicitly, so a receptionist cannot misread a window because their workstation is set to a different zone.
- **AC-4 (negative) — Given** `appointment_to` is not strictly after `appointment_from`, **When** validation runs, **Then** the API returns 400 before the database `CHECK` constraint is reached.
- **AC-5 (negative) — Given** a window whose duration exceeds the configured maximum, **When** it is submitted, **Then** it is rejected with 422 naming the limit — an unbounded window becomes an unbounded credential.
- **AC-6 (negative — DST) — Given** an appointment window spanning a daylight-saving transition in the building's timezone, **When** the window is stored and rendered, **Then** the absolute duration is preserved and the rendered local times reflect the transition, with tests asserting both the spring-forward and autumn-back cases.

**Security considerations:** the validity window is a security control, not a convenience — it bounds how long a VMS-provisioned credential opens a door, so the maximum-duration ceiling (AC-5) and correct timezone handling (AC-6) directly limit the blast radius of a mis-entered appointment.

**Development Tasks**

**T-08.3.1.1 — `AppointmentWindow` value object with duration ceiling** · `P0` · `2 pts` · deps: `T-07.1.1.1`
- **Description:** Domain value object over `appointment_from`/`appointment_to` with a strictly-positive-duration invariant and an injected maximum-duration policy; explicitly instant-based, never local-date-time.
- **Acceptance Criteria:** unit tests cover zero, negative, at-ceiling and over-ceiling durations; the type holds instants only; the ceiling is injected from configuration; DST-spanning windows preserve absolute duration.
- **Dependencies:** `T-07.1.1.1`

**T-08.3.1.2 — Window derivation chain** · `P0` · `1 pt` · deps: `T-08.3.1.1, F-04.6, F-04.8`
- **Description:** Resolution order — explicit visitor window, else parent request window, else pass type `default_valid_hours`, else the `default_pass_valid_hours` system setting — implemented as an ordered chain with the source recorded for traceability.
- **Acceptance Criteria:** unit tests cover each level of the chain and the exhaustion case; the derived window's source is recorded so a support question about "why is this pass valid until 8pm" is answerable; derivation never yields a null window.
- **Dependencies:** `T-08.3.1.1`

**T-08.3.1.3 — Building timezone configuration and rendering** · `P1` · `1 pt` · deps: `F-04.8`
- **Description:** A building timezone system setting, a server-side formatting helper, and consistent portal rendering of every window with an explicit zone label.
- **Acceptance Criteria:** windows render identically regardless of workstation timezone; zone label always present; DST spring-forward and autumn-back rendering covered by tests; setting change takes effect without redeploy.
- **Dependencies:** `F-04.8`

**T-08.3.1.4 — Window pickers and validation in the UI** · `P1` · `1 pt` · deps: `T-08.3.1.3, T-08.1.1.4`
- **Description:** Shared date/time range control used by both the tenant request form and the pre-registration form, showing the building timezone and the applicable duration ceiling.
- **Acceptance Criteria:** picker is keyboard-operable and screen-reader-labelled; ceiling breach flagged before submission; the control is shared, not duplicated per form; entered times are converted to instants using the building timezone, not the browser's.
- **Dependencies:** `T-08.3.1.3`

---

#### US-08.3.2 — Validate appointment windows against operating rules

**As a** System Administrator **I want** appointment windows checked against building operating hours and the holiday calendar **so that** VMS does not promise a visitor access at a time the building will not admit them.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-11 (SRS B1); reference data from `TDD-DERIVED` FR-CFG-08 (TDD §4.1) — ⚠️ the holiday calendar itself is `TDD-DERIVED` pending TODO-01 |
| **Dependencies** | US-08.3.1, F-04.7 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a window falling on a date in `vms.holiday_calendar` with `is_working = false`, **When** it is submitted, **Then** a warning is returned identifying the date and the holiday name.
- **AC-2 — Given** a window falling outside configured building operating hours, **When** it is submitted, **Then** a warning is returned stating the configured hours.
- **AC-3 — Given** a warning is returned, **When** an authorized user confirms explicitly, **Then** the record is accepted and the override is audited with the actor and the warning overridden — ⚠️ **NFR-AVL-01 (SRS B1)** notes operating hours are undefined in the source documents, so these are advisory warnings, not hard blocks, until the client states otherwise.
- **AC-4 (negative) — Given** the holiday calendar or operating-hours setting is unavailable, **When** validation runs, **Then** it degrades to permitting the window with a logged advisory rather than blocking pre-registration on a reference-data outage.
- **AC-5 (negative) — Given** a user without override authority, **When** they confirm past a warning, **Then** the API returns 403 and the record is not created.

**Security considerations:** overrides are permission-gated and audited so a pattern of after-hours overrides is detectable; fail-open on reference-data unavailability is a deliberate availability trade-off (NFR-AVL-01) and is recorded as such, given these are advisory warnings rather than access decisions — ACS remains the enforcement point (CON-01, CON-02).

**Development Tasks**

**T-08.3.2.1 — Operating-hours and holiday validation policy** · `P2` · `1 pt` · deps: `T-08.3.1.2, F-04.7`
- **Description:** A policy component returning structured advisory warnings for holiday and out-of-hours windows, reading the calendar and hours through the master-data port.
- **Acceptance Criteria:** unit tests cover holiday, out-of-hours, both together, and neither; an `is_working = true` holiday produces no warning; reference-data unavailability yields a logged advisory and an empty warning set, never an exception.
- **Dependencies:** `F-04.7`

**T-08.3.2.2 — Warning surfacing and audited override** · `P2` · `1 pt` · deps: `T-08.3.2.1`
- **Description:** Return warnings in the create/amend response and accept an explicit `acknowledgedWarnings` confirmation on resubmission, permission-gated and audited.
- **Acceptance Criteria:** unacknowledged warnings return 422 with the warning list; acknowledgement by an authorized user succeeds and writes an override audit entry naming the warnings; acknowledgement without the permission returns 403.
- **Dependencies:** `T-08.3.2.1`

**T-08.3.2.3 — Warning display and confirmation in the UI** · `P3` · `1 pt` · deps: `T-08.3.2.2, T-08.3.1.4`
- **Description:** Inline warning panel on the window pickers with an explicit acknowledgement control for authorized users.
- **Acceptance Criteria:** warnings are visually and semantically distinct from errors; acknowledgement is a deliberate action, never pre-checked; unauthorized users see the warning and an explanation that they cannot proceed.
- **Dependencies:** `T-08.3.2.2`

---

#### US-08.3.3 — Prevent conflicting appointments for the same visitor

**As a** Master Admin **I want** overlapping active appointments for the same visitor detected **so that** one person does not end up holding two live credentials at once.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-11 (SRS B1); backed downstream by the schema's `ux_credentials_active_per_visitor` partial unique index |
| **Dependencies** | US-08.3.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a visitor identified by the same normalised email already has an appointment whose window overlaps the one being entered, **When** the new record is submitted, **Then** the overlap is detected and reported with the conflicting appointment's window and host.
- **AC-2 — Given** an overlap is reported, **When** an authorized user confirms deliberately, **Then** the record is created and the decision is audited — the schema still guarantees only one **active credential** per visitor via `ux_credentials_active_per_visitor`, so the conflict is a workflow warning, not a data-integrity risk.
- **AC-3 — Given** two appointments that merely touch at their boundary (`to` equals the next `from`), **When** overlap is evaluated, **Then** they are treated as non-overlapping.
- **AC-4 (negative — double booking) — Given** two receptionists submit overlapping appointments for the same visitor simultaneously, **When** both transactions run, **Then** the detection is advisory and both may persist, but a later attempt to activate a second credential for that visitor fails against the partial unique index with a clear domain error rather than a raw constraint violation.
- **AC-5 (negative) — Given** the conflicting appointment lies outside the caller's authorized scope, **When** the conflict is reported, **Then** it discloses only that a conflict exists and its window — never the other tenant's host name, company or contact details.

**Security considerations:** AC-5 is the important one — a naive conflict message is a cross-tenant information leak (OWASP A01). The conflict response is deliberately minimised outside the caller's scope. The database partial unique index is the real integrity guarantee; the workflow check is convenience, and the code treats it that way (AC-4).

**Development Tasks**

**T-08.3.3.1 — Overlap detection query with scope-aware disclosure** · `P2` · `1 pt` · deps: `T-08.3.1.1, T-08.1.2.1`
- **Description:** Detection over active visitor appointments matched on normalised email, using half-open interval semantics, returning a full conflict record in scope and a minimised one out of scope.
- **Acceptance Criteria:** boundary-touching windows do not match; out-of-scope conflicts expose window only, proven by a cross-tenant integration test; indexed on normalised email plus appointment window.
- **Dependencies:** `T-08.1.2.1`

**T-08.3.3.2 — Conflict warning and audited confirmation** · `P2` · `1 pt` · deps: `T-08.3.3.1, T-08.3.2.2`
- **Description:** Fold conflict detection into the same warning/acknowledgement channel as US-08.3.2 rather than inventing a second mechanism.
- **Acceptance Criteria:** conflict returns 422 with the minimised detail; acknowledgement persists and audits; the acknowledgement mechanism is shared with operating-hours warnings.
- **Dependencies:** `T-08.3.3.1`

**T-08.3.3.3 — Domain-level handling of the active-credential unique index** · `P2` · `1 pt` · deps: `T-08.3.3.1`
- **Description:** Translate a `ux_credentials_active_per_visitor` violation into a specific domain exception and a 409 with an actionable message, rather than a 500.
- **Acceptance Criteria:** a concurrent double-activation integration test produces exactly one active credential and one 409 with a readable message; the raw constraint name and SQL state never reach the client response, and no PII is attached to the error log entry.
- **Dependencies:** `T-08.3.3.1`

---

### F-08.4 — Visitor record lifecycle & status tracking

> ⚠️ **`TDD-DERIVED` — backlog only.** Sourced from **TDD §4.2 / §4.4** and the schema's
> `vms.visitor_status` enum, referenced as `FR-ENT-10` — an identifier that **does not exist in the
> attached SRS**. Planned and estimated here so the phase has a realistic shape and cost, but **not
> authorized for implementation until TODO-01 is dispositioned.** *Note for the client conversation:
> `vms.visitors` already carries a `status` column with eight values plus `checked_in_at` /
> `checked_out_at` timestamps, so the schema assumes this lifecycle exists. Phase 2 needs only the
> pre-arrival portion (`pending`, `approved`, `cancelled`, `expired`, `no_show`); `checked_in`,
> `inside` and `checked_out` belong to Phase 3 EPIC-12 and are declared but not driven here.*

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` FR-ENT-10 (TDD §4.2) — pending TODO-01 |
| **Priority** | P1 *(if authorized)* · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-07.5, F-08.1 |

---

#### US-08.4.1 — Track visitor record status through the pre-arrival lifecycle

**As a** Master Admin **I want** each visitor record to carry an accurate lifecycle status **so that** reception can tell at a glance whether a visitor is expected, cancelled, or never turned up.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-ENT-10 (TDD §4.2) — **backlog only pending TODO-01** |
| **Dependencies** | US-07.5.1, US-08.1.1 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** the `vms.visitor_status` enum, **When** the visitor state machine is defined, **Then** the Phase 2 transitions are exactly: `pending → approved`, `pending → cancelled`, `approved → cancelled`, `approved → expired`, `approved → no_show`. Transitions into `checked_in`, `inside` and `checked_out` are declared but reserved for Phase 3 EPIC-12 and rejected if attempted in this phase.
- **AC-2 — Given** a request-level transition occurs, **When** it cascades, **Then** visitor status follows the F-07.5 cascade rules and no visitor is moved out of a terminal state.
- **AC-3 — Given** a visitor status changes, **When** it commits, **Then** a `VisitorStatusChanged` domain event is published carrying visitor id, previous and new status, and an audit entry is written.
- **AC-4 (negative) — Given** an illegal transition such as `cancelled → approved`, or a Phase 3 transition attempted in Phase 2, **When** it is attempted through any path, **Then** a domain exception is raised and nothing is mutated.
- **AC-5 (negative) — Given** a new value is added to `vms.visitor_status` without extending the transition table, **When** the build runs, **Then** the exhaustiveness test fails.

**Security considerations:** the visitor status gates credential eligibility in EPIC-09 — only an `approved` visitor may have a credential requested — so transition integrity is directly an access-control concern; enforced in the domain layer with no interface-layer bypass; status changes audited.

**Development Tasks**

**T-08.4.1.1 — Visitor status transition table and guard** · `P1` · `2 pts` · deps: `T-07.5.1.1`
- **Description:** Declarative visitor transition table in the `domain` module, with Phase 3 targets declared but gated by a feature flag so the Phase 2 boundary is explicit rather than implied by absence.
- **Acceptance Criteria:** parameterised test asserts every source×target cell; Phase 3 transitions rejected while the flag is off and permitted when on, so EPIC-12 has a seam rather than a rewrite; exhaustiveness test covers all eight enum values.
- **Dependencies:** `T-07.5.1.1`

**T-08.4.1.2 — Visitor status change events and audit** · `P1` · `2 pts` · deps: `T-08.4.1.1, T-07.5.2.1`
- **Description:** Register `VisitorStatusChanged` on the aggregate for every visitor transition and route it through the outbox with a matching audit entry.
- **Acceptance Criteria:** exactly one event and one audit row per transition; the event carries ids and statuses only, no PII; a cascade affecting five visitors produces five events, verified by an integration test.
- **Dependencies:** `T-08.4.1.1`

**T-08.4.1.3 — Status display and legend in reception views** · `P2` · `1 pt` · deps: `T-08.4.1.2, T-08.2.2.3`
- **Description:** Consistent status chip component and a legend across the pre-registration, central day view and tenant request views.
- **Acceptance Criteria:** one shared component, not per-page variants; status conveyed by text and shape as well as colour (WCAG 1.4.1); legend available on every view that shows status.
- **Dependencies:** `T-08.4.1.2`

---

#### US-08.4.2 — Automatically expire and flag no-show visitors

**As a** Master Admin **I want** visitors whose appointment window has passed without arrival to be marked automatically **so that** yesterday's expected arrivals do not clutter today's desk and stale records do not sit on live credentials.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-ENT-10 (TDD §4.2) — **backlog only pending TODO-01** |
| **Dependencies** | US-08.4.1, US-09.7.1 |
| **Blocked by** | TODO-01 (authorization to implement) · ⚠️ TODO-09 (ACS-side deactivation deliberately carved out — see AC-2) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor in `approved` status whose `appointment_to` has passed by a configured grace period without a check-in, **When** the scheduled job runs, **Then** the visitor moves to `no_show` and a `VisitorStatusChanged` event is published.
- **AC-2 — Given** a visitor moves to `expired` or `no_show`, **When** an active credential exists for them, **Then** a `CredentialExpiryDue` signal is raised for F-09.7 — ⚠️ **TODO-09**: whether VMS or ACS actually performs the deactivation is unresolved, so this phase raises the signal and moves VMS-side state only; the ACS deactivation call belongs to Phase 3 F-14.1.
- **AC-3 — Given** the job runs, **When** it processes a batch, **Then** it is idempotent — a re-run over the same window changes nothing further.
- **AC-4 (negative) — Given** the job runs on multiple application instances, **When** they overlap, **Then** a distributed lock in Redis ensures exactly one instance processes a given batch, preventing duplicate events and duplicate credential-expiry signals.
- **AC-5 (negative) — Given** the job fails partway through a batch, **When** it is retried, **Then** already-processed visitors are skipped and processing resumes without gaps or repeats.
- **AC-6 (negative) — Given** a visitor was checked in before the window elapsed, **When** the job runs, **Then** they are not marked `no_show`, since `checked_in` and `inside` are outside the job's source-state set.

**Security considerations:** duplicate credential-deactivation signals are the risk here, so the distributed lock and idempotency (AC-3, AC-4) matter; the job runs as a system principal with a narrow permission set, not as a borrowed user identity; its audit entries record the system actor explicitly so automated changes are distinguishable from human ones.

**Development Tasks**

**T-08.4.2.1 — Scheduled expiry / no-show job with distributed locking** · `P2` · `1 pt` · deps: `T-08.4.1.1`
- **Description:** Spring scheduled job selecting `approved` visitors past `appointment_to` plus grace, batched and paged, guarded by a Redis distributed lock with a lease shorter than the schedule interval.
- **Acceptance Criteria:** two concurrent instances process each visitor exactly once, proven by an integration test; re-running produces no further changes; a mid-batch failure resumes correctly; `checked_in`/`inside` visitors excluded by the source-state predicate; grace period configurable.
- **Dependencies:** `T-08.4.1.1`

**T-08.4.2.2 — Credential expiry signal with the TODO-09 carve-out** · `P2` · `1 pt` · deps: `T-08.4.2.1, T-09.7.1.1`
- **Description:** Publish a `CredentialExpiryDue` domain event when an expiring visitor holds an active credential, consumed by F-09.7 to move the credential state within VMS. The ACS-side deactivation call is explicitly **not** made in this phase.
- **Acceptance Criteria:** the event fires once per active credential; a named test and a code comment record that ACS deactivation is deferred to F-14.1 pending TODO-09; no ACS port method is invoked from this path — asserted with a strict mock.
- **Dependencies:** `T-09.7.1.1`

**T-08.4.2.3 — Job observability and system-actor audit** · `P3` · `1 pt` · deps: `T-08.4.2.1`
- **Description:** Metrics for processed, skipped and failed counts, a health indicator for last successful run, and audit entries attributed to a named system actor.
- **Acceptance Criteria:** metrics exposed on the F-01.7 endpoint; a run that has not completed within the expected interval degrades the health indicator; audit entries clearly distinguish system-initiated from user-initiated changes; no visitor PII in metric labels or job logs.
- **Dependencies:** `T-08.4.2.1`

---

## EPIC-09 — Pass & Credential Generation

| | |
|---|---|
| **Provenance** | `SRS` — FR-VMS-05 (SRS B1), FR-VMS-06 (SRS B1), FR-VMS-07 (SRS B1), FR-VMS-11 (SRS B1), FR-VMS-13 (SRS B1) |
| **Requirement IDs** | FR-VMS-05, FR-VMS-06, FR-VMS-07, FR-VMS-11, FR-VMS-13 (all SRS B1); NFR-PRF-01, NFR-REL-01, NFR-MNT-01, NFR-SEC-01 (SRS B1); CON-01, CON-02 (SRS B1) |
| **Priority** | **P0** — this is what makes Phase 2 demonstrable |
| **Features** | 7 (F-09.1 … F-09.7) |
| **Stories / Tasks / Points** | 16 stories · 50 tasks · **69 points** |
| **Bounded context** | Pass & Credential — aggregate root `Credential` |
| **Primary tables** | `vms.credentials`, `vms.pass_types`, `vms.visitors`, `vms.acs_requests`, `vms.notification_logs`, `vms.audit_logs` |
| **⚠️ Blocked by** | **TODO-02 (verification only — implementable against simulator per ADR-0002)** — applies to every ACS-touching story below |

**Goal.** Turn an approved visitor into a pass they can actually present at the gate. On approval, VMS
calls the ACS API to request creation of a time-bound credential (**FR-VMS-05**), specifying a validity
window (**FR-VMS-11**) and a restriction type of `time_bound` or `one_time` (**FR-VMS-13**); it stores
the credential reference ACS returns (**FR-VMS-06**) in `vms.credentials`; and it renders and delivers
that credential to the visitor by email, on-screen, or print (**FR-VMS-07**). Every ACS interaction goes
through the **ACS port (F-11.1)** and is exercised against the **ACS simulator (F-11.2)** — per ADR-0002
and per the architecture rule that **no ACS-specific type exists outside the ACS integration module**
(CON-01, CON-02, NFR-MNT-01). The epic is fully implementable today and fully unverifiable today; see
the ACS dependency note at the top of this document, because the distinction decides what may be
signed off at the end of the phase.

---

### F-09.1 — Credential creation request orchestration

VMS calls the ACS API to request creation of a credential for an approved visitor.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-05 (SRS B1) |
| **Priority** | P0 · **Stories** 3 · **Tasks** 11 · **Points** 18 |
| **Depends on** | F-07.4 (approval), F-08.3 (window), **F-11.1 (ACS port)**, **F-11.2 (ACS simulator)** |

---

#### US-09.1.1 — Request credential creation from ACS for an approved visitor

**As a** Master Admin **I want** VMS to request a credential from ACS once a visitor is approved **so that** the visitor has something to present at the barrier without anyone touching the access control system by hand.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 8 |
| **Provenance** | `SRS` FR-VMS-05 (SRS B1) |
| **Dependencies** | US-07.4.1, US-08.3.1, F-11.1, F-11.2, US-09.3.1, US-09.4.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor in `approved` status with a resolved validity window and pass type, **When** credential creation is requested, **Then** a `vms.credentials` row is created in `state = 'requested'` with `visitor_id`, `pass_type_id`, `credential_type`, `restriction`, `valid_from` and `valid_to`, **before** any outbound call is attempted.
- **AC-2 — Given** the credential row exists, **When** the ACS call is made, **Then** it goes through the `AcsPort` interface defined in the `application` module — the credential service holds no ACS-specific type, URL, DTO or error code, asserted by the architecture fitness test.
- **AC-3 — Given** ACS (simulated) accepts the request, **When** the response returns, **Then** the credential moves to `state = 'active'`, `issued_at` is set, and the returned reference is stored per F-09.2.
- **AC-4 — Given** the request is made, **When** it is recorded, **Then** a `vms.acs_requests` row exists with `op = 'create_credential'`, the credential id, and request/response payloads, and an audit entry with `action = 'credential.issue'` names the initiating user.
- **AC-5 (negative — ACS unavailable) — Given** ACS is unreachable or times out, **When** the call fails, **Then** the credential remains in `requested`, the `vms.acs_requests` row is left in a retryable state for F-11.3, the initiating receptionist is told the credential is not yet available, and **no visitor, request or approval data is lost** (NFR-REL-01 (SRS B1), FR-API-01 (SRS B1)).
- **AC-6 (negative — ACS rejects) — Given** ACS (simulated) returns a business rejection, **When** the response is processed, **Then** the credential moves to `state = 'failed'` with the reason recorded, no retry is attempted for a non-retryable rejection, and the operator sees an actionable message.
- **AC-7 (negative — duplicate) — Given** a visitor already holds an `active` credential, **When** creation is requested again, **Then** it is refused with 409 before any outbound call is made, so the `ux_credentials_active_per_visitor` index is never the first line of defence and no duplicate ACS credential is created.
- **AC-8 (negative — authorization) — Given** a caller without the `credential.issue` permission, **When** they invoke the endpoint, **Then** they receive 403, no credential row is created, no ACS call is attempted, and an authorization-denied audit entry is written.

**Security considerations:** `credential.issue` is the most sensitive permission in the phase — it provisions physical building access, so it is enforced at the boundary and every invocation is audited with the actor (AC-4, AC-8); the duplicate guard (AC-7) prevents both a cost and an access-sprawl problem; ACS request/response payloads written to `vms.acs_requests` and `vms.acs_api_log` are PII-redacted, carrying visitor id and window rather than name and contact details; credential creation must never be reachable for a visitor who is not `approved`.

**Development Tasks**

**T-09.1.1.1 — `Credential` aggregate and domain model** · `P0` · `2 pts` · deps: `—`
- **Description:** `Credential` aggregate root in the credential service `domain` module — `CredentialType` (`qr`, `rfid`), `RestrictionType` (`time_bound`, `one_time`), `CredentialState` (`requested`, `active`, `expired`, `revoked`, `cancelled`, `failed`), and a `ValidityWindow` value object, all mirroring the schema enums. No framework imports, no ACS types.
- **Acceptance Criteria:** compiles with zero framework and zero ACS imports; enum values match `vms.credential_type`, `vms.restriction_type`, `vms.credential_state` exactly, asserted by a mapping test; `valid_to > valid_from` invariant enforced at construction.
- **Dependencies:** `—`

**T-09.1.1.2 — Persistence mapping for `vms.credentials`** · `P0` · `1 pt` · deps: `T-09.1.1.1`
- **Description:** JPA mapping and repository for `vms.credentials`, including the PostgreSQL enum types and awareness of the `ux_credentials_active_per_visitor` partial unique index.
- **Acceptance Criteria:** Testcontainers round-trip of every state and type combination; the partial unique index violation surfaces as the domain exception from T-08.3.3.3, not a raw SQL error; `qr_payload` and `acs_credential_id` are nullable until ACS confirms, as the schema comment requires.
- **Dependencies:** `T-09.1.1.1`

**T-09.1.1.3 — `RequestCredentialCreation` use case against the ACS port** · `P0` · `2 pts` · deps: `T-09.1.1.2, F-11.1`
- **Description:** Application use case that validates visitor eligibility (`approved`, no active credential), persists the credential in `requested`, calls `AcsPort.createCredential(...)`, and applies the outcome. Written entirely against the port; the adapter binding is injected.
- **Acceptance Criteria:** unit tests with a stubbed port cover accept, business rejection, timeout and transport failure; the credential row is persisted before the call in every path, proven by a test that fails the call and asserts the row exists; the use case signature contains no ACS-specific type; eligibility check rejects a non-`approved` visitor.
- **Dependencies:** `F-11.1`

**T-09.1.1.4 — Contract tests against the ACS simulator** · `P0` · `2 pts` · deps: `T-09.1.1.3, F-11.2`
- **Description:** Contract test suite exercising credential creation against the F-11.2 simulator, covering success, business rejection, timeout, 5xx and malformed response, and a simulator scenario for a slow response near the NFR-PRF-01 budget.
- **Acceptance Criteria:** suite runs in CI on every commit per the testing strategy; each scenario asserts the resulting `vms.credentials.state` and `vms.acs_requests.status`; the suite is written so it can be re-pointed at the real endpoint when TODO-02 lands without rewriting assertions; results are recorded in the traceability matrix as **"done against simulator"**, never as verified.
- **Dependencies:** `F-11.2`

**T-09.1.1.5 — Credential issuance endpoint and audit** · `P0` · `1 pt` · deps: `T-09.1.1.3`
- **Description:** `POST /api/v1/visitors/{visitorId}/credential` guarded by `credential.issue`, writing the audit entry and the `vms.acs_requests` record, with PII-redacted payload persistence.
- **Acceptance Criteria:** 201 on success, 409 on an existing active credential, 422 on an ineligible visitor, 403 without the permission, 502/504 mapped from ACS transport failures with a retryable indication; a payload-inspection test asserts no visitor name, email or phone in `vms.acs_requests.request_payload`.
- **Dependencies:** `T-09.1.1.3`

---

#### US-09.1.2 — Trigger credential creation automatically on approval

**As a** Master Admin **I want** approval to start the credential request without a second manual step **so that** a visitor approved this morning has their pass before they arrive, and nobody has to remember to press a button.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-05 (SRS B1) — *"If appointed, VMS shall call the ACS API…"* |
| **Dependencies** | US-09.1.1, US-07.5.2 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** a `VisitorRequestApproved` event is consumed, **When** the handler runs, **Then** a credential creation request is initiated for each approved visitor on that request.
- **AC-2 — Given** automatic issuance is configurable, **When** the `credential.auto_issue_on_approval` system setting is false, **Then** no automatic request is made and issuance remains manual through US-09.1.1 — the trigger is a policy, not a hard-wired behaviour.
- **AC-3 — Given** the same `VisitorRequestApproved` event is delivered twice, **When** the handler processes the duplicate, **Then** the `event_id` idempotency check prevents a second credential and a second ACS call.
- **AC-4 (negative — ACS unavailable) — Given** ACS is unreachable when the event is handled, **When** the call fails, **Then** the credential stays in `requested` and is retried by F-11.3; the approval itself is never rolled back, because approval and issuance are separate aggregates with eventual consistency between them.
- **AC-5 (negative — partial group failure) — Given** a five-visitor request where issuance succeeds for three and fails for two, **When** processing completes, **Then** the three succeed independently, the two remain retryable, and the operator sees a per-visitor status rather than an all-or-nothing outcome.
- **AC-6 (negative — poison event) — Given** an event that fails handling repeatedly, **When** the retry ceiling is reached, **Then** it moves to a dead-letter topic with its `event_id` and correlation id, an operator alert is raised (F-11.4), and the consumer continues processing subsequent events rather than stalling the partition.

**Security considerations:** the event consumer runs as a system principal, not as the approving user's identity — the audit entry records both the system actor and the originating approver, so automated provisioning of building access remains attributable; idempotency (AC-3) prevents duplicate credential creation from a redelivered event; dead-lettering prevents a poison event silently halting all credential issuance, which would be an availability failure with security consequences.

**Development Tasks**

**T-09.1.2.1 — Idempotent `VisitorRequestApproved` consumer** · `P1` · `2 pts` · deps: `T-09.1.1.3, T-07.5.2.2`
- **Description:** Kafka consumer in the credential service with a processed-`event_id` table for idempotency, per-visitor fan-out, and per-visitor independent failure handling.
- **Acceptance Criteria:** replaying the same event creates no second credential and makes no second ACS call, proven by an integration test with a counting stub port; a five-visitor event with two induced failures yields three active and two retryable credentials; the consumer does not read the visitor service's tables — it uses the event payload and the visitor read port.
- **Dependencies:** `T-07.5.2.2`

**T-09.1.2.2 — Auto-issue policy setting** · `P2` · `1 pt` · deps: `F-04.8`
- **Description:** Add `credential.auto_issue_on_approval` to `vms.system_settings` via a forward-only migration, read through the settings port.
- **Acceptance Criteria:** setting false disables the automatic path while leaving the manual endpoint working; the effective value is logged at startup; a missing key defaults to the documented value rather than failing open to automatic issuance.
- **Dependencies:** `F-04.8`

**T-09.1.2.3 — Dead-letter handling and operator alerting for the consumer** · `P2` · `2 pts` · deps: `T-09.1.2.1, F-11.4`
- **Description:** Retry-with-backoff then dead-letter for the approval consumer, integrated with the F-11.4 operator alerting path.
- **Acceptance Criteria:** a persistently failing event dead-letters after the configured attempts with its `event_id` and correlation id preserved; the partition continues processing; an operator alert is raised; the dead-letter record carries no visitor PII.
- **Dependencies:** `F-11.4`

---

#### US-09.1.3 — Retry and surface failed credential requests

**As a** Master Admin **I want** failed credential requests visible and retryable **so that** an ACS outage becomes a short queue I can work through rather than a visitor standing at the desk with nothing.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-05 (SRS B1), FR-API-01 (SRS B1); `SRS-NFR` NFR-REL-01 (SRS B1) |
| **Dependencies** | US-09.1.1, F-11.3, F-11.4 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** credentials stuck in `requested` or moved to `failed`, **When** I open the credential operations view, **Then** I see them with the visitor, the window, the attempt count from `vms.acs_requests.attempt_count`, the last error and the next retry time.
- **AC-2 — Given** a retryable failure, **When** the F-11.3 retry runs, **Then** it retries with backoff up to the `acs.retry.max_attempts` system setting (seeded at 5) and moves to `dead_letter` beyond it.
- **AC-3 — Given** a dead-lettered request, **When** I trigger a manual retry, **Then** a new attempt is made, the action is audited with my user id, and the attempt count continues rather than resetting.
- **AC-4 — Given** a credential request fails, **When** the failure is surfaced, **Then** the receptionist who initiated it is notified of the failure per FR-API-01 (SRS B1) — the failure is pushed to the operator, not merely available if they go looking.
- **AC-5 (negative — retry storm) — Given** ACS is down and 200 credentials are queued, **When** retries run, **Then** backoff is jittered and concurrency-bounded so recovery does not produce a thundering herd against a just-recovered ACS.
- **AC-6 (negative — non-retryable) — Given** a business rejection such as an invalid validity window, **When** it is classified, **Then** it is **not** retried, because retrying a deterministic rejection wastes attempts and delays the operator learning the real problem.
- **AC-7 (negative — authorization) — Given** a user without `credential.issue`, **When** they attempt a manual retry, **Then** they receive 403 and the attempt is audited.

**Security considerations:** manual retry is a privileged action that provisions access, so it carries the same permission and audit treatment as initial issuance (AC-3, AC-7); error messages surfaced to operators are sanitised so a raw ACS error cannot leak endpoint or credential-mechanism detail into the UI or the logs; bounded retry concurrency is both an availability and a self-DoS control.

**Development Tasks**

**T-09.1.3.1 — Retryable vs non-retryable failure classification** · `P1` · `2 pts` · deps: `T-09.1.1.3, F-11.3`
- **Description:** Classify ACS port failures into retryable (transport, timeout, 5xx, rate limit) and non-retryable (business rejection, validation) inside the ACS integration module, exposing only a neutral classification through the port.
- **Acceptance Criteria:** the classification enum contains no ACS-specific vocabulary and lives behind the port; unit tests cover each class; a non-retryable failure never schedules `next_retry_at`; a new unmapped failure defaults to non-retryable so an unknown error cannot cause an infinite retry loop.
- **Dependencies:** `F-11.3`

**T-09.1.3.2 — Failed-credential operations view** · `P1` · `2 pts` · deps: `T-09.1.3.1`
- **Description:** `GET /api/v1/credentials/failed` and a Master Admin operations page listing stuck and failed credentials with attempt counts, last error and next retry, plus a manual retry action.
- **Acceptance Criteria:** 200 for `credential.issue` holders, 403 otherwise; last error is sanitised, containing no endpoint, token or raw payload; manual retry is audited; list is paginated and bounded; the page distinguishes retryable from terminal failures visually and in text.
- **Dependencies:** `T-09.1.3.1`

**T-09.1.3.3 — Operator failure notification** · `P2` · `1 pt` · deps: `T-09.1.3.1, F-16.1`
- **Description:** Notify the initiating receptionist on credential request failure, satisfying the FR-API-01 (SRS B1) notify-the-receptionist clause, using the pulled-forward F-16.1 notification service.
- **Acceptance Criteria:** the initiating user is notified on transition to `failed` or `dead_letter`; the notification names the visitor by id and display name only, with no contact detail; repeated failures for one credential are coalesced rather than sent per attempt.
- **Dependencies:** `F-16.1`

---

### F-09.2 — Credential reference storage

VMS receives and stores the credential reference ACS returns, for display and sharing with the visitor.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-06 (SRS B1) |
| **Priority** | P0 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-09.1, F-11.1 |

---

#### US-09.2.1 — Store the credential reference returned by ACS

**As a** Master Admin **I want** the credential reference ACS returns persisted against the visitor **so that** VMS can display and share the pass without asking ACS again every time someone looks at it.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-06 (SRS B1) |
| **Dependencies** | US-09.1.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** ACS (simulated) returns a credential reference, **When** the response is processed, **Then** `vms.credentials.acs_credential_id` is set, and where the credential is a QR the opaque payload is stored in `qr_payload`, exactly as the schema comments describe.
- **AC-2 — Given** a reference is stored, **When** the credential state changes to `active`, **Then** `issued_at` is set from the response and the write happens in one transaction with the state change.
- **AC-3 — Given** the stored reference, **When** it is read for display or delivery, **Then** it is read from `vms.credentials` and not re-fetched from ACS — VMS stores references and ACS-reported state, and is not the system of record for physical credential state.
- **AC-4 (negative — missing reference) — Given** an ACS response that reports success but carries no credential reference, **When** it is processed, **Then** the credential moves to `failed` with a distinct reason rather than to `active` with a null reference, because an active credential nobody can present is worse than a visible failure.
- **AC-5 (negative — oversize or malformed payload) — Given** a returned `qr_payload` exceeding the configured maximum length or failing basic structural validation, **When** it is processed, **Then** it is rejected, the credential moves to `failed`, and the raw payload is not written to logs.
- **AC-6 (negative — no PII in the reference path) — Given** the reference and payload are stored, **When** logs and Kafka events for this path are inspected, **Then** neither `qr_payload` nor `acs_credential_id` appears — they are access-bearing secrets, and the credential id is used for correlation instead.

**Security considerations:** `qr_payload` is an access-bearing value — anyone holding it can present it at a barrier — so it is treated as a secret: never logged, never emitted in a domain event, never included in a list projection, and only returned by an authorized, audited read (F-09.5). ⚠️ **NFR-CMP-01 / TODO-06** — whether it additionally requires encryption at rest beyond F-05.3's storage-level encryption depends on the data-protection regime, which is unresolved; the column is isolated so a later column-level encryption change is contained.

**Development Tasks**

**T-09.2.1.1 — Reference persistence with response validation** · `P0` · `1 pt` · deps: `T-09.1.1.3`
- **Description:** Apply the ACS response to the credential aggregate: validate that a reference is present and structurally acceptable, set `acs_credential_id`, `qr_payload`, `issued_at`, and transition state — all in one transaction.
- **Acceptance Criteria:** a success response with a missing reference yields `failed`, not `active`; an oversize payload yields `failed`; the state change and reference write are atomic, proven by a mid-write failure test; payload length ceiling is configurable.
- **Dependencies:** `T-09.1.1.3`

**T-09.2.1.2 — Secret-handling rules for `qr_payload` and `acs_credential_id`** · `P0` · `2 pts` · deps: `T-09.2.1.1`
- **Description:** Mark both fields as sensitive across serialization, logging and event publication: excluded from `toString()`, from default API projections, from Kafka payloads, and redacted in `vms.acs_requests` / `vms.acs_api_log`.
- **Acceptance Criteria:** an automated test scans serialized events, log output and audit rows for the payload value and fails if found; the fields are absent from every list projection; a developer adding them to an event breaks the no-secrets test.
- **Dependencies:** `T-09.2.1.1`

**T-09.2.1.3 — Credential read model for display and delivery** · `P1` · `2 pts` · deps: `T-09.2.1.2`
- **Description:** An authorized read path returning the credential with its payload for the display and delivery use cases only, separate from the general credential projection.
- **Acceptance Criteria:** the payload-bearing read requires an explicit permission and writes an audit entry; the general credential list projection never includes it; an integration test asserts the two projections differ in exactly the sensitive fields.
- **Dependencies:** `T-09.2.1.2`

---

#### US-09.2.2 — View a visitor's credential status and reference

**As a** Master Admin **I want** to see a visitor's credential, its state and its window **so that** I can answer "does this person have a working pass?" at the desk.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-06 (SRS B1) |
| **Dependencies** | US-09.2.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor with a credential, **When** I open their record, **Then** I see the credential type, restriction, state, validity window and `issued_at`, with the ACS reference shown in a masked form.
- **AC-2 — Given** a visitor has had several credentials over time, **When** I view their record, **Then** all are listed with their states, so a revoked-then-reissued history is visible rather than only the current one.
- **AC-3 — Given** I need the full reference for troubleshooting, **When** I reveal it explicitly and hold the required permission, **Then** it is returned and the reveal is audited as a distinct action.
- **AC-4 (negative) — Given** I lack the credential-read permission, **When** I request the endpoint, **Then** I receive 403 and no credential data, masked or otherwise, is returned.
- **AC-5 (negative) — Given** a visitor outside my authorized scope, **When** I request their credential by visitor id, **Then** I receive 404 identical in shape to a genuinely missing visitor.
- **AC-6 (negative) — Given** the credential state in VMS may lag ACS reality, **When** the state is displayed, **Then** it is labelled as VMS's last known state with its timestamp — ⚠️ **TODO-09** leaves it unresolved whether ACS expires credentials autonomously, so VMS must not present its own state as authoritative. Live status query is Phase 3 F-14.2 (FR-VMS-14).

**Security considerations:** masking by default with an explicitly audited reveal (AC-1, AC-3) limits casual exposure of an access-bearing value; scope enforcement and 404-not-403 on the visitor path; AC-6 is an honesty control — presenting a possibly-stale state as authoritative would let a receptionist wave through a visitor whose credential ACS has already revoked.

**Development Tasks**

**T-09.2.2.1 — Credential history read model with masking** · `P1` · `1 pt` · deps: `T-09.2.1.3`
- **Description:** Read projection listing all credentials for a visitor with state, window and a masked reference, plus a `lastKnownAt` timestamp on the state.
- **Acceptance Criteria:** masking retains only a short suffix; ordering is newest first; the projection includes `lastKnownAt`; scope predicate applied; no `qr_payload` in this projection.
- **Dependencies:** `T-09.2.1.3`

**T-09.2.2.2 — Credential view endpoints with audited reveal** · `P1` · `1 pt` · deps: `T-09.2.2.1`
- **Description:** `GET /api/v1/visitors/{visitorId}/credentials` and a separate `:reveal` endpoint for the unmasked reference, each with its own permission and audit action.
- **Acceptance Criteria:** 200 masked for the standard read; the reveal endpoint requires the elevated permission, returns the full reference, and writes a `credential.reveal` audit entry; 403 and 404 behaviours as specified; reveal is rate limited per user.
- **Dependencies:** `T-09.2.2.1`

**T-09.2.2.3 — Credential panel on the visitor detail view** · `P1` · `1 pt` · deps: `T-09.2.2.2`
- **Description:** Credential section on the visitor detail page showing history, state chips, window, and a reveal control for authorized users, with an explicit "last known at" label.
- **Acceptance Criteria:** state conveyed by text as well as colour; the staleness label is always present, never conditional; reveal requires a deliberate action and shows a warning that the reveal is audited; masked value is what is copied by default.
- **Dependencies:** `T-09.2.2.2`

---

### F-09.3 — Validity window specification

VMS specifies a validity window (date and time slot) when requesting credential creation from ACS.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-11 (SRS B1) |
| **Priority** | P0 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-08.3, F-09.1 |

---

#### US-09.3.1 — Derive and send the credential validity window

**As a** Master Admin **I want** the credential request to carry an explicit validity window **so that** the pass works during the appointment and stops working afterwards.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-11 (SRS B1) |
| **Dependencies** | US-08.3.1, US-09.1.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor with an appointment window, **When** a credential is requested, **Then** `vms.credentials.valid_from` / `valid_to` are set from the F-08.3 derivation chain and included in the ACS port request as absolute instants in a documented, unambiguous format.
- **AC-2 — Given** the pass type defines `default_valid_hours`, **When** no explicit window exists, **Then** the window is derived from it, and the derivation source is recorded on the credential for support traceability.
- **AC-3 — Given** a window is sent, **When** the credential is created, **Then** the window persisted in VMS is exactly the window sent to ACS, with a test asserting no timezone or precision drift between the two.
- **AC-4 (negative — invalid window) — Given** a window where `valid_to` is not after `valid_from`, **When** creation is attempted, **Then** it is refused before the outbound call, and the database `CHECK (valid_to > valid_from)` is never the mechanism that catches it.
- **AC-5 (negative — past window) — Given** a window that has already fully elapsed, **When** creation is attempted, **Then** it is refused with 422 — issuing a dead-on-arrival credential consumes an ACS operation and confuses the operator.
- **AC-6 (negative — ceiling) — Given** a window exceeding the maximum credential duration, **When** creation is attempted, **Then** it is refused with 422 naming the limit, bounding how long any single VMS-provisioned credential can open a door.

**Security considerations:** the validity window is the primary temporal access control on a VMS-provisioned credential; the duration ceiling (AC-6) and the past-window guard (AC-5) are enforced server-side before the outbound call, so a manipulated client cannot request an over-long or backdated credential; AC-3's no-drift assertion matters because a timezone bug here silently extends physical access.

**Development Tasks**

**T-09.3.1.1 — Validity window derivation into the credential aggregate** · `P0` · `2 pts` · deps: `T-09.1.1.1, T-08.3.1.2`
- **Description:** Apply the F-08.3 derivation chain when constructing a `Credential`, recording the derivation source, and enforce the past-window and duration-ceiling rules in the domain.
- **Acceptance Criteria:** unit tests cover each derivation level, an elapsed window, a zero-length window and an over-ceiling window; derivation source persisted; no path constructs a credential with a null window.
- **Dependencies:** `T-08.3.1.2`

**T-09.3.1.2 — Window representation in the ACS port contract** · `P0` · `2 pts` · deps: `T-09.1.1.3, F-11.1`
- **Description:** Define the validity window on the port request as absolute instants with a documented format, and a round-trip test asserting the persisted window equals the transmitted window.
- **Acceptance Criteria:** no local-date-time anywhere in the port contract; round-trip test passes across a DST boundary and across a non-UTC building timezone; the port contract documents the format so the TODO-02 wire adapter has an unambiguous target to map onto.
- **Dependencies:** `F-11.1`

**T-09.3.1.3 — Window display on the pass and in operations views** · `P1` · `1 pt` · deps: `T-09.3.1.2, T-08.3.1.3`
- **Description:** Render the credential validity window with an explicit timezone label wherever a pass or credential is shown.
- **Acceptance Criteria:** zone label always shown; identical rendering across the pass view, delivery email and operations views via the shared formatter; DST-spanning windows render correctly.
- **Dependencies:** `T-09.3.1.2`

---

#### US-09.3.2 — Adjust a credential's validity window before issuance

**As a** Master Admin **I want** to adjust the validity window before the credential is issued **so that** a meeting that has moved by an hour does not need the whole request cancelled and redone.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-11 (SRS B1) |
| **Dependencies** | US-09.3.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** a credential in `requested` state, **When** I adjust the window within the permitted ceiling, **Then** the credential's window updates and the audit entry records the old and new values with my user id.
- **AC-2 — Given** the window is adjusted before the ACS call succeeds, **When** the call is subsequently made or retried, **Then** it carries the adjusted window.
- **AC-3 (negative — already active) — Given** a credential in `active` state, **When** I attempt to adjust its window, **Then** the API returns 409 — amending a live credential's window means an ACS update operation, which is not in scope for this phase and is not in the SRS.
- **AC-4 (negative — race with issuance) — Given** the ACS response arrives while I am adjusting the window, **When** both transactions run, **Then** optimistic locking ensures the adjustment fails with 409 rather than silently overwriting the issued window, so VMS's stored window can never disagree with what ACS was told.
- **AC-5 (negative — authorization) — Given** a user without `credential.issue`, **When** they attempt an adjustment, **Then** they receive 403 and the attempt is audited.

**Security considerations:** AC-4 is the sharp edge — a lost update here would leave VMS displaying a window ACS never received, which is exactly the class of drift that makes an access system untrustworthy; adjustment is permission-gated and audited; the ceiling from US-09.3.1 applies equally to adjustments so the limit cannot be escaped by adjusting after creation.

**Development Tasks**

**T-09.3.2.1 — Pre-issuance window adjustment with optimistic locking** · `P2` · `1 pt` · deps: `T-09.3.1.1, T-09.1.1.2`
- **Description:** `adjustWindow()` on the credential aggregate, legal only from `requested`, with a `@Version` column on `vms.credentials` added by a forward-only migration.
- **Acceptance Criteria:** adjustment from `active`, `failed`, `revoked`, `cancelled` or `expired` raises a domain exception; a concurrent adjust-vs-response-apply test yields exactly one winner and no window/ACS disagreement; the duration ceiling is re-checked on adjustment.
- **Dependencies:** `T-09.1.1.2`

**T-09.3.2.2 — Window adjustment endpoint** · `P2` · `1 pt` · deps: `T-09.3.2.1`
- **Description:** `PATCH /api/v1/credentials/{id}/validity` guarded by `credential.issue`, audited, mapping domain failures to 409 and 422.
- **Acceptance Criteria:** 200 from `requested`; 409 from any other state or on version conflict; 422 on ceiling breach or elapsed window; 403 without permission; audit records old and new values.
- **Dependencies:** `T-09.3.2.1`

**T-09.3.2.3 — Adjustment control in the credential operations view** · `P3` · `1 pt` · deps: `T-09.3.2.2, T-09.1.3.2`
- **Description:** Window adjustment control on the credential operations page, visible only for `requested` credentials.
- **Acceptance Criteria:** control absent for every other state; a 409 refreshes and explains that the credential was issued concurrently; the ceiling is shown in the control rather than only discovered on rejection.
- **Dependencies:** `T-09.3.2.2`

---

### F-09.4 — Restriction type selection — time-bound / one-time

VMS supports requesting either time-bound or one-time-use restriction types, per what the ACS API allows.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-13 (SRS B1) |
| **Priority** | P1 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-09.1, F-04.6 |

---

#### US-09.4.1 — Select time-bound or one-time restriction on a credential

**As a** Master Admin **I want** to choose whether a pass works for a period or for a single entry **so that** a one-off delivery courier is not handed an all-day credential.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-13 (SRS B1) |
| **Dependencies** | US-09.1.1, F-04.6 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** the `vms.restriction_type` enum (`time_bound`, `one_time`), **When** a credential is created, **Then** `vms.credentials.restriction` is set from the explicit selection or from the pass type's `default_restriction`, and the value is included in the ACS port request.
- **AC-2 — Given** the seeded pass types, **When** no explicit restriction is chosen, **Then** `DAY_QR` and `CARD_DAY` default to `time_bound` and `ONE_TIME` defaults to `one_time`, matching the schema seed data.
- **AC-3 — Given** a restriction is chosen, **When** the pass is rendered and delivered, **Then** the restriction is stated in plain language to the visitor, so a one-time pass holder is not surprised at the second barrier.
- **AC-4 (negative — unsupported by ACS) — Given** ACS (simulated) reports that a requested restriction type is unsupported, **When** the response is processed, **Then** the credential moves to `failed` with a specific reason and VMS does **not** silently substitute the other restriction type — FR-VMS-13 (SRS B1) says *"per what the ACS API allows"*, and quietly downgrading `one_time` to `time_bound` would widen access beyond what was authorized.
- **AC-5 (negative — unknown capability) — ⚠️ TODO-02 — Given** the real ACS restriction-type vocabulary is unknown, **When** the port is defined, **Then** it exposes VMS's two schema values and the mapping to ACS vocabulary lives entirely in the adapter, so the TODO-02 answer changes one mapping class and nothing else (NFR-MNT-01 (SRS B1)).
- **AC-6 (negative — authorization) — Given** a user without `credential.issue`, **When** they attempt to select a restriction, **Then** they receive 403.

**Security considerations:** AC-4 is a genuine security rule, not a nicety — a silent fallback from `one_time` to `time_bound` grants broader physical access than the operator selected, and it would do so invisibly. The failure is deliberately loud. AC-5 keeps ACS vocabulary out of the core per CON-01/CON-02.

**Development Tasks**

**T-09.4.1.1 — Restriction selection and pass-type defaulting** · `P1` · `2 pts` · deps: `T-09.1.1.1, F-04.6`
- **Description:** Restriction resolution on the credential aggregate — explicit selection, else the pass type's `default_restriction`, with no implicit fallback beyond that.
- **Acceptance Criteria:** unit tests cover explicit selection, each seeded pass type default, and the no-resolution case which fails rather than guessing; the resolved restriction is persisted; selection is validated against the enum.
- **Dependencies:** `F-04.6`

**T-09.4.1.2 — Restriction in the ACS port contract with adapter-side mapping** · `P1` · `2 pts` · deps: `T-09.4.1.1, F-11.1`
- **Description:** Represent restriction on the port using VMS's own vocabulary, with a mapping class inside the ACS integration module and an explicit unsupported-restriction failure classification.
- **Acceptance Criteria:** the mapping class is the only place ACS restriction vocabulary appears, asserted by the architecture fitness test; an unsupported-restriction simulator scenario yields `failed` with the specific reason and never a substituted restriction — asserted directly by a test named for the rule.
- **Dependencies:** `F-11.1`

**T-09.4.1.3 — Restriction selection in the issuance UI** · `P2` · `1 pt` · deps: `T-09.4.1.2, T-09.1.3.2`
- **Description:** Restriction control on the issuance flow, defaulted from the selected pass type with the default's origin shown.
- **Acceptance Criteria:** default reflects the chosen pass type and updates when the pass type changes; the control explains what each restriction means in plain language; unsupported-restriction failures surface a specific, actionable message rather than a generic error.
- **Dependencies:** `T-09.4.1.2`

---

#### US-09.4.2 — Choose the credential type for a pass

**As a** Master Admin **I want** to issue either a QR pass or an RFID card **so that** the visitor gets the credential form the building and the visitor can actually use.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-05 (SRS B1) — *"QR code or RFID card"*; `SRS` FR-VMS-13 (SRS B1) |
| **Dependencies** | US-09.4.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** the `vms.credential_type` enum (`qr`, `rfid`), **When** a credential is created, **Then** `credential_type` is set from the explicit selection or the pass type's `default_credential`, and is included in the ACS port request.
- **AC-2 — Given** `credential_type = 'qr'`, **When** ACS returns a payload, **Then** `qr_payload` is populated and the pass renders as a QR per F-09.5.
- **AC-3 — Given** `credential_type = 'rfid'`, **When** the credential is issued, **Then** no `qr_payload` is expected, the pass presentation reflects a physical card rather than a scannable image, and the card issuance record itself is Phase 3 F-15.1 (FR-CRD-01 (SRS B1)) — explicitly out of scope here.
- **AC-4 (negative — mismatch) — Given** `credential_type = 'rfid'` but ACS returns a QR payload, or `qr` with no payload, **When** the response is processed, **Then** the mismatch is treated as a failure rather than persisted, because a credential whose stored form contradicts its type will fail confusingly at the barrier.
- **AC-5 (negative — authorization) — Given** a user without `credential.issue`, **When** they attempt to select a credential type, **Then** they receive 403.

**Security considerations:** type/payload consistency (AC-4) prevents a credential record that cannot be honoured; the RFID path deliberately stops short of card accountability (FR-CRD-01, Phase 3) rather than half-implementing it here, which keeps the Phase 2 scope claim honest.

**Development Tasks**

**T-09.4.2.1 — Credential type selection with pass-type defaulting** · `P2` · `1 pt` · deps: `T-09.4.1.1`
- **Description:** Type resolution mirroring restriction resolution, from explicit selection or the pass type's `default_credential`.
- **Acceptance Criteria:** unit tests cover explicit and defaulted resolution for all three seeded pass types; unresolved type fails rather than defaulting to `qr`; value validated against the enum.
- **Dependencies:** `T-09.4.1.1`

**T-09.4.2.2 — Type/payload consistency validation** · `P2` · `1 pt` · deps: `T-09.4.2.1, T-09.2.1.1`
- **Description:** Validate the ACS response against the requested credential type before persisting, failing the credential on a mismatch.
- **Acceptance Criteria:** `qr` with no payload fails; `rfid` with a QR payload fails; both produce distinct, specific reasons; simulator scenarios cover each; no partial persistence on mismatch.
- **Dependencies:** `T-09.4.2.1`

**T-09.4.2.3 — Type selection and type-aware pass presentation** · `P2` · `1 pt` · deps: `T-09.4.2.2`
- **Description:** Type control in the issuance flow and type-aware pass presentation that shows a QR for `qr` and card instructions for `rfid`.
- **Acceptance Criteria:** presentation branches correctly by type; the RFID view makes clear the physical card is collected at reception and does not imply a scannable image exists; control defaults from the pass type.
- **Dependencies:** `T-09.4.2.2`

---

### F-09.5 — QR rendering & pass presentation

The credential becomes a pass a visitor can look at, hold up, and have scanned.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-07 (SRS B1) |
| **Priority** | P0 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-09.2, F-06.1 (branding), F-06.4 (accessibility) |

---

#### US-09.5.1 — Render the credential as a scannable QR pass

**As a** Visitor **I want** my pass shown as a scannable QR with my visit details **so that** I can present it at the barrier without needing anything printed.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-07 (SRS B1) |
| **Dependencies** | US-09.2.1, F-06.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** an `active` credential with `credential_type = 'qr'` and a stored `qr_payload`, **When** the pass is rendered, **Then** a QR image encoding exactly the stored payload is produced, at an error-correction level and module size sufficient to scan reliably from a phone screen at barrier distance.
- **AC-2 — Given** the pass is rendered, **When** it is displayed, **Then** it shows the visitor name, host, building, validity window with its timezone, and the restriction type in plain language, using the client's branding per FR-ADM-03 (SRS B1) / CON-03 (SRS B1).
- **AC-3 — Given** the QR is generated, **When** it is produced, **Then** generation happens **server-side** and the raw payload is never sent to the browser as a separate value — the client receives the image, not the secret.
- **AC-4 (negative — no payload) — Given** a credential in `requested`, `failed`, `expired`, `revoked` or `cancelled` state, **When** the pass is requested, **Then** no QR is rendered and a state-appropriate message is shown instead of a pass — a rendered QR must never imply a working credential.
- **AC-5 (negative — unauthorized) — Given** an unauthenticated or unauthorized caller, **When** they request a pass by credential or visitor id, **Then** they receive 404 with no distinction between missing and forbidden, and the pass endpoint is not guessable by id enumeration.
- **AC-6 (negative — caching) — Given** the pass image is served, **When** the response headers are inspected, **Then** it is marked no-store and private, so an access-bearing image is not cached by an intermediary or left in a shared browser cache at a reception PC.

**Security considerations:** the rendered QR is a physical access token in image form. Server-side generation (AC-3) keeps the payload out of client state; no-store caching (AC-6) matters specifically because these pages are viewed on shared reception workstations; state-gated rendering (AC-4) prevents a screenshot of a revoked pass being indistinguishable from a live one; pass access is audited and rate limited.

**Development Tasks**

**T-09.5.1.1 — Server-side QR generation service** · `P0` · `1 pt` · deps: `T-09.2.1.3`
- **Description:** Infrastructure component rendering `qr_payload` to a PNG/SVG at a configured error-correction level and size, with the payload never leaving the server except as an encoded image.
- **Acceptance Criteria:** generated codes decode back to exactly the stored payload in an automated round-trip test; error-correction level and size configurable; generation is bounded in time and memory; the payload appears in no log line or metric label.
- **Dependencies:** `T-09.2.1.3`

**T-09.5.1.2 — Pass rendering endpoint with state gating and cache control** · `P0` · `2 pts` · deps: `T-09.5.1.1`
- **Description:** `GET /api/v1/credentials/{id}/pass` returning the branded pass, gated on `active` state, authorized, audited, rate limited, and served with `Cache-Control: no-store, private`.
- **Acceptance Criteria:** non-`active` states return a state message rather than an image; 404 for unauthorized and missing alike; no-store headers asserted by test; access audited as `credential.pass_view`; rate limited per user and per credential.
- **Dependencies:** `T-09.5.1.1`

**T-09.5.1.3 — Branded pass view (Next.js)** · `P0` · `2 pts` · deps: `T-09.5.1.2, F-06.1`
- **Description:** Pass page rendering the QR with visitor, host, building, window, timezone and restriction, using the F-06.1 design tokens and client branding.
- **Acceptance Criteria:** branding applied from tokens, not hard-coded; the QR image carries a meaningful `alt` describing the pass and its validity, since the image itself is not perceivable to a screen-reader user; contrast and text size meet WCAG 2.1 AA; renders correctly at phone width; restriction stated in plain language.
- **Dependencies:** `T-09.5.1.2`

---

#### US-09.5.2 — Present the pass on the reception screen

**As a** Master Admin **I want** to show a visitor their pass on screen at the desk **so that** a visitor without email access still walks away with a usable credential.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-07 (SRS B1) — *"on-screen image (reception display)"* |
| **Dependencies** | US-09.5.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) · ⚠️ TODO-17 (the dedicated visitor-facing "slave" display of FR-VMS-15 is Phase 3 F-12.4 and is **not** this story) |

**Acceptance Criteria**
- **AC-1 — Given** an `active` credential, **When** I select on-screen presentation at the desk, **Then** the pass renders full-screen at a size a visitor can photograph from across the counter.
- **AC-2 — Given** the pass is on screen, **When** I dismiss it or the inactivity timer elapses, **Then** it is cleared automatically, so the next visitor at the desk does not see the previous one's pass and details.
- **AC-3 — Given** presentation mode, **When** it is active, **Then** only pass-relevant fields are shown — no navigation, no other visitors, no operational data.
- **AC-4 (negative — abandonment) — Given** the operator walks away with a pass displayed, **When** the inactivity timeout expires, **Then** the screen clears without any interaction, because an unattended reception screen showing a live QR is an unattended credential.
- **AC-5 (negative — state change) — Given** the credential is revoked or expires while displayed, **When** the display refreshes, **Then** it stops showing the QR and states the credential is no longer valid.
- **AC-6 (negative — screenshotting scope) — Given** the pass is presented, **When** the page is inspected, **Then** it contains no other visitor's data and no API tokens beyond the session, limiting what a photograph of the screen can capture.

**Security considerations:** this story is mostly a physical-security control expressed in software — auto-clear on inactivity (AC-2, AC-4) and minimised on-screen content (AC-3, AC-6) address a shared, publicly-visible reception screen. ⚠️ **TODO-17** — the dedicated visitor-facing display of FR-VMS-15 (SRS B1) is under-specified and belongs to Phase 3 F-12.4; this story deliberately covers only the receptionist's own screen, and must not be presented to the client as delivering FR-VMS-15.

**Development Tasks**

**T-09.5.2.1 — Presentation mode with auto-clear** · `P1` · `1 pt` · deps: `T-09.5.1.3`
- **Description:** Full-screen presentation route with a configurable inactivity timer, automatic clearing, and a minimised chrome-free layout.
- **Acceptance Criteria:** timer clears the display without interaction, verified with fake timers; navigation and operational data are absent from the DOM in this mode, not merely hidden by CSS; exiting returns to the operator view with session intact.
- **Dependencies:** `T-09.5.1.3`

**T-09.5.2.2 — Live state check during presentation** · `P2` · `1 pt` · deps: `T-09.5.2.1, T-09.7.1.2`
- **Description:** Periodic revalidation of credential state while presented, replacing the QR with an invalid-credential message if state changes.
- **Acceptance Criteria:** a revocation during presentation removes the QR within the poll interval; the revalidation call is authorized and rate limited; a failed check shows a stale indicator rather than assuming validity.
- **Dependencies:** `T-09.7.1.2`

**T-09.5.2.3 — Presentation entry point at the desk** · `P2` · `1 pt` · deps: `T-09.5.2.1`
- **Description:** A present-on-screen action on the visitor and credential views, available only for `active` credentials.
- **Acceptance Criteria:** action absent for non-`active` states; entering presentation is audited as `credential.pass_present`; the control is keyboard-reachable and the mode is exitable by Escape.
- **Dependencies:** `T-09.5.2.1`

---

### F-09.6 — Pass delivery — email, on-screen, print

The credential is shareable with the visitor by email, on-screen image, or printed copy.

| | |
|---|---|
| **Provenance** | `SRS` FR-VMS-07 (SRS B1) |
| **Priority** | P1 · **Stories** 3 · **Tasks** 9 · **Points** 11 |
| **Depends on** | F-09.5, **F-16.1 / F-16.2 (notification service and email channel, pulled forward into Phase 2)** |

---

#### US-09.6.1 — Deliver the pass to the visitor by email

**As a** Visitor **I want** my pass emailed to me **so that** I arrive with it already on my phone instead of queuing at reception to be issued one.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-07 (SRS B1); delivery mechanics via `SRS` FR-NOT-01 (SRS B1) — the pulled-forward F-16.1/F-16.2 |
| **Dependencies** | US-09.5.1, F-16.1, F-16.2 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** an `active` credential and a visitor with an email address, **When** delivery is triggered, **Then** an email is dispatched containing the pass with the QR image and the visit details, and a `vms.notification_logs` row is written with `channel = 'email'`, `type = 'confirmation'` and the delivery status.
- **AC-2 — Given** the email is sent, **When** the delivery outcome is known, **Then** `vms.notification_logs.status` is updated across `queued` → `sent` → `delivered` or `failed`, and `sent_at` is recorded.
- **AC-3 — Given** the email body, **When** it is composed, **Then** it embeds the QR as an inline image rather than linking to an authenticated URL the visitor cannot open, and it carries no link that grants access without authentication.
- **AC-4 (negative — no email address) — Given** a visitor with no email, **When** delivery is triggered, **Then** it is skipped with a clear reason recorded, the operator is told to use on-screen or print instead, and no partial notification row is left in `queued` forever.
- **AC-5 (negative — bounce or send failure) — Given** the mail transport fails or the address bounces, **When** the outcome is processed, **Then** the notification is marked `failed` with the reason, retried within a bounded policy, and the initiating operator is informed — the visitor's credential itself is unaffected.
- **AC-6 (negative — wrong recipient) — Given** delivery, **When** the recipient is resolved, **Then** it is read from the persisted `vms.visitors.email` for that visitor id and never from a caller-supplied address, so the endpoint cannot be used to send someone else's pass to an arbitrary mailbox.
- **AC-7 (negative — content) — Given** the email is composed, **When** it is inspected, **Then** it contains no other visitor's data, no internal ids beyond what the visitor needs, and no ACS reference in text form.

**Security considerations:** AC-6 is the critical control — a caller-supplied recipient turns pass delivery into an open credential-exfiltration endpoint. The recipient is always derived server-side from the visitor record. Email carries an access-bearing QR, so the body is minimised (AC-7), the payload is never included as text, and dispatch is permission-gated and audited. ⚠️ **TODO-10** — channel selection (email and/or WhatsApp) is unresolved; this phase implements email only, matching the schema's `notification.whatsapp.enabled = false` default. ⚠️ **TODO-12** — no retention rule exists for the notification log's stored recipient address.

**Development Tasks**

**T-09.6.1.1 — Pass delivery use case with server-derived recipient** · `P1` · `1 pt` · deps: `T-09.5.1.1, F-16.1`
- **Description:** Use case resolving the recipient from `vms.visitors.email` by visitor id, composing the pass content, dispatching through the notification port, and writing `vms.notification_logs`.
- **Acceptance Criteria:** the command DTO has no recipient field at all, so caller-supplied addresses are structurally impossible; a missing address short-circuits with a recorded reason and no queued row; unit tests cover dispatch, missing address and transport failure.
- **Dependencies:** `F-16.1`

**T-09.6.1.2 — Branded pass email template with inline QR** · `P1` · `2 pts` · deps: `T-09.6.1.1, F-16.2, F-06.1`
- **Description:** Email template carrying the QR as an inline attachment, visit details, validity window with timezone, and restriction in plain language, branded per F-06.1.
- **Acceptance Criteria:** renders acceptably in common mail clients including plain-text fallback; QR inline rather than remotely loaded, so it survives image blocking; no authenticated links; template content is length-bounded and escaped; no ACS reference in text.
- **Dependencies:** `F-16.2`

**T-09.6.1.3 — Delivery status tracking and operator feedback** · `P2` · `2 pts` · deps: `T-09.6.1.2`
- **Description:** Update `vms.notification_logs.status` from transport callbacks, apply a bounded retry policy, and surface delivery state on the credential view.
- **Acceptance Criteria:** status transitions recorded with timestamps; retries bounded and not applied to a hard bounce; delivery state visible to the operator; a failed delivery never alters the credential's own state; recipient address not logged outside `vms.notification_logs`.
- **Dependencies:** `T-09.6.1.2`

---

#### US-09.6.2 — Print a physical pass

**As a** Master Admin **I want** to print a visitor's pass **so that** a visitor with no phone or email still leaves the desk with a credential in hand.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-07 (SRS B1) — *"printed copy"* |
| **Dependencies** | US-09.5.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** an `active` credential, **When** I print the pass, **Then** a print-optimised layout is produced containing the QR at a scannable physical size, the visitor name, host, building, validity window with timezone, and the restriction.
- **AC-2 — Given** the printed output, **When** it is scanned from paper, **Then** the QR decodes reliably at the specified print size and contrast, verified by an automated decode test against the rendered print artefact.
- **AC-3 — Given** printing occurs, **When** it is initiated, **Then** it is audited as `credential.pass_print` with the actor and credential id.
- **AC-4 (negative — non-active) — Given** a credential not in `active` state, **When** printing is attempted, **Then** it is refused — a printed pass outlives the screen that showed it, so printing an invalid credential is worse than displaying one.
- **AC-5 (negative — content) — Given** the print layout, **When** it is generated, **Then** it contains no navigation, no operator identity, no other visitor's data, and no ACS reference in text form.
- **AC-6 (negative — authorization) — Given** a user without pass-view authority, **When** they request the print layout, **Then** they receive 404 consistent with the pass endpoint.

**Security considerations:** a printed pass is an uncontrolled physical artefact — it cannot be revoked by clearing a screen, so state gating (AC-4) and audit (AC-3) are how issuance stays accountable; the print layout is content-minimised (AC-5); the restriction and expiry are printed prominently so a found pass is recognisably expired.

**Development Tasks**

**T-09.6.2.1 — Print-optimised pass layout** · `P2` · `1 pt` · deps: `T-09.5.1.3`
- **Description:** Print stylesheet and layout producing a fixed-size pass with the QR at a specified physical dimension, high contrast, and no page chrome.
- **Acceptance Criteria:** print preview matches the specified dimensions across common paper sizes; navigation and operator data excluded from the print DOM; validity window and restriction rendered prominently.
- **Dependencies:** `T-09.5.1.3`

**T-09.6.2.2 — Print-size QR decode verification** · `P2` · `1 pt` · deps: `T-09.6.2.1, T-09.5.1.1`
- **Description:** Automated test rendering the print artefact at target DPI and decoding the QR back to the stored payload.
- **Acceptance Criteria:** decode succeeds at the specified print size and at a degraded-contrast variant; the test fails if module size or error-correction configuration is reduced below the verified threshold, so a later styling change cannot silently break scannability.
- **Dependencies:** `T-09.6.2.1`

**T-09.6.2.3 — Print action with state gating and audit** · `P2` · `1 pt` · deps: `T-09.6.2.1`
- **Description:** Print control on the pass and credential views, available only for `active` credentials, writing the print audit entry.
- **Acceptance Criteria:** control absent for non-`active` states and the underlying route refuses them; audit entry written on invocation; 404 for unauthorized callers.
- **Dependencies:** `T-09.6.2.1`

---

#### US-09.6.3 — Re-issue or resend a pass on request

**As a** Master Admin **I want** to resend a visitor's pass **so that** a lost email does not mean cancelling and re-approving the whole visit.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-07 (SRS B1) — shareability of the credential |
| **Dependencies** | US-09.6.1 |
| **Blocked by** | TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** an `active` credential previously delivered, **When** I resend it, **Then** the same credential is redelivered to the visitor's stored address, a new `vms.notification_logs` row is written, and no new ACS credential is created.
- **AC-2 — Given** a resend occurs, **When** it is recorded, **Then** an audit entry names the actor, the credential and the channel, so repeated resends are visible.
- **AC-3 (negative — no new credential) — Given** a resend, **When** it is processed, **Then** the ACS port is not invoked at all — resending is a delivery operation, not an issuance operation, and conflating the two would create duplicate credentials and duplicate ACS spend. Asserted with a strict mock port.
- **AC-4 (negative — rate limiting) — Given** repeated resends for one credential, **When** the configured threshold is exceeded, **Then** further resends are refused with 429, bounding use of the endpoint as a mail-bombing or credential-spraying vector.
- **AC-5 (negative — non-active) — Given** a credential not in `active` state, **When** a resend is attempted, **Then** it is refused with 409.
- **AC-6 (negative — authorization) — Given** a user without the delivery permission, **When** they attempt a resend, **Then** they receive 403 and the attempt is audited.

**Security considerations:** AC-3 and AC-4 are the controls that matter — resend must be structurally incapable of creating a credential, and it must be rate limited because it dispatches an access-bearing artefact to an address on demand; recipient remains server-derived (US-09.6.1 AC-6); every resend is audited so a pattern is detectable.

**Development Tasks**

**T-09.6.3.1 — Resend use case isolated from issuance** · `P2` · `1 pt` · deps: `T-09.6.1.1`
- **Description:** A delivery-only use case that loads an existing `active` credential and dispatches it, with no dependency on the ACS port in its construction graph.
- **Acceptance Criteria:** the use case does not receive the ACS port as a dependency at all, making AC-3 structural rather than behavioural; a strict-mock test confirms no port invocation; non-`active` states rejected.
- **Dependencies:** `T-09.6.1.1`

**T-09.6.3.2 — Resend endpoint with rate limiting and audit** · `P2` · `1 pt` · deps: `T-09.6.3.1`
- **Description:** `POST /api/v1/credentials/{id}/deliver` with a per-credential and per-user Redis rate limit and an audit entry.
- **Acceptance Criteria:** 202 on accepted dispatch; 429 with `Retry-After` beyond the threshold; 409 for non-`active`; 403 without permission; audit written on every attempt including refused ones.
- **Dependencies:** `T-09.6.3.1`

**T-09.6.3.3 — Delivery history on the credential view** · `P3` · `1 pt` · deps: `T-09.6.3.2, T-09.2.2.3`
- **Description:** Show the credential's delivery history from `vms.notification_logs` with channel, status and timestamp, plus the resend control.
- **Acceptance Criteria:** history ordered newest first; recipient address masked in the UI; resend control disabled while rate limited with the retry time shown; statuses conveyed by text as well as colour.
- **Dependencies:** `T-09.6.3.2`

---

### F-09.7 — Credential lifecycle state machine

> ⚠️ **`TDD-DERIVED` — backlog only.** Sourced from **TDD §4.3**, with no backing SRS functional
> requirement. Planned and estimated for a realistic phase shape, but **not authorized for
> implementation until TODO-01 is dispositioned.** *Note for the client conversation: the schema's
> `vms.credential_state` enum already defines six states, and FR-VMS-05, FR-VMS-12 and FR-VMS-14
> (SRS B1) each imply transitions between them, so — as with F-07.5 — this is a strong candidate for
> absorption into an SRS requirement rather than descope. Phase 2 drives `requested → active`,
> `requested → failed`, and the VMS-side moves to `cancelled`/`expired`/`revoked`. The **ACS-side**
> deactivation call (FR-VMS-12 (SRS B1)) is Phase 3 F-14.1 and is ⚠️ **TODO-09**.*

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` TDD §4.3 — pending TODO-01 |
| **Priority** | P0 *(if authorized — F-09.1 and F-09.2 depend on it)* · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-09.1, F-07.5 |

---

#### US-09.7.1 — Enforce legal credential state transitions

**As a** System Administrator **I want** credential state changes constrained to a defined state machine **so that** a revoked pass cannot be resurrected and a failed request cannot silently become active.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §4.3 — **backlog only pending TODO-01** |
| **Dependencies** | US-09.1.1 |
| **Blocked by** | TODO-01 (authorization to implement) · TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** the `vms.credential_state` enum (`requested`, `active`, `expired`, `revoked`, `cancelled`, `failed`), **When** the state machine is defined, **Then** the Phase 2 legal transitions are exactly: `requested → active`, `requested → failed`, `requested → cancelled`, `active → expired`, `active → revoked`, `active → cancelled`. All others, including anything out of `expired`, `revoked`, `failed` or `cancelled`, are illegal.
- **AC-2 — Given** a transition to `active`, **When** it is applied, **Then** `issued_at` is set; **Given** a transition to `expired`, `revoked` or `cancelled`, **Then** `deactivated_at` is set — the timestamps are driven by the transition, never assigned independently.
- **AC-3 — Given** the `ux_credentials_active_per_visitor` partial unique index, **When** a second credential is transitioned to `active` for the same visitor, **Then** the transition is refused at the domain level before the index is reached, with the index remaining as the last line of defence.
- **AC-4 (negative) — Given** an illegal transition such as `revoked → active` or `failed → active`, **When** it is attempted through any path — use case, event handler, scheduled job or direct repository call, **Then** a domain exception is raised and nothing is mutated.
- **AC-5 (negative — concurrency) — Given** a revocation and an ACS success response applying concurrently, **When** both run, **Then** optimistic locking resolves them to one outcome and the credential never ends up `active` after a committed revocation.
- **AC-6 (negative — exhaustiveness) — Given** a new value is added to `vms.credential_state` without extending the transition table, **When** the build runs, **Then** the exhaustiveness test fails.

**Security considerations:** this state machine is the mechanism by which a pass stops working. `revoked → active` being impossible (AC-4) and the revocation-vs-issuance race resolving safely (AC-5) are the two properties that make revocation trustworthy. Note the honest limit of this story: it governs **VMS's** record. Whether the physical credential stops working depends on ACS, which is ⚠️ **TODO-09** and Phase 3 F-14.1 — this must not be represented to the client as revocation at the barrier.

**Development Tasks**

**T-09.7.1.1 — Credential transition table and guard** · `P0` · `2 pts` · deps: `T-09.1.1.1`
- **Description:** Declarative transition table for `CredentialState` in the `domain` module, with a guard invoked by every mutating method and transition-driven timestamp assignment.
- **Acceptance Criteria:** parameterised test asserts every source×target cell across all six states; `issued_at` and `deactivated_at` are only ever set by a transition; exhaustiveness test covers the full enum; no method mutates state without the guard.
- **Dependencies:** `T-09.1.1.1`

**T-09.7.1.2 — Active-credential uniqueness enforced in the domain** · `P0` · `2 pts` · deps: `T-09.7.1.1, T-09.1.1.2`
- **Description:** Domain-level check plus optimistic locking so activation of a second credential for a visitor fails before the database index, with the index violation still mapped to the same domain exception.
- **Acceptance Criteria:** a concurrent double-activation integration test yields one `active` credential and one clean domain error, never a raw constraint stack trace; a revoke-vs-activate race never leaves the credential `active`; `@Version` column added by forward-only migration.
- **Dependencies:** `T-09.1.1.2`

**T-09.7.1.3 — Credential state fitness and exhaustiveness tests** · `P1` · `1 pt` · deps: `T-09.7.1.1`
- **Description:** Architecture fitness test asserting no code outside the aggregate assigns `state`, `issued_at` or `deactivated_at`, plus the enum exhaustiveness test.
- **Acceptance Criteria:** a direct assignment from a service or repository fails the build; an unmapped enum value fails the build; both run on every commit.
- **Dependencies:** `T-09.7.1.1`

---

#### US-09.7.2 — Emit credential lifecycle events and audit entries

**As a** System Administrator **I want** every credential state change published and audited **so that** notification, reporting and any later investigation have a complete, trustworthy record of who got access and when.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` TDD §4.3 — **backlog only pending TODO-01**; audit store from `TDD-DERIVED` FR-AUD-01 |
| **Dependencies** | US-09.7.1, US-07.5.2, F-05.1 |
| **Blocked by** | TODO-01 (authorization to implement) · TODO-02 (verification only — implementable against simulator per ADR-0002) |

**Acceptance Criteria**
- **AC-1 — Given** a credential transition commits, **When** the transaction completes, **Then** the corresponding event — `CredentialRequested`, `CredentialIssued`, `CredentialFailed`, `CredentialRevoked`, `CredentialExpired`, `CredentialCancelled` — is published through the outbox exactly once.
- **AC-2 — Given** an event is published, **When** its payload is inspected, **Then** it carries credential id, visitor id, state, window and timestamps — and **never** `qr_payload`, `acs_credential_id`, or any visitor PII.
- **AC-3 — Given** a transition commits, **When** the audit trail is inspected, **Then** a `vms.audit_logs` row records the actor (user or named system principal), `action` such as `credential.issue` or `credential.revoke`, and PII-redacted before/after states.
- **AC-4 — Given** `CredentialIssued`, **When** it is consumed by the pulled-forward notification service, **Then** it triggers the FR-NOT-01 (SRS B1) confirmation path — ⚠️ scoped in Phase 2 to email only, per TODO-05 and TODO-10.
- **AC-5 (negative) — Given** the audit write fails, **When** the transition transaction runs, **Then** the whole transaction rolls back — an unauditable grant of building access does not happen.
- **AC-6 (negative) — Given** a duplicate event delivery, **When** a consumer processes it, **Then** the `event_id` supports idempotent handling and the contract requires it.
- **AC-7 (negative) — Given** an attempt to add a credential secret to an event schema, **When** CI runs, **Then** the no-secrets serialization test fails the build.

**Security considerations:** credential events describe grants of physical access, so they are both a valuable audit source and a dangerous leak vector — AC-2 and AC-7 make the ids-only contract enforced rather than aspirational; fail-closed auditing (AC-5) means no untraceable issuance; the system principal is named distinctly from human actors so automated issuance is attributable.

**Development Tasks**

**T-09.7.2.1 — Credential outbox and lifecycle event schemas** · `P1` · `1 pt` · deps: `T-09.7.1.1, T-07.5.2.1`
- **Description:** Transactional outbox for the credential service and the six versioned lifecycle event schemas, registered with backward-compatibility enforcement.
- **Acceptance Criteria:** exactly one event per committed transition and none for a rollback; a broker-down test shows publication resuming on recovery; the no-secrets serialization test covers `qr_payload` and `acs_credential_id` and fails the build if either appears; schema compatibility gated in CI.
- **Dependencies:** `T-07.5.2.1`

**T-09.7.2.2 — Transactional credential audit** · `P1` · `1 pt` · deps: `T-09.7.2.1, T-07.4.3.1`
- **Description:** Audit writes on every credential transition inside the transition transaction, using the F-07.4 PII-redacting serializer and a named system principal for automated paths.
- **Acceptance Criteria:** a forced audit failure rolls back the transition; exactly one audit row per transition under concurrency; system-initiated and user-initiated entries are distinguishable; no credential secret in `before_state` or `after_state`.
- **Dependencies:** `T-07.4.3.1`

**T-09.7.2.3 — Consumer contract documentation for credential events** · `P2` · `1 pt` · deps: `T-09.7.2.1`
- **Description:** Document topics, keys, schemas, idempotency expectations and the no-PII/no-secrets contract for the credential events, for Phase 4 notification and reporting consumers.
- **Acceptance Criteria:** committed alongside the schemas; names every consumer expected in Phase 3 and Phase 4; states explicitly that consumers must not expect credential secrets and must resolve them through the authorized read path instead.
- **Dependencies:** `T-09.7.2.1`

---

## EPIC-10 — Host Management

> ⚠️ **`TDD-DERIVED` — entire epic is backlog only.** EPIC-10 has **no backing SRS functional
> requirement of its own**. `vms.hosts` exists in the schema, commented *"Tenant-side hosts who receive
> visitors (FR-VMS-06)"*, and `vms.visitor_requests.host_id` references it — but FR-VMS-06 (SRS B1) is
> about **storing the credential reference returned by ACS**, not about hosts. That schema comment is a
> mis-citation and is recorded as an inconsistency at the end of this document. The epic is planned and
> estimated so the phase has a realistic shape and cost, but **not authorized for implementation until
> TODO-01 is dispositioned.** *Note for the client conversation: F-07.1 and F-08.1 both reference a host
> on a visitor request, and FR-NOT-01 / FR-NOT-02 (SRS B1) in Phase 4 require notifying "the host" — so
> something host-shaped is needed. The minimum viable interpretation is a host **directory**, which is
> what is decomposed here.*

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` FR-VMS-06 (TDD §4.2) — ⚠️ mis-cited in the schema; see inconsistency note |
| **Requirement IDs** | None directly. Supports FR-VMS-01, FR-VMS-03 (SRS B1) and, in Phase 4, FR-NOT-01, FR-NOT-02 (SRS B1) |
| **Priority** | **P1** *(if authorized)* — F-07.1 and F-08.1 depend on a host reference existing |
| **Features** | 2 (F-10.1, F-10.2) |
| **Stories / Tasks / Points** | 4 stories · 12 tasks · **16 points** |
| **Bounded context** | Visitor & Approval (host is a supporting entity, not an aggregate root) |
| **Primary tables** | `vms.hosts`, `vms.tenants`, `vms.visitor_requests` |

**Goal.** Maintain the tenant-side people who receive visitors, so a visitor request can name who they
are coming to see and Phase 4 can notify that person. Hosts are tenant-owned reference data — created
and maintained within a tenant, selectable when raising a request or a pre-registration, and never
visible across tenant boundaries. The epic is deliberately minimal: a directory and an assignment. It
does not introduce host logins, host approval authority, or a host portal, none of which appear in the
attached SRS — Phase 3's F-13.2 walk-in host approval (FR-VMS-08 (SRS B1)) is separately gated on
⚠️ **TODO-18**, and nothing here pre-empts that decision.

---

### F-10.1 — Host directory

Create and maintain the tenant-side people who receive visitors.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` FR-VMS-06 (TDD §4.2) — pending TODO-01 |
| **Priority** | P1 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-04.3 (tenants), F-03.2 |

---

#### US-10.1.1 — Maintain the host directory for my tenant

**As a** Tenant **I want** to maintain the list of people in my organisation who receive visitors **so that** a request can name a real host and Phase 4 can notify them.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-VMS-06 (TDD §4.2) — **backlog only pending TODO-01** |
| **Dependencies** | F-04.3, F-03.2, F-06.3 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** I am authenticated as a Tenant, **When** I create a host with `full_name`, `email` and `phone`, **Then** a `vms.hosts` row is created with `tenant_id` derived from my own user record and `is_active = true`.
- **AC-2 — Given** hosts exist for my tenant, **When** I list them, **Then** I see only my tenant's hosts, paginated, with active and inactive distinguishable.
- **AC-3 — Given** a host who has left, **When** I deactivate them, **Then** `is_active` becomes false, they no longer appear in host selectors, and existing `vms.visitor_requests.host_id` references remain intact so historical requests stay readable.
- **AC-4 (negative — cross-tenant) — Given** a host belonging to another tenant, **When** I attempt to read, update or deactivate it by id, **Then** I receive 404 — `vms.hosts.tenant_id` is `NOT NULL` and scoping is applied in the repository, not the controller.
- **AC-5 (negative — deletion) — Given** a host referenced by existing requests, **When** deletion is attempted, **Then** it is refused in favour of deactivation, because `vms.hosts` has `ON DELETE CASCADE` from `vms.tenants` and deleting a host would orphan the audit story of every visit they received.
- **AC-6 (negative — authorization) — Given** a user without host-management authority, **When** they attempt any write, **Then** they receive 403 and the attempt is audited.

**Security considerations:** host records are employee PII (name, email, phone) belonging to a tenant, so the cross-tenant read is the primary risk (AC-4) and is closed in the repository layer; deactivation over deletion (AC-3, AC-5) preserves the audit trail; writes are permission-gated and audited; host contact details are excluded from logs and from any cross-tenant-visible projection.

**Development Tasks**

**T-10.1.1.1 — Host entity, persistence and tenant scoping** · `P1` · `1 pt` · deps: `T-07.6.1.1`
- **Description:** Host entity and repository mapping `vms.hosts` with an unconditional tenant-scope predicate on every read and write path, reusing the F-07.6 scoping mechanism.
- **Acceptance Criteria:** every repository method requires the tenant scope parameter, enforced by a fitness test; a cross-tenant id returns empty; Testcontainers test covers create, update, deactivate and the cascade behaviour from `vms.tenants`.
- **Dependencies:** `T-07.6.1.1`

**T-10.1.1.2 — Host CRUD endpoints with deactivation semantics** · `P1` · `2 pts` · deps: `T-10.1.1.1`
- **Description:** `GET`/`POST`/`PATCH /api/v1/hosts` and a deactivate action, with no delete endpoint at all.
- **Acceptance Criteria:** no delete route exists, making AC-5 structural; 404 for cross-tenant ids; 403 without permission; email validated and normalised; writes audited; list paginated and clamped.
- **Dependencies:** `T-10.1.1.1`

**T-10.1.1.3 — Host directory management page** · `P2` · `2 pts` · deps: `T-10.1.1.2`
- **Description:** Tenant-scoped host directory page with create, edit, deactivate and reactivate, and an active/inactive filter.
- **Acceptance Criteria:** inactive hosts visually and semantically distinguished; deactivate requires confirmation stating that existing requests are unaffected; no delete affordance anywhere in the UI; table semantics and labelled controls.
- **Dependencies:** `T-10.1.1.2`

---

#### US-10.1.2 — Search the host directory when raising a request

**As a** Floor Receptionist **I want** to search for a host by name **so that** I can attach the right person to a pre-registration without knowing an internal id.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-VMS-06 (TDD §4.2) — **backlog only pending TODO-01** |
| **Dependencies** | US-10.1.1 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** I am raising a request or pre-registration, **When** I search for a host by name, **Then** matching active hosts within my authorized scope are returned with name and, where relevant, the tenant.
- **AC-2 — Given** a search, **When** it executes, **Then** only `is_active = true` hosts are returned, so a departed employee cannot be attached to a new visit.
- **AC-3 (negative — cross-tenant) — Given** hosts in other tenants match my search term, **When** the query runs, **Then** they are not returned — a floor receptionist's scope is resolved from their reception point, and a tenant user's from their own tenant. ⚠️ **TODO-14** — the receptionist's exact breadth is the isolation question; the restrictive default applies.
- **AC-4 (negative — harvesting) — Given** a very short or empty search term, **When** it is submitted, **Then** no query runs and nothing is returned, and results are capped, preventing the endpoint being used to enumerate a tenant's staff directory.
- **AC-5 (negative — rate) — Given** repeated searches from one session, **When** the threshold is exceeded, **Then** requests are throttled with 429.
- **AC-6 (negative — disclosure) — Given** results are returned, **When** the payload is inspected, **Then** it contains host name and id only — never host email or phone, which are not needed to select a host.

**Security considerations:** this is a staff-directory enumeration surface (OWASP A01) — minimum term length, result cap, rate limiting, scope predicate and a minimised payload (AC-6) are all applied; search terms excluded from logs.

**Development Tasks**

**T-10.1.2.1 — Scoped host search query with harvesting controls** · `P2` · `1 pt` · deps: `T-10.1.1.1, T-08.1.1.2`
- **Description:** Active-host search predicated on the caller's resolved scope, with minimum term length, result ceiling and a supporting index on `(tenant_id, is_active, full_name)`.
- **Acceptance Criteria:** below-minimum terms short-circuit; results capped; cross-tenant hosts never returned, proven by a two-tenant integration test; inactive hosts excluded; indexed lookup.
- **Dependencies:** `T-08.1.1.2`

**T-10.1.2.2 — Host search endpoint with minimised payload** · `P2` · `1 pt` · deps: `T-10.1.2.1`
- **Description:** `GET /api/v1/hosts:search` returning id and name only, rate limited per user.
- **Acceptance Criteria:** payload contains no email or phone, asserted by test; 429 beyond the threshold; 403 without the relevant permission; search term absent from access logs.
- **Dependencies:** `T-10.1.2.1`

**T-10.1.2.3 — Shared host selector component** · `P2` · `1 pt` · deps: `T-10.1.2.2`
- **Description:** One host selector component consumed by both the tenant request form and the pre-registration form.
- **Acceptance Criteria:** a single shared component, not per-form duplicates; proper combobox semantics with keyboard navigation and announced result counts; debounced; clearly indicates when no hosts match rather than appearing broken.
- **Dependencies:** `T-10.1.2.2`

---

### F-10.2 — Host assignment to visitor requests

Attaching a host to a request, and keeping that attachment valid.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` TDD §4.2 — pending TODO-01 |
| **Priority** | P1 · **Stories** 2 · **Tasks** 6 · **Points** 8 |
| **Depends on** | F-10.1, F-07.1, F-08.1 |

---

#### US-10.2.1 — Assign a host to a visitor request

**As a** Tenant **I want** to name the host a visitor is coming to see **so that** reception knows who to call and Phase 4 knows who to notify.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §4.2 — **backlog only pending TODO-01** |
| **Dependencies** | US-10.1.1, US-07.1.1, US-08.1.1 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** I am creating a request or pre-registration, **When** I select a host, **Then** `vms.visitor_requests.host_id` is set to that host and the host is displayed on the request, the approval dashboard and the pass.
- **AC-2 — Given** a request in `submitted` state, **When** I change the assigned host, **Then** the change persists and is audited with before and after host ids.
- **AC-3 — Given** a request with an assigned host, **When** the credential is issued, **Then** the host is available for the Phase 4 FR-NOT-03 (SRS B1, ⚠️ TDD-referenced) host notification path without a further lookup at notification time.
- **AC-4 (negative — cross-tenant host) — Given** a host id belonging to a different tenant, **When** I submit it on a request, **Then** the request is rejected with 400 or 404 and the host is not attached — the host must belong to the request's tenant, validated server-side rather than trusted from the payload.
- **AC-5 (negative — inactive host) — Given** a host who has been deactivated, **When** I attempt to assign them to a new request, **Then** it is rejected; existing requests already referencing them are unaffected.
- **AC-6 (negative — decided request) — Given** a request already `approved` or `rejected`, **When** I attempt to reassign the host, **Then** the API returns 409, because the host was part of what the FM Admin approved.

**Security considerations:** AC-4 is a cross-tenant object-reference check (OWASP A01) — a host id from another tenant supplied on a request would leak that host's identity onto a foreign request and, in Phase 4, send them a notification about a visit they know nothing about; validation is server-side against the request's own tenant; reassignment after decision is blocked so the approved facts are stable.

**Development Tasks**

**T-10.2.1.1 — Host assignment validation on the request aggregate** · `P1` · `2 pts` · deps: `T-10.1.1.1, T-07.1.1.1`
- **Description:** Assignment rule on `VisitorRequest` validating that the host exists, is active, and belongs to the request's tenant, resolved through a host read port rather than a direct table read.
- **Acceptance Criteria:** unit tests cover cross-tenant, inactive, missing and valid hosts; assignment from a decided state raises a domain exception; no direct cross-context table access, asserted by the service-boundary fitness test.
- **Dependencies:** `T-07.1.1.1`

**T-10.2.1.2 — Host assignment and reassignment endpoints** · `P1` · `2 pts` · deps: `T-10.2.1.1`
- **Description:** Host selection on the create paths plus a reassignment operation legal only from `submitted`, audited with before and after values.
- **Acceptance Criteria:** 400/404 on a cross-tenant or missing host; 422 on an inactive host; 409 from a decided state; 403 without permission; audit entry records both host ids.
- **Dependencies:** `T-10.2.1.1`

**T-10.2.1.3 — Host display across request, dashboard and pass views** · `P2` · `1 pt` · deps: `T-10.2.1.2, T-09.5.1.3`
- **Description:** Render the assigned host consistently on the tenant request views, the approval dashboard, the credential views and the pass.
- **Acceptance Criteria:** host name shown, host email and phone shown only to users with host-directory access within the owning tenant; a request with no host renders an explicit "not assigned" rather than an empty cell.
- **Dependencies:** `T-10.2.1.2`

---

#### US-10.2.2 — Handle requests whose host becomes unavailable

**As an** FM Admin **I want** requests whose assigned host has been deactivated to be visible **so that** an approved visitor does not arrive to be met by someone who left the company.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` TDD §4.2 — **backlog only pending TODO-01** |
| **Dependencies** | US-10.2.1 |
| **Blocked by** | TODO-01 (authorization to implement) |

**Acceptance Criteria**
- **AC-1 — Given** a host is deactivated, **When** future-dated requests reference them, **Then** those requests are flagged on the approval dashboard and in the tenant's own list as having an unavailable host.
- **AC-2 — Given** a flagged request in `submitted` state, **When** the tenant reassigns the host, **Then** the flag clears.
- **AC-3 — Given** a request whose appointment has already passed, **When** its host is deactivated, **Then** it is not flagged — the flag concerns upcoming visits only, not history.
- **AC-4 (negative — no auto-cancellation) — Given** a host is deactivated, **When** the flagging runs, **Then** no request is automatically cancelled and no credential is automatically revoked; the flag is advisory, because automatically revoking building access as a side effect of an HR record change is not a behaviour any SRS requirement authorizes.
- **AC-5 (negative — scope) — Given** the flag is displayed, **When** it is rendered to a user outside the owning tenant, **Then** it indicates only that the host is unavailable, without disclosing the host's name or the reason.

**Security considerations:** AC-4 is the important restraint — an automated cascade from host deactivation to credential revocation would be an unrequested, high-impact behaviour with no requirement behind it, exactly the kind of invention project rule 3 forbids; the flag is advisory and human-actioned. AC-5 avoids leaking tenant staffing changes across tenant boundaries.

**Development Tasks**

**T-10.2.2.1 — Unavailable-host detection for upcoming requests** · `P3` · `1 pt` · deps: `T-10.2.1.1`
- **Description:** A derived flag on the request read models where the assigned host is inactive and the appointment window has not passed, computed in the projection rather than stored.
- **Acceptance Criteria:** past-dated requests never flagged; reassignment clears the flag on the next read with no stored state to become stale; the computation does not add a per-row query.
- **Dependencies:** `T-10.2.1.1`

**T-10.2.2.2 — Flag surfacing with scope-aware disclosure** · `P3` · `1 pt` · deps: `T-10.2.2.1, T-07.3.1.4`
- **Description:** Show the flag on the approval dashboard and the tenant request list, with full detail in the owning tenant and a minimised form elsewhere.
- **Acceptance Criteria:** out-of-tenant viewers see the flag without the host name or reason, proven by a cross-tenant test; the flag is conveyed by text and icon, not colour alone; the flag links to reassignment for users who can act on it.
- **Dependencies:** `T-10.2.2.1`

**T-10.2.2.3 — Explicit no-cascade regression test** · `P3` · `1 pt` · deps: `T-10.2.2.1`
- **Description:** A named regression test asserting that host deactivation produces no request cancellation, no visitor status change and no credential revocation.
- **Acceptance Criteria:** the test is named for the rule so its intent survives refactoring; it asserts no ACS port invocation using a strict mock; it references AC-4 and the no-invented-behaviour rule in a comment.
- **Dependencies:** `T-10.2.2.1`

---

## Phase 2 summary

### Per-epic totals

| Epic | Title | Provenance | Features | Stories | Tasks | Points |
|---|---|---|---|---|---|---|
| EPIC-07 | Visitor Request & Approval | `SRS` FR-VMS-01, FR-VMS-02 (SRS B1) · F-07.5 `TDD-DERIVED` | 6 | 15 | 52 | 65 |
| EPIC-08 | Visitor Pre-Registration | `SRS` FR-VMS-03, FR-VMS-11 (SRS B1) · F-08.4 `TDD-DERIVED` | 4 | 10 | 32 | 40 |
| EPIC-09 | Pass & Credential Generation | `SRS` FR-VMS-05/06/07/11/13 (SRS B1) · F-09.7 `TDD-DERIVED` | 7 | 16 | 50 | 69 |
| EPIC-10 | Host Management | `TDD-DERIVED` — entire epic | 2 | 4 | 12 | 16 |
| | **Phase 2 total** | | **19** | **45** | **146** | **190** |

### Per-feature breakdown

| Feature | Title | Provenance | Priority | Stories | Tasks | Points |
|---|---|---|---|---|---|---|
| F-07.1 | Tenant visitor request submission | `SRS` FR-VMS-01 | P0 | 3 | 12 | 13 |
| F-07.2 | Group & multi-visitor requests | `SRS` FR-VMS-01 | P1 | 2 | 7 | 10 |
| F-07.3 | FM Admin approval dashboard | `SRS` FR-VMS-02 | P0 | 3 | 10 | 11 |
| F-07.4 | Approve / reject with recorded reason | `SRS` FR-VMS-02 | P0 | 3 | 11 | 13 |
| F-07.5 | Request state machine & domain events | `TDD-DERIVED` | P0 | 2 | 6 | 10 |
| F-07.6 | Request status visibility for tenants | `SRS` FR-VMS-01 | P1 | 2 | 6 | 8 |
| F-08.1 | Floor receptionist pre-registration | `SRS` FR-VMS-03 | P0 | 3 | 10 | 13 |
| F-08.2 | Central server submission & synchronisation | `SRS` FR-VMS-03 | P1 | 2 | 6 | 8 |
| F-08.3 | Appointment scheduling & validity windows | `SRS` FR-VMS-11 | P0 | 3 | 10 | 11 |
| F-08.4 | Visitor record lifecycle & status tracking | `TDD-DERIVED` | P1 | 2 | 6 | 8 |
| F-09.1 | Credential creation request orchestration | `SRS` FR-VMS-05 | P0 | 3 | 11 | 18 |
| F-09.2 | Credential reference storage | `SRS` FR-VMS-06 | P0 | 2 | 6 | 8 |
| F-09.3 | Validity window specification | `SRS` FR-VMS-11 | P0 | 2 | 6 | 8 |
| F-09.4 | Restriction type selection | `SRS` FR-VMS-13 | P1 | 2 | 6 | 8 |
| F-09.5 | QR rendering & pass presentation | `SRS` FR-VMS-07 | P0 | 2 | 6 | 8 |
| F-09.6 | Pass delivery — email, on-screen, print | `SRS` FR-VMS-07 | P1 | 3 | 9 | 11 |
| F-09.7 | Credential lifecycle state machine | `TDD-DERIVED` | P0 | 2 | 6 | 8 |
| F-10.1 | Host directory | `TDD-DERIVED` | P1 | 2 | 6 | 8 |
| F-10.2 | Host assignment to visitor requests | `TDD-DERIVED` | P1 | 2 | 6 | 8 |
| | **Total** | | | **45** | **146** | **190** |

### Provenance split

| Provenance | Features | Stories | Points | Implementable now? |
|---|---|---|---|---|
| `SRS` | 15 | 35 | 148 | ✅ Yes — but see the ACS column below |
| `TDD-DERIVED` | 4 (F-07.5, F-08.4, F-09.7, and all of EPIC-10) | 10 | 42 | ⚠️ **Backlog only — pending TODO-01** |
| `BLOCKED` | 0 | 0 | 0 | — |

**22%** of Phase 2's points are `TDD-DERIVED` and therefore not authorized for implementation until
TODO-01 is dispositioned. Two of those four features — F-07.5 (request state machine) and F-09.7
(credential state machine) — sit on the **critical path of `SRS`-backed features** (F-07.4 and F-09.1
respectively). If TODO-01 is answered by descoping them rather than requirementing them, the SRS-backed
approval and issuance flows still need transition guards, which would then have to be built as
implementation detail inside those features. **This is the single most consequential item to resolve
before Phase 2 sprint planning.**

### ACS verification status

| | Stories | Points |
|---|---|---|
| Stories marked **Blocked by TODO-02 (verification only)** | **16** (all of EPIC-09) | **69** |
| Stories fully verifiable within Phase 2 | 29 | 121 |

**36%** of Phase 2's points are implementable but not verifiable until TODO-02 lands and F-11.7
replaces the simulator. The phase can be **demonstrated** in full; it cannot be **accepted** in full.

### Dependency graph

```
PHASE 1 FOUNDATIONS (must be complete before Phase 2 sprint 1)
  EPIC-02 identity ──┐
  EPIC-03 RBAC ──────┤
  EPIC-04 master data┤
  EPIC-05 audit ─────┤
  EPIC-06 portal ────┤
  PULLED FORWARD (ADR-0002):
    F-11.1 ACS port ─┤
    F-11.2 simulator ┤
    F-16.1/2 notify ─┘
          │
          ▼
╔═════════════════════════════════════════════════════════════════════════════╗
║ PHASE 2                                                                     ║
╚═════════════════════════════════════════════════════════════════════════════╝

  EPIC-10  HOST MANAGEMENT            ⚠️ TDD-DERIVED — pending TODO-01
    F-10.1 host directory
        └─► F-10.2 host assignment
                 │  (US-10.1.1 is a dependency of US-07.1.1 and US-08.1.1)
                 ▼
  EPIC-07  VISITOR REQUEST & APPROVAL
    F-07.5 state machine + events  ⚠️ TDD-DERIVED — pending TODO-01
        │   (US-07.5.1 gates every transition in F-07.1/F-07.4)
        ▼
    F-07.1 tenant submission ──► F-07.2 group requests
        │                            │
        ▼                            │
    F-07.3 approval dashboard ◄──────┘
        │
        ▼
    F-07.4 approve / reject ──► F-07.6 tenant status visibility
        │
        │  emits VisitorRequestApproved (Kafka, via outbox)
        │
        ├──────────────────────────────────────────────┐
        │                                              │
        ▼                                              │
  EPIC-08  VISITOR PRE-REGISTRATION                    │
    F-08.1 floor pre-registration                      │
        ├─► F-08.2 central submission & sync           │
        └─► F-08.3 appointment & validity windows      │
                 │                                     │
             F-08.4 visitor lifecycle                  │
             ⚠️ TDD-DERIVED — pending TODO-01          │
                 │                                     │
                 │  supplies the validity window       │
                 └──────────────┬──────────────────────┘
                                ▼
  EPIC-09  PASS & CREDENTIAL GENERATION
    ⛔ every story below: Blocked by TODO-02 (VERIFICATION ONLY)
       implementable against F-11.2 simulator per ADR-0002

    F-09.7 credential state machine  ⚠️ TDD-DERIVED — pending TODO-01
        │   (US-09.7.1 gates every transition in F-09.1/F-09.2)
        ▼
    F-09.3 validity window ──┐
    F-09.4 restriction type ─┤
                             ▼
                       F-09.1 credential creation ──[AcsPort]──► F-11.2 simulator
                             │                                        ╎
                             ▼                                        ╎ replaced by
                       F-09.2 reference storage                       ╎ F-11.7 when
                             │                                        ╎ TODO-02 lands
                             ▼                                        ▼
                       F-09.5 QR rendering ──► F-09.6 pass delivery  (PHASE 3)
                                                    │
                                                    └──► F-16.2 email channel

╔═════════════════════════════════════════════════════════════════════════════╗
║ PHASE 2 EXIT ──► PHASE 3 (EPIC-11 wire adapter, EPIC-12 arrival, EPIC-14    ║
║                  credential lifecycle ops, EPIC-15 card accountability)     ║
╚═════════════════════════════════════════════════════════════════════════════╝

CRITICAL PATH (longest chain):
  F-11.1 ──► F-09.7 ──► F-09.1 ──► F-09.2 ──► F-09.5 ──► F-09.6
  gated at both ends: F-09.7 by TODO-01, the whole chain's verification by TODO-02

TODO GATES TOUCHING PHASE 2:
  TODO-01 🔴  F-07.5, F-08.4, F-09.7, EPIC-10 entirely       (10 stories, 42 pts)
  TODO-02 🔴  EPIC-09 verification only                       (16 stories, 69 pts)
  TODO-07 🟠  NFR-PRF-01 has no numeric target — T-07.2.1.3, T-09.1.1.4 use a provisional p95 ≤ 3 s
  TODO-09 🟠  who expires a credential — US-08.4.2 AC-2, US-09.7.1 (ACS-side call deferred to F-14.1)
  TODO-10 🟠  email and/or WhatsApp — US-09.6.1 (email only this phase)
  TODO-12 🟠  PII retention — no purge rule; all visitor PII stored indefinitely
  TODO-13 🟠  ID document capture — visitors.id_document_ref written by no Phase 2 story
  TODO-14 🟠  tenant isolation — US-07.3.1, US-07.6.1, US-08.1.1, US-08.2.2, US-10.1.2 (restrictive default)
  TODO-17 🟡  visitor-facing display — US-09.5.2 covers the operator screen ONLY, not FR-VMS-15
  TODO-18 🟡  walk-in approval channel — not Phase 2; EPIC-10 deliberately does not pre-empt it
```

### Source-document inconsistencies noticed while writing this decomposition

Raised here rather than resolved, per project rule 3. None were guessed at in the backlog above.

1. **`vms.hosts` mis-cites FR-VMS-06.** The schema comments `vms.hosts` as *"Tenant-side hosts who
   receive visitors (FR-VMS-06)"*, but FR-VMS-06 (SRS B1) is about storing the **credential reference
   returned by ACS**. Hosts have no SRS requirement at all. This is why EPIC-10 is `TDD-DERIVED` despite
   the index citing FR-VMS-06 for F-10.1. Recommend a `needs-clarification` issue.
2. **`vms.credentials` cites a requirement range that does not exist.** The table is commented
   *"Passes/credentials requested from ACS (FR-VMS-07..16)"* — but the SRS FR-VMS series ends at 15, and
   the catalogue already flags `FR-VMS-16` as undefined (§5). The schema comment asserts a range one
   wider than the SRS provides.
3. **`vms.acs_api_log` cites FR-API-04, which does not exist in the SRS.** The comment reads
   *"Request/response audit trail for ACS integration (FR-API-04)"*; the SRS requirement for this is
   **FR-API-02 (SRS B1)**. This is discrepancy **D-04** (the FR-API ID collision) appearing in the
   schema as well as the TDD.
4. **TODO cross-references in `07-open-questions.md` point at the wrong epics.** TODO-04 says
   *"Story: EPIC-07"* but manual credential override is **F-14.4** in EPIC-14. TODO-05 and TODO-10 say
   *"EPIC-10"* but WhatsApp and channel selection are **F-16.3** and **F-16.5** in EPIC-16 — and EPIC-10
   in the index is Host Management. TODO-11 says *"EPIC-09"* but card reconciliation is **F-15.3** in
   EPIC-15. TODO-17 says *"EPIC-08"* but the visitor-facing display is **F-12.4** in EPIC-12. TODO-18
   says *"EPIC-06"* but walk-in approval is **F-13.2** in EPIC-13. TODO-03 says *"EPIC-06 / STORY for
   FR-VMS-10"* but express entry is **F-13.4** in EPIC-13. The open-questions register appears to
   predate the epic renumbering in `00-epic-feature-index.md` and should be re-pointed, or a reader
   will chase TODO-05 into the Host Management epic.
5. **TODO-02's "Blocks" list omits EPIC-11 and names EPIC-05.** It reads *"Blocks: EPIC-12, EPIC-05,
   EPIC-07, EPIC-09"*. EPIC-05 is the audit/security foundation, which has no ACS dependency, while
   **EPIC-11** — the ACS Integration Client itself — is absent from the list. Same renumbering drift.
6. **EPIC-07 is listed as blocked by TODO-02, which is only partly true.** Per TODO-02's list, EPIC-07
   is blocked. In fact EPIC-07 (request and approval) has **no ACS dependency whatsoever** — it is
   EPIC-09 that does. Treating EPIC-07 as ACS-gated would needlessly stall the phase's entire front
   half. This decomposition treats EPIC-07 as unblocked, and flags the discrepancy rather than
   silently disagreeing with the register.
7. **NFR-PRF-01 remains unmeasurable (TODO-07).** Two tasks here (`T-07.2.1.3`, `T-09.1.1.4`) need a
   latency target to write an assertion against. They use the register's own proposed **p95 ≤ 3 s** as
   an explicitly provisional value. It is not agreed, and every use is labelled.
8. **The index's coverage table maps FR-VMS-11 to F-08.3 and F-09.3, but the SRS wording covers only
   the ACS-request side.** FR-VMS-11 (SRS B1) says *"VMS shall specify a validity window … when
   requesting credential creation from ACS"* — that is F-09.3. F-08.3 (appointment scheduling on the
   visitor record) is the necessary precondition but is not literally what FR-VMS-11 states. F-08.3 is
   decomposed here as `SRS` on the reasonable reading that a window must exist before it can be
   specified, but a strict reverse trace could challenge it. Worth confirming alongside TODO-01.
