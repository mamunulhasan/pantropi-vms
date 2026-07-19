# Phase 3 — Entry Verification & ACS Integration

**Milestone:** M3 (Stage A + Stage B) with M4 pull-through · **Baseline:** B1
**Contract:** decomposes exactly the epics and features in [`00-epic-feature-index.md`](00-epic-feature-index.md). No additions, no renames, no renumbering.
**TDD alignment:** §4.4 Entry & ACS Operations Service, §6 ACS integration design, §3 technology stack.
**Architecture:** [ADR-0002 — Isolate ACS behind an anti-corruption layer, and build against a simulator](../../adr/0002-acs-anti-corruption-layer.md)

---

## Phase goal

Deliver the live visit lifecycle and the ACS integration boundary: a receptionist can verify an
arriving visitor, a credential can be requested, queried, deactivated and revoked through a single
ACS port, entry and exit events flow back into VMS idempotently, RFID cards are accounted for, and
every one of those interactions survives an ACS outage without losing data.

The user-visible surface of this phase is small. The reliability and boundary work underneath it is
not, and it is what makes NFR-MNT-01 and NFR-REL-01 (SRS B1) real rather than aspirational.

## Counts

| Level | Count |
|---|---|
| Epics | 5 (EPIC-11 … EPIC-15) |
| Features | 25 |
| User stories | 55 |
| Development tasks | 146 |
| Total story points | **220** |

| Epic | Features | Stories | Tasks | Points |
|---|---|---|---|---|
| EPIC-11 — ACS Integration Client (ACL) | 9 | 23 | 63 | 98 |
| EPIC-12 — Arrival Verification & Entry | 4 | 9 | 23 | 35 |
| EPIC-13 — Walk-in & Express Entry | 4 | 8 | 21 | 32 |
| EPIC-14 — Credential Lifecycle Operations | 4 | 7 | 19 | 27 |
| EPIC-15 — Card Accountability & Reconciliation | 4 | 8 | 20 | 28 |
| **Total** | **25** | **55** | **146** | **220** |

## Technology (TDD §3)

Java 21 + Spring Boot · PostgreSQL 14+ (`vms` schema) · Redis (credential status cache) · Kafka
(domain event stream, ACS retry topic, dead-letter topic) · Testcontainers for integration tests.

---

## ⚠️ ACS dependency note — the Stage A / Stage B split

**This is the governing constraint of the phase. Read it before planning a sprint.**

The detailed ACS API contract from Universal Automations Ltd — endpoints, authentication method,
payload schemas, error codes, event delivery mechanism, idempotency semantics, rate limits — **does
not exist** (TODO-02; SRS Appendix B item 4; TDD §11 dependency 1). Per ADR-0002 we do not wait for
it and we do not guess it. We build to a port we own and test against a simulator we build.

Every story in this phase therefore carries an **ACS Stage** field:

| Stage | Meaning | Can a sprint take it today? |
|---|---|---|
| **A (simulator)** | Fully implementable and fully testable **now**, against the ACS simulator (F-11.2). Its acceptance criteria are satisfiable without UAL. | ✅ Yes |
| **B (real contract)** | Requires the published UAL contract (TODO-02) **and** the ACS non-production endpoint (TDD §9.3). Cannot be started, let alone verified. | ⛔ No |
| **N/A** | Contains no ACS interaction at all — pure VMS workflow, persistence or UI. | ✅ Yes |

**Story split:** 36 Stage A (146 pts) · 5 Stage B (26 pts) · 14 N/A (48 pts).

### The honest limitation

Passing tests against our own simulator proves our code is internally consistent. **It proves
nothing about ACS.** A Stage A story reaching all its acceptance criteria is marked
**"done against simulator"** — not "done", not "verified". It converts to verified only when it is
re-run against the UAL non-production endpoint under F-11.7. The traceability matrix records this
distinction per requirement, and no Stage A story may be reported to the client as satisfying an
FR-VMS-* or FR-CRD-* requirement without that qualifier attached.

### What Stage B costs us if TODO-02 never lands

F-11.7 (18 points) and F-13.4 (8 points) are unstartable — 26 of 220 points. The remaining 194
points are real, shippable work that de-risks the gate rather than idling behind it. This is the
entire justification for ADR-0002.

---

## Phase exit criteria

### Stage A — buildable now, against the simulator

- [ ] `AcsPort` is defined in the application layer in VMS domain vocabulary; `createCredential`,
      `deactivateCredential`, `queryCredentialStatus` and inbound event handling are the only ways
      VMS reaches ACS (CON-02, SRS B1)
- [ ] The architecture fitness test fails the build if any type in the ACS adapter package is
      referenced from `domain`, `application` or `interfaces` (CON-01, NFR-MNT-01, SRS B1)
- [ ] The ACS simulator implements `AcsPort` with configurable latency, failure injection, malformed
      responses and event emission — and is maintained as production-quality test infrastructure
- [ ] Every outbound ACS operation is durably recorded in `vms.acs_requests` before it is attempted,
      retried with exponential backoff, and dead-lettered after the configured attempt ceiling
      (FR-API-01, SRS B1; NFR-REL-01, SRS B1)
- [ ] A receptionist is notified in-session when a credential request cannot be completed, and the
      request is not lost (FR-API-01, SRS B1)
- [ ] Every ACS request and response is written to `vms.acs_api_log` with secrets and visitor PII
      redacted (FR-API-02, SRS B1)
- [ ] ACS authentication credentials are resolved from the secrets manager at runtime and appear in
      no source file, no configuration file in version control, and no database column
      (FR-API-03, SRS B1)
- [ ] Inbound access and exit events are idempotent on `vms.access_events.acs_event_id`; a
      redelivered event produces exactly one row and exactly one downstream domain event
- [ ] Arrival lookup, check-in/check-out, walk-in registration, credential query, deactivation,
      revocation, card issuance/return logging and daily reconciliation all pass integration tests
      against the simulator
- [ ] The periodic VMS↔ACS reconciliation job detects and reports state drift
- [ ] No story in this phase is reported as "verified" — only "done against simulator"

### Stage B — requires the real UAL contract (TODO-02)

- [ ] The published UAL ACS API contract is received and reviewed against `AcsPort`; any port
      revision required is raised as a `BREAKING CHANGE` and re-baselined
- [ ] The wire-level adapter (F-11.7) is implemented against the published contract
- [ ] Contract tests run consumer-driven against the ACS non-production endpoint (TDD §9.3)
- [ ] Every Stage A story is re-executed against the non-production endpoint and converts from
      "done against simulator" to **verified** in the traceability matrix
- [ ] NFR-PRF-01 (SRS B1) is measured against a numeric target — **requires TODO-07**
- [ ] TODO-09 is answered: whether VMS or ACS expires a credential at end of validity, closing F-14.1
- [ ] TODO-11 is answered: reconciliation run time, day cut-off, recipient and channel, closing F-15.3
- [ ] TODO-03 is answered: express entry confirmed or descoped, closing or deleting F-13.4
- [ ] TODO-04 is answered: manual override requirementised or descoped, closing or deleting F-14.4

### Open questions that constrain this phase

| TODO | Effect on Phase 3 | Stories held conservative |
|---|---|---|
| **TODO-02** 🔴 | ACS contract absent — the whole Stage A/B split exists because of this | F-11.7 entirely |
| **TODO-03** 🔴 | Express entry may not be achievable; vendor describes a fully reception-mediated flow | F-13.4 — descope candidate |
| **TODO-04** 🟠 | Manual override has no SRS requirement (D-06) | F-14.4 — **do not build** |
| **TODO-07** 🟠 | NFR-PRF-01 has no numeric target | Reception-facing timeout ACs |
| **TODO-09** 🟠 | Unclear whether VMS or ACS expires credentials | F-14.1 — must tolerate both |
| **TODO-11** 🟠 | Reconciliation timing, cut-off, recipient undefined | F-15.3, F-15.4 |
| **TODO-17** 🟡 | Visitor display physical/interaction model undefined | F-12.4 |
| **TODO-18** 🟡 | Walk-in approval channel undefined — flow or checkbox | F-13.2 |

We do not resolve any of these ourselves. Where a story touches one, its acceptance criteria stay
deliberately conservative, the ambiguous branch is carved out, and the story carries a `Blocked by`.

---

## EPIC-11 — ACS Integration Client (Anti-Corruption Layer)

**Provenance:** `SRS-CON` CON-01, CON-02 (SRS B1) · `SRS` FR-API-01 (SRS B1), FR-API-02 (SRS B1), FR-API-03 (SRS B1) ·
`SRS-NFR` NFR-MNT-01, NFR-REL-01 (SRS B1) · `ENABLER` / ADR-0002 · `TDD-DERIVED` TDD §6.2
**Requirement IDs:** FR-API-01 (SRS B1), FR-API-02 (SRS B1), FR-API-03 (SRS B1), CON-01 (SRS B1),
CON-02 (SRS B1), NFR-MNT-01 (SRS B1), NFR-REL-01 (SRS B1)
**Priority:** P0 · **Story points:** 98 · **Stories:** 23 · **Tasks:** 63

Build the single boundary through which all VMS↔ACS traffic passes, and make that boundary reliable
enough that an ACS outage is an inconvenience rather than a data-loss event. The epic owns the port
definition, the simulator that makes the port testable before UAL delivers anything, the durable
outbox and retry machinery that satisfies FR-API-01 (SRS B1), the request/response audit trail that
satisfies FR-API-02 (SRS B1), the authentication and secrets handling that satisfies FR-API-03
(SRS B1), idempotent inbound event consumption keyed on the `vms.access_events.acs_event_id` unique
constraint, and the periodic reconciliation that catches drift between the two systems. Everything
except F-11.7 is Stage A and buildable today; F-11.7 is the seam where the real contract eventually
lands, and it is the only place in the codebase that should need to change when it does.

---

### F-11.1 — ACS port definition

Define `AcsPort` in the application layer, in VMS domain vocabulary, and enforce by automated test that no ACS-specific type escapes the adapter package.

**Provenance:** `SRS-CON` CON-02 (SRS B1), `SRS-NFR` NFR-MNT-01 (SRS B1) · ADR-0002 decisions 1, 2, 4 · **Priority:** P0 · **Points:** 8 · **Stories:** 2

#### US-11.1.1 — Define the ACS port in domain vocabulary

**As a** System Administrator **I want** every ACS interaction to go through one interface expressed in VMS terms **so that** a change to the ACS API contract is absorbed in one place instead of rippling through the visitor workflow.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS-CON` CON-02 (SRS B1), `SRS-NFR` NFR-MNT-01 (SRS B1) · ADR-0002 |
| **Dependencies** | — |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** the application layer, **When** `AcsPort` is inspected, **Then** it exposes exactly `createCredential`, `deactivateCredential`, `queryCredentialStatus` and inbound access/exit and card event handling per TDD §6.1, and every parameter and return type is a VMS domain type.
- **AC-2 — Given** a credential creation request, **When** it is expressed as an `AcsCredentialRequest`, **Then** it carries visitor reference, `credential_type` (`qr`/`rfid`), `restriction` (`time_bound`/`one_time`), `valid_from` and `valid_to` — mirroring `vms.credentials` columns, not any ACS field naming.
- **AC-3 — Given** the port definition, **When** the module is compiled, **Then** it has no dependency on any HTTP client, serialisation library or ACS vendor package.
- **AC-4 (negative) — Given** a developer adds an ACS vendor enum to a port method signature, **When** CI runs, **Then** the architecture fitness test fails the build with a message naming the offending type.

**Development Tasks**

**T-11.1.1.1 — Define `AcsPort` interface and operation signatures** · `P0` · `2 pts` · deps: `—`
- **Description:** Create the `AcsPort` interface in the `application` layer's `port.out` package with the five TDD §6.1 operations. Signatures use VMS domain types only.
- **Acceptance Criteria:** Interface compiles with zero framework imports; all five operations present; javadoc names the governing requirement CON-02 (SRS B1).
- **Dependencies:** `—`

**T-11.1.1.2 — Define port request/response value objects** · `P0` · `2 pts` · deps: `T-11.1.1.1`
- **Description:** Immutable value objects for credential request, credential result, status result and inbound event, mapped conceptually to `vms.credentials`, `vms.access_events` and `vms.card_issuances` columns.
- **Acceptance Criteria:** All types immutable; validity window enforces `valid_to > valid_from` matching the `vms.credentials` CHECK constraint; no nullable ACS-shaped fields; unit tests cover construction validation.
- **Dependencies:** `T-11.1.1.1`

**T-11.1.1.3 — Define the ACS failure taxonomy in VMS terms** · `P0` · `1 pt` · deps: `T-11.1.1.2`
- **Description:** A sealed VMS-owned failure type distinguishing transient (retryable), permanent (non-retryable), and malformed-response outcomes, so callers never see an ACS error code.
- **Acceptance Criteria:** Three outcome categories defined; retry eligibility is a property of the VMS type, not of an ACS status code; unit tested.
- **Dependencies:** `T-11.1.1.2`

#### US-11.1.2 — Enforce the ACS boundary in CI

**As a** System Administrator **I want** the build to fail when ACS-specific types leak out of the adapter **so that** the anti-corruption layer is guaranteed by automation rather than by reviewer vigilance.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS-CON` CON-01 (SRS B1), `SRS-NFR` NFR-MNT-01 (SRS B1) · ADR-0002 decision 4 |
| **Dependencies** | US-11.1.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** the CI pipeline, **When** the architecture fitness test suite runs, **Then** it asserts that no type in the ACS adapter package is referenced from `domain`, `application` or `interfaces`.
- **AC-2 — Given** a clean codebase, **When** the fitness test runs, **Then** it passes and reports the number of boundary rules evaluated.
- **AC-3 (negative) — Given** a controller imports an adapter DTO, **When** CI runs, **Then** the build fails, the offending class and import are named, and the failure references CON-01 (SRS B1).

**Development Tasks**

**T-11.1.2.1 — Add ACS-boundary architecture fitness test** · `P0` · `2 pts` · deps: `US-11.1.1`
- **Description:** ArchUnit rule set asserting the adapter package is referenced only by the Spring wiring configuration, plus the existing inward-dependency layer rules.
- **Acceptance Criteria:** Rule fails on a deliberately seeded violation; passes on `develop`; failure message names class, import and requirement.
- **Dependencies:** `US-11.1.1`

**T-11.1.2.2 — Wire the fitness test into the required CI status checks** · `P0` · `1 pt` · deps: `T-11.1.2.1`
- **Description:** Add the fitness test to the branch-protection required checks on `develop` and `main` per §2 of the workflow document.
- **Acceptance Criteria:** A PR with a boundary violation cannot be merged; check name appears in branch protection; documented in the PR checklist.
- **Dependencies:** `T-11.1.2.1`

---

### F-11.2 — ACS simulator

A first-class, production-quality simulator implementing `AcsPort`, with configurable latency, failure injection and event emission.

**Provenance:** `ENABLER` / ADR-0002 decision 3 · justified against NFR-REL-01 (SRS B1) · **Priority:** P0 · **Points:** 11 · **Stories:** 3

#### US-11.2.1 — Baseline ACS simulator implementing the port

**As a** developer **I want** a simulator that implements `AcsPort` with realistic happy-path behaviour **so that** every credential workflow can be built and integration-tested before the UAL contract exists.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` / ADR-0002 decision 3 |
| **Dependencies** | US-11.1.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a credential creation request, **When** the simulator handles it, **Then** it returns a synthetic `acs_credential_id` and, for `credential_type = 'qr'`, a synthetic `qr_payload`, suitable for persisting to `vms.credentials`.
- **AC-2 — Given** a credential the simulator has created, **When** `queryCredentialStatus` is called, **Then** it returns a state consistent with the validity window it was given (`active` inside, `expired` after `valid_to`).
- **AC-3 — Given** a `deactivateCredential` call, **When** it succeeds, **Then** a subsequent status query reports `revoked` and the simulator holds that state for the test lifetime.
- **AC-4 (negative) — Given** a `deactivateCredential` call for an `acs_credential_id` the simulator never issued, **When** it is handled, **Then** it returns a permanent (non-retryable) failure and the caller does not retry.

**Development Tasks**

**T-11.2.1.1 — Implement the simulator's in-memory credential store and state machine** · `P0` · `2 pts` · deps: `US-11.1.1`
- **Description:** Simulator-internal store keyed by synthetic credential id, tracking state transitions mirroring `vms.credential_state`.
- **Acceptance Criteria:** State transitions requested→active→expired/revoked enforced; illegal transition returns a permanent failure; store is resettable between tests.
- **Dependencies:** `US-11.1.1`

**T-11.2.1.2 — Implement create / deactivate / query operations** · `P0` · `2 pts` · deps: `T-11.2.1.1`
- **Description:** Full `AcsPort` implementation over the simulator store, honouring `restriction` semantics for `one_time` (single successful entry then unusable).
- **Acceptance Criteria:** All three operations implemented; one-time credential rejects a second entry; unit tests cover each operation and each restriction type.
- **Dependencies:** `T-11.2.1.1`

**T-11.2.1.3 — Package the simulator as a selectable Spring profile** · `P0` · `1 pt` · deps: `T-11.2.1.2`
- **Description:** Bind the simulator as the `AcsPort` bean under the `acs-simulator` profile; the wire adapter binds under `acs-live`. Exactly one binds at a time.
- **Acceptance Criteria:** Context fails fast if both or neither profile is active; the simulator profile is the default in local and CI environments and is refused in production configuration.
- **Dependencies:** `T-11.2.1.2`

#### US-11.2.2 — Configurable latency and failure injection

**As a** developer **I want** to make the simulator slow, flaky, or broken on demand **so that** ACS failure modes are tested deliberately rather than discovered in production.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `ENABLER` / ADR-0002 · justified against NFR-REL-01 (SRS B1), FR-API-01 (SRS B1) |
| **Dependencies** | US-11.2.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a configured fixed or distributed latency, **When** any port operation is invoked, **Then** the simulator delays by that amount before responding, so timeout handling is exercisable.
- **AC-2 — Given** a configured failure rate, **When** operations are invoked repeatedly, **Then** the configured proportion fails transiently and the retry machinery in F-11.3 engages.
- **AC-3 (negative) — Given** the simulator is configured to return a malformed response, **When** the adapter receives it, **Then** it is classified as a malformed-response failure, is not silently coerced into a success, and no partial row is written to `vms.credentials`.
- **AC-4 (negative) — Given** the simulator is configured as fully unavailable, **When** a credential request is made, **Then** the call fails transiently rather than hanging indefinitely, and the caller-side timeout fires.

**Development Tasks**

**T-11.2.2.1 — Implement latency and timeout injection** · `P0` · `1 pt` · deps: `US-11.2.1`
- **Description:** Configurable fixed, uniform and long-tail latency profiles, plus a "never responds" mode.
- **Acceptance Criteria:** Latency configurable per operation; long-tail profile reproducibly triggers the client timeout; documented in the simulator README.
- **Dependencies:** `US-11.2.1`

**T-11.2.2.2 — Implement transient/permanent failure and malformed-response injection** · `P0` · `2 pts` · deps: `T-11.2.2.1`
- **Description:** Injection of transient failures at a configured rate, permanent failures on demand, and structurally malformed payloads (missing credential id, unparseable validity window, wrong type).
- **Acceptance Criteria:** Each injection mode reachable from an integration test; malformed responses map to the malformed-response category from T-11.1.1.3; deterministic under a fixed seed.
- **Dependencies:** `T-11.2.2.1`

#### US-11.2.3 — Simulated inbound event emission

**As a** developer **I want** the simulator to emit access, exit and card events **so that** inbound event handling and idempotency can be tested without ACS.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `ENABLER` / ADR-0002 · supports `TDD-DERIVED` TDD §6.2 |
| **Dependencies** | US-11.2.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** an active credential in the simulator, **When** a simulated gate scan is triggered, **Then** an access event with a unique synthetic `acs_event_id`, direction `entry`, `gate_ref` and `event_time` is emitted to the inbound handler.
- **AC-2 — Given** an entry event has been emitted, **When** an exit is triggered, **Then** a matching event with direction `exit` is emitted.
- **AC-3 — Given** an RFID credential, **When** a card-issuer swipe is simulated, **Then** a card return event carrying the `acs_card_id` is emitted.
- **AC-4 (negative) — Given** an event that has already been emitted, **When** redelivery is triggered, **Then** the same `acs_event_id` is reused so that duplicate-handling in F-11.8 is exercised.

**Development Tasks**

**T-11.2.3.1 — Implement event emission for entry, exit and card return** · `P0` · `2 pts` · deps: `US-11.2.1`
- **Description:** Simulator-side event generator producing inbound events in the port's VMS-shaped event type, dispatched to the registered inbound handler.
- **Acceptance Criteria:** Entry, exit and card-return events emitted; `acs_event_id` unique per logical event; `event_time` controllable for clock-skew tests.
- **Dependencies:** `US-11.2.1`

**T-11.2.3.2 — Implement deliberate redelivery and out-of-order emission** · `P1` · `1 pt` · deps: `T-11.2.3.1`
- **Description:** Test hooks to redeliver a prior event verbatim and to deliver an exit before its corresponding entry.
- **Acceptance Criteria:** Redelivery reuses `acs_event_id` exactly; out-of-order emission available to integration tests; both documented as supported test scenarios.
- **Dependencies:** `T-11.2.3.1`

---

### F-11.3 — Outbound retry, backoff & durable outbox

Every outbound ACS operation is durably recorded in `vms.acs_requests` before it is attempted, retried with exponential backoff, and never lost to a crash.

**Provenance:** `SRS` FR-API-01 (SRS B1) · `SRS-NFR` NFR-REL-01 (SRS B1) · **Priority:** P0 · **Points:** 13 · **Stories:** 3

#### US-11.3.1 — Durably record every outbound ACS operation before it is attempted

**As a** Central Receptionist **I want** a credential request to be recorded before VMS calls ACS **so that** an outage or crash never loses the request I made on behalf of a visitor standing at my desk.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-API-01 (SRS B1), `SRS-NFR` NFR-REL-01 (SRS B1) · ADR-0002 decision 5 |
| **Dependencies** | US-11.1.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** any outbound ACS operation, **When** it is initiated, **Then** a `vms.acs_requests` row is committed with `operation` set to the matching `vms.acs_op` value, `credential_id`, `request_payload`, `status = 'pending'` and `attempt_count = 0` **before** the network call is attempted.
- **AC-2 — Given** a successful ACS response, **When** it is processed, **Then** the same row is updated to `status = 'succeeded'` with `response_payload` populated, in the same transaction as the resulting `vms.credentials` state change.
- **AC-3 — Given** a transient failure, **When** it is processed, **Then** the row moves to `status = 'failed'` with `last_error` and `next_retry_at` set, and remains eligible for the retry worker.
- **AC-4 (negative) — Given** the application crashes between committing the outbox row and receiving the ACS response, **When** it restarts, **Then** the row is still present and is picked up by the retry worker; no request is silently dropped.
- **AC-5 (negative) — Given** ACS is unreachable entirely, **When** the operation is initiated, **Then** the outbox row is still committed and the visitor's pre-registration and approval data are untouched (NFR-REL-01, SRS B1).

**Development Tasks**

**T-11.3.1.1 — Implement the outbox repository over `vms.acs_requests`** · `P0` · `2 pts` · deps: `US-11.1.1`
- **Description:** Persistence adapter for `vms.acs_requests` supporting insert-pending, mark-sent, mark-succeeded, mark-failed-with-retry and claim-due-for-retry, using the existing `idx_acsreq_status` index on `(status, next_retry_at)`.
- **Acceptance Criteria:** All five operations implemented; claim uses `SELECT … FOR UPDATE SKIP LOCKED` so concurrent workers do not double-send; Testcontainers integration tests cover each transition.
- **Dependencies:** `US-11.1.1`

**T-11.3.1.2 — Implement the outbox-first decorator around `AcsPort`** · `P0` · `2 pts` · deps: `T-11.3.1.1`
- **Description:** A port decorator that writes the outbox row, then delegates, then records the outcome — so callers get outbox semantics without knowing they exist (ADR-0002 decision 5).
- **Acceptance Criteria:** Decorator applies to all outbound operations; no caller references the outbox; integration test proves the row exists before the delegate is invoked.
- **Dependencies:** `T-11.3.1.1`

**T-11.3.1.3 — Enforce transactional consistency with credential state** · `P0` · `1 pt` · deps: `T-11.3.1.2`
- **Description:** Ensure the outbox outcome update and the `vms.credentials` state change commit together, honouring the one-aggregate-per-transaction rule via the credential aggregate.
- **Acceptance Criteria:** Integration test with an injected failure between the two writes leaves no inconsistent pair; the partial unique index `ux_credentials_active_per_visitor` is never violated.
- **Dependencies:** `T-11.3.1.2`

**T-11.3.1.4 — Redact secrets and PII from persisted payloads** · `P0` · `1 pt` · deps: `T-11.3.1.2`
- **Description:** Redaction applied to `request_payload` and `response_payload` before persistence — no authentication material, no raw `id_document_ref`.
- **Acceptance Criteria:** Auth headers and tokens never appear in `vms.acs_requests`; a unit test asserts redaction for each known sensitive field; supports FR-API-03 (SRS B1).
- **Dependencies:** `T-11.3.1.2`

#### US-11.3.2 — Retry failed ACS operations with exponential backoff

**As a** System Administrator **I want** failed ACS operations retried automatically with increasing delays **so that** a transient outage resolves itself without manual intervention and without hammering a recovering ACS.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-API-01 (SRS B1), `SRS-NFR` NFR-REL-01 (SRS B1) |
| **Dependencies** | US-11.3.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a `vms.acs_requests` row in `status = 'failed'` with `next_retry_at` in the past, **When** the retry worker runs, **Then** the operation is re-attempted and `attempt_count` is incremented.
- **AC-2 — Given** successive failures, **When** `next_retry_at` is computed, **Then** the delay grows exponentially with jitter, and the schedule is derived from configuration rather than hard-coded.
- **AC-3 — Given** the `acs.retry.max_attempts` system setting (seeded at `5` in `vms.system_settings`), **When** `attempt_count` reaches it, **Then** the row transitions to `status = 'dead_letter'` and is no longer retried (handled by F-11.4).
- **AC-4 — Given** a retry succeeds, **When** the outcome is recorded, **Then** `status = 'succeeded'`, and the credential reaches the state it would have reached on the first attempt.
- **AC-5 (negative) — Given** two worker instances run concurrently, **When** both poll for due rows, **Then** each row is claimed by exactly one worker and no ACS operation is sent twice.
- **AC-6 (negative) — Given** a permanent failure classification, **When** it is recorded, **Then** the row is **not** retried and moves directly to dead-letter with the reason preserved in `last_error`.

**Development Tasks**

**T-11.3.2.1 — Implement the backoff schedule calculator** · `P0` · `1 pt` · deps: `US-11.3.1`
- **Description:** Exponential backoff with configurable base, multiplier, ceiling and full jitter, reading `acs.retry.max_attempts` from `vms.system_settings`.
- **Acceptance Criteria:** Delay sequence unit-tested at each attempt count; jitter bounded; ceiling respected; zero hard-coded intervals.
- **Dependencies:** `US-11.3.1`

**T-11.3.2.2 — Implement the retry worker** · `P0` · `2 pts` · deps: `T-11.3.2.1`
- **Description:** Scheduled worker in the `interfaces` layer claiming due rows via the outbox repository and re-invoking the port, backed by a Kafka retry topic for fan-out per TDD §3.
- **Acceptance Criteria:** Due rows re-attempted; claim is exclusive under concurrency (integration test with two workers); worker is idempotent across restarts.
- **Dependencies:** `T-11.3.2.1`

**T-11.3.2.3 — Distinguish retryable from non-retryable outcomes** · `P0` · `1 pt` · deps: `T-11.3.2.2`
- **Description:** Route outcomes through the failure taxonomy from T-11.1.1.3 so permanent failures bypass retry entirely.
- **Acceptance Criteria:** Permanent failure produces exactly one attempt then dead-letter; transient failure retries to the ceiling; malformed response treated as permanent and never retried into a loop.
- **Dependencies:** `T-11.3.2.2`

**T-11.3.2.4 — Emit retry metrics and structured logs** · `P1` · `1 pt` · deps: `T-11.3.2.2`
- **Description:** Counters for attempts, successes, transient failures, permanent failures and dead-letters, plus correlation-id-tagged structured logs.
- **Acceptance Criteria:** Metrics exposed on the actuator endpoint; every log line carries the correlation id and the `vms.acs_requests` id; no payload content logged at INFO.
- **Dependencies:** `T-11.3.2.2`

#### US-11.3.3 — Notify the receptionist when a credential request cannot be completed

**As a** Central Receptionist **I want** to be told immediately when a credential request has not gone through **so that** I can manage the visitor in front of me instead of assuming success.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-API-01 (SRS B1) — *"notify the receptionist of the failure"* |
| **Dependencies** | US-11.3.1 |
| **Blocked by** | TODO-07 (no NFR-PRF-01 numeric target for the wait threshold) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a credential request that fails or exceeds the configured reception wait threshold, **When** the outcome is known, **Then** the requesting receptionist receives an in-session notification stating that the request is queued and will be retried automatically.
- **AC-2 — Given** a queued request that later succeeds on retry, **When** it completes, **Then** the receptionist's view for that visitor updates to show the credential without requiring a manual refresh of the whole page.
- **AC-3 — Given** the notification is displayed, **When** the receptionist reads it, **Then** it names the visitor and the operation, and offers no "retry now" control that could produce a duplicate ACS request.
- **AC-4 (negative) — Given** ACS is unreachable, **When** the receptionist retries the workflow from the UI, **Then** the system recognises the existing pending `vms.acs_requests` row and does not enqueue a second operation for the same credential.

> **Conservative by design.** The wait threshold is configuration with a provisional default. It is
> not a committed performance target — NFR-PRF-01 (SRS B1) has no number until TODO-07 resolves.

**Development Tasks**

**T-11.3.3.1 — Surface queued/failed credential requests to the reception UI** · `P0` · `2 pts` · deps: `US-11.3.1`
- **Description:** Reception-facing status projection over `vms.acs_requests` joined to `vms.credentials`, pushed to the session that initiated the request.
- **Acceptance Criteria:** Queued, failed and succeeded states each render distinctly; update arrives without full page reload; projection is read-only and touches no other context's tables.
- **Dependencies:** `US-11.3.1`

**T-11.3.3.2 — Make the reception wait threshold configurable** · `P1` · `1 pt` · deps: `T-11.3.3.1`
- **Description:** A `vms.system_settings` key for the wait threshold, defaulted provisionally and documented as pending TODO-07.
- **Acceptance Criteria:** Threshold read from settings at runtime; changing it needs no redeploy; the setting description explicitly cites TODO-07 as unresolved.
- **Dependencies:** `T-11.3.3.1`

**T-11.3.3.3 — Suppress duplicate enqueue on receptionist re-submission** · `P0` · `1 pt` · deps: `T-11.3.3.1`
- **Description:** Guard in the credential request use case that detects an in-flight `pending`/`failed` outbox row for the same credential and returns the existing one.
- **Acceptance Criteria:** Repeated submission yields one `vms.acs_requests` row; integration test drives three rapid submissions under simulator unavailability and asserts a single row.
- **Dependencies:** `T-11.3.3.1`

---

### F-11.4 — Dead-letter handling & operator alerting

Requests that exhaust retries are preserved, alerted on, and replayable — never silently discarded.

**Provenance:** `SRS` FR-API-01 (SRS B1) · `SRS-NFR` NFR-REL-01 (SRS B1) · **Priority:** P0 · **Points:** 11 · **Stories:** 3

#### US-11.4.1 — Dead-letter a request that exhausts its retries

**As a** System Administrator **I want** an exhausted ACS request moved to a dead-letter state atomically **so that** it is never lost between removal from the queue and the record of its failure.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-API-01 (SRS B1), `SRS-NFR` NFR-REL-01 (SRS B1) |
| **Dependencies** | US-11.3.2 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a request whose `attempt_count` has reached `acs.retry.max_attempts`, **When** the final attempt fails, **Then** the `vms.acs_requests` row moves to `status = 'dead_letter'` with `last_error` preserved and `next_retry_at` cleared.
- **AC-2 — Given** the dead-letter transition, **When** it commits, **Then** the outbox update and the Kafka dead-letter topic publication happen in one logical unit, so a crash between them cannot lose the record.
- **AC-3 — Given** a dead-lettered credential request, **When** the associated credential is inspected, **Then** `vms.credentials.state` is `failed`, not left at `requested`.
- **AC-4 (negative) — Given** a process crash immediately after the final failed attempt, **When** the service restarts, **Then** the row is either still retryable or already dead-lettered — never removed from `vms.acs_requests`.

**Development Tasks**

**T-11.4.1.1 — Implement the atomic dead-letter transition** · `P0` · `2 pts` · deps: `US-11.3.2`
- **Description:** Single-transaction move to `dead_letter`, credential state to `failed`, and dead-letter topic publish via the transactional outbox pattern.
- **Acceptance Criteria:** Integration test with injected crash points between the writes leaves no lost record; `last_error` always populated on dead-letter.
- **Dependencies:** `US-11.3.2`

**T-11.4.1.2 — Configure the Kafka dead-letter topic** · `P0` · `1 pt` · deps: `T-11.4.1.1`
- **Description:** Dedicated dead-letter topic with long retention, separate from the retry topic, carrying the `vms.acs_requests` id rather than the payload.
- **Acceptance Criteria:** Topic provisioned in local and CI environments; retention documented; message carries no PII or secrets.
- **Dependencies:** `T-11.4.1.1`

#### US-11.4.2 — Inspect and replay dead-lettered ACS requests

**As a** System Administrator **I want** to see every dead-lettered ACS request and replay it after the underlying problem is fixed **so that** an outage does not require re-entering visitor data by hand.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-API-01 (SRS B1) · `ENABLER` (operability, NFR-AVL-01 SRS B1) |
| **Dependencies** | US-11.4.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** dead-lettered rows exist, **When** an administrator opens the ACS operations view, **Then** they are listed with operation, credential reference, `attempt_count`, `last_error` and age, newest first.
- **AC-2 — Given** a dead-lettered row, **When** the administrator replays it, **Then** `attempt_count` resets, `status` returns to `pending`, and the retry worker picks it up.
- **AC-3 — Given** a replay is performed, **When** it is recorded, **Then** a `vms.audit_logs` entry captures the acting user, the action, and before/after status.
- **AC-4 (negative) — Given** a user without the `settings.manage` permission, **When** they attempt a replay, **Then** the request is denied at the API boundary and the denial is logged.
- **AC-5 (negative) — Given** a dead-lettered credential whose visitor has since checked out or whose validity window has passed, **When** replay is attempted, **Then** it is refused with a clear reason rather than provisioning a credential nobody needs.

**Development Tasks**

**T-11.4.2.1 — Build the dead-letter query endpoint and projection** · `P1` · `2 pts` · deps: `US-11.4.1`
- **Description:** Paginated, filterable read model over `vms.acs_requests` where `status = 'dead_letter'`, joined to visitor and credential for context.
- **Acceptance Criteria:** Pagination and filtering by operation and date range; response contains redacted payloads only; authorised by `settings.manage`.
- **Dependencies:** `US-11.4.1`

**T-11.4.2.2 — Implement the replay use case with staleness guard** · `P1` · `2 pts` · deps: `T-11.4.2.1`
- **Description:** Reset-and-requeue use case, refusing replay when the validity window has elapsed or the visitor is `checked_out`/`cancelled`/`expired`.
- **Acceptance Criteria:** Valid replay requeues; each stale condition refuses with a distinct reason; unit and integration tests per branch.
- **Dependencies:** `T-11.4.2.1`

**T-11.4.2.3 — Audit every replay action** · `P0` · `1 pt` · deps: `T-11.4.2.2`
- **Description:** Write `vms.audit_logs` with `action = 'acs.request.replay'`, entity type/id, before/after state and IP.
- **Acceptance Criteria:** Entry written for every replay including refused attempts; user attribution present; append-only respected.
- **Dependencies:** `T-11.4.2.2`

#### US-11.4.3 — Alert operators when requests dead-letter

**As a** System Administrator **I want** an alert when ACS requests start dead-lettering **so that** I find out from monitoring rather than from a receptionist calling to say nothing works.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-API-01 (SRS B1) · `ENABLER` (NFR-AVL-01, SRS B1) |
| **Dependencies** | US-11.4.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a request dead-letters, **When** the event is published, **Then** an operator alert is raised carrying the operation, the failure reason and the affected credential reference.
- **AC-2 — Given** repeated dead-letters within a configured window, **When** alerts are generated, **Then** they are aggregated into one alert with a count rather than one alert per row.
- **AC-3 — Given** a sustained ACS outage, **When** alerting fires, **Then** the alert distinguishes "ACS unreachable" from "ACS rejecting our requests", because the operator response differs.
- **AC-4 (negative) — Given** the alert channel is itself unavailable, **When** an alert cannot be delivered, **Then** the failure is logged and the dead-letter record is unaffected.

**Development Tasks**

**T-11.4.3.1 — Implement dead-letter alert generation and aggregation** · `P1` · `2 pts` · deps: `US-11.4.1`
- **Description:** Consumer on the dead-letter topic producing aggregated operator alerts with a configurable window and threshold.
- **Acceptance Criteria:** Single dead-letter alerts once; a burst produces one aggregated alert; window and threshold configurable via `vms.system_settings`.
- **Dependencies:** `US-11.4.1`

**T-11.4.3.2 — Classify outage type in the alert** · `P2` · `1 pt` · deps: `T-11.4.3.1`
- **Description:** Use the failure taxonomy from T-11.1.1.3 to label the alert as connectivity, authentication, or rejection.
- **Acceptance Criteria:** Three classifications produced from simulator-injected conditions; classification asserted in integration tests.
- **Dependencies:** `T-11.4.3.1`

---

### F-11.5 — API request/response audit logging

Every ACS request and response is logged to `vms.acs_api_log` for audit and troubleshooting.

**Provenance:** `SRS` FR-API-02 (SRS B1) · **Priority:** P0 · **Points:** 8 · **Stories:** 2

#### US-11.5.1 — Log all ACS requests and responses

**As a** System Administrator **I want** every ACS API request and response recorded **so that** an integration dispute or a credential failure can be reconstructed from evidence.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-API-02 (SRS B1) |
| **Dependencies** | US-11.3.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** any outbound ACS call, **When** it is made, **Then** a `vms.acs_api_log` row is written with `direction = 'outbound'`, `acs_request_id` linking to `vms.acs_requests`, the payload and `logged_at`.
- **AC-2 — Given** any response, **When** it is received, **Then** a row is written with `direction = 'inbound'`, `http_status` and the response payload, correlatable to the outbound row through `acs_request_id`.
- **AC-3 — Given** an inbound ACS-initiated event, **When** it is received, **Then** it too is logged with `direction = 'inbound'` even though it has no originating `acs_requests` row.
- **AC-4 (negative) — Given** an ACS call that times out with no response at all, **When** the timeout fires, **Then** the outbound row still exists and an inbound row records the timeout with a null `http_status` — the absence of a response is itself evidence.
- **AC-5 (negative) — Given** logging itself fails, **When** the ACS call proceeds, **Then** the failure to log is raised as an alert and the call outcome is still recorded in `vms.acs_requests` — logging must not silently degrade to nothing.

**Development Tasks**

**T-11.5.1.1 — Implement the `vms.acs_api_log` writer** · `P0` · `2 pts` · deps: `US-11.3.1`
- **Description:** Append-only writer for `vms.acs_api_log` invoked from the port decorator for both directions.
- **Acceptance Criteria:** Outbound and inbound rows written; `acs_request_id` linkage correct; writer never updates or deletes existing rows.
- **Dependencies:** `US-11.3.1`

**T-11.5.1.2 — Capture timeouts and transport failures as log entries** · `P0` · `1 pt` · deps: `T-11.5.1.1`
- **Description:** Ensure no-response outcomes produce an inbound row with null `http_status` and a structured reason.
- **Acceptance Criteria:** Simulator "never responds" mode produces exactly one outbound and one inbound row; reason distinguishes timeout from connection refusal.
- **Dependencies:** `T-11.5.1.1`

**T-11.5.1.3 — Correlate log entries with the request correlation id** · `P1` · `1 pt` · deps: `T-11.5.1.1`
- **Description:** Propagate the per-request correlation id from the reception UI through the port into the log payload envelope.
- **Acceptance Criteria:** A single receptionist action is traceable end-to-end by correlation id across application logs, `vms.acs_requests` and `vms.acs_api_log`.
- **Dependencies:** `T-11.5.1.1`

**T-11.5.1.4 — Integration-test log completeness across failure modes** · `P0` · `1 pt` · deps: `T-11.5.1.2`
- **Description:** Test matrix covering success, transient failure, permanent failure, malformed response and timeout, asserting log completeness for each.
- **Acceptance Criteria:** Five scenarios each assert the expected row count and content; suite runs against the simulator in CI.
- **Dependencies:** `T-11.5.1.2`

#### US-11.5.2 — Redact secrets and personal data from ACS logs

**As a** System Administrator **I want** ACS logs to exclude authentication material and unnecessary personal data **so that** the audit trail does not itself become the security incident.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-API-02 (SRS B1), `SRS` FR-API-03 (SRS B1), `SRS-NFR` NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-11.5.1 |
| **Blocked by** | TODO-12 (no retention period defined for personal data) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a payload containing authentication material, **When** it is logged, **Then** the credential value is replaced with a fixed redaction marker and never persisted.
- **AC-2 — Given** a payload containing visitor personal data, **When** it is logged, **Then** only fields necessary for troubleshooting are retained and `id_document_ref` is redacted.
- **AC-3 — Given** the log table, **When** it is read, **Then** access requires an administrative permission and each read is itself auditable.
- **AC-4 (negative) — Given** a new field appears in an ACS response that the redaction rules do not recognise, **When** it is logged, **Then** the default is to redact unknown fields rather than to persist them.

> **Retention deliberately unset.** A purge policy for `vms.acs_api_log` cannot be written until
> TODO-12 defines a retention period. The table grows unbounded until then, and that is recorded as
> a known operational debt rather than resolved by guessing a number.

**Development Tasks**

**T-11.5.2.1 — Implement the redaction rule set with deny-by-default** · `P0` · `2 pts` · deps: `US-11.5.1`
- **Description:** Field-level redaction applied to both `vms.acs_api_log` and `vms.acs_requests` payloads, redacting any field not on an explicit allow-list.
- **Acceptance Criteria:** Known secret and PII fields redacted; unknown fields redacted by default; unit tests cover allow-listed, denied and unknown fields.
- **Dependencies:** `US-11.5.1`

**T-11.5.2.2 — Restrict and audit access to ACS log data** · `P0` · `1 pt` · deps: `T-11.5.2.1`
- **Description:** Authorise log-read endpoints behind an administrative permission and write a `vms.audit_logs` entry on each read.
- **Acceptance Criteria:** Unauthorised read denied at the API boundary; every authorised read produces an audit entry naming the user and the query.
- **Dependencies:** `T-11.5.2.1`

---

### F-11.6 — ACS service authentication

VMS authenticates to the ACS API using a credential mechanism resolved from a secrets manager, never an open endpoint.

**Provenance:** `SRS` FR-API-03 (SRS B1), `SRS-NFR` NFR-SEC-01 (SRS B1) · TDD §6.3 · **Priority:** P0 · **Points:** 8 · **Stories:** 2

#### US-11.6.1 — Authenticate every ACS call from secrets-manager-held material

**As a** System Administrator **I want** ACS authentication material resolved at runtime from the secrets manager **so that** no credential ever exists in source control, a config file, or a database column.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-API-03 (SRS B1), `SRS-NFR` NFR-SEC-01 (SRS B1) · TDD §6.3 |
| **Dependencies** | US-11.1.1, F-05.2 (Phase 1 secrets management) |
| **Blocked by** | TODO-02 (the actual ACS auth method — API key vs OAuth2 — is unknown) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** an outbound ACS call, **When** it is made, **Then** authentication material is attached, resolved through a strategy abstraction that supports at least API-key and OAuth2 client-credentials without changing any caller.
- **AC-2 — Given** the running application, **When** authentication material is needed, **Then** it is fetched from the secrets manager and held only in memory — never written to `vms.system_settings`, `vms.acs_requests`, `vms.acs_api_log` or any log line.
- **AC-3 — Given** a repository scan in CI, **When** it runs, **Then** it fails the build if anything resembling an ACS credential is committed.
- **AC-4 (negative) — Given** the secrets manager is unavailable at call time, **When** an ACS call is attempted, **Then** it fails as a transient error, is queued in `vms.acs_requests`, and the failure reason distinguishes "cannot authenticate" from "ACS unreachable".
- **AC-5 (negative) — Given** ACS rejects the presented credential, **When** the response is processed, **Then** it is classified as a permanent authentication failure, is not retried into a lockout, and raises an operator alert.

> **Stage A caveat.** The strategy abstraction and the simulator-side API-key path are fully
> buildable now. Which concrete mechanism UAL actually requires is unknown until TODO-02 lands; the
> concrete implementation of that mechanism belongs to F-11.7.

**Development Tasks**

**T-11.6.1.1 — Define the ACS authentication strategy abstraction** · `P0` · `2 pts` · deps: `US-11.1.1`
- **Description:** An adapter-internal interface for attaching authentication to an outbound call, with API-key and OAuth2 client-credentials implementations.
- **Acceptance Criteria:** Strategy selected by configuration; no caller aware of it; the abstraction lives inside the ACS adapter package and is caught by the F-11.1.2 fitness test if it leaks.
- **Dependencies:** `US-11.1.1`

**T-11.6.1.2 — Integrate secrets-manager resolution** · `P0` · `2 pts` · deps: `T-11.6.1.1`
- **Description:** Runtime resolution of ACS authentication material via the Phase 1 secrets management component, with in-memory caching and no persistence.
- **Acceptance Criteria:** No secret in source, config, or database (asserted by test); secrets-manager unavailability surfaces as a transient failure; cache has a bounded lifetime.
- **Dependencies:** `T-11.6.1.1`

**T-11.6.1.3 — Add credential-scanning to CI** · `P0` · `1 pt` · deps: `T-11.6.1.2`
- **Description:** Secret-scanning step in the CI pipeline covering source, configuration and migration files.
- **Acceptance Criteria:** Seeded fake credential fails the build; step added to required status checks; false-positive allow-list documented.
- **Dependencies:** `T-11.6.1.2`

#### US-11.6.2 — Rotate ACS authentication material without downtime

**As a** System Administrator **I want** to rotate ACS credentials without restarting VMS **so that** routine or emergency rotation does not require a maintenance window during building operating hours.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-API-03 (SRS B1) · `SRS-NFR` NFR-SEC-01 (SRS B1), NFR-AVL-01 (SRS B1) |
| **Dependencies** | US-11.6.1 |
| **Blocked by** | TODO-02 (rotation semantics depend on the ACS auth mechanism) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** the secret is rotated in the secrets manager, **When** the cached value's lifetime expires or an explicit refresh is triggered, **Then** subsequent ACS calls use the new material without a restart.
- **AC-2 — Given** an OAuth2 strategy, **When** the access token expires mid-operation, **Then** the token is refreshed transparently and the operation completes without surfacing an error to the receptionist.
- **AC-3 (negative) — Given** rotation leaves the old and new secrets both briefly valid, **When** calls are made during the overlap, **Then** none fail; and if the old secret is revoked immediately, **Then** in-flight calls fail transiently and are retried with the new material rather than dead-lettered.

**Development Tasks**

**T-11.6.2.1 — Implement secret refresh and cache invalidation** · `P2` · `2 pts` · deps: `US-11.6.1`
- **Description:** Bounded-lifetime cache with explicit administrative refresh endpoint, plus automatic refresh on an authentication-failure classification.
- **Acceptance Criteria:** Rotation picked up without restart; refresh endpoint authorised by `settings.manage` and audited; refresh storm prevented by a single-flight guard.
- **Dependencies:** `US-11.6.1`

**T-11.6.2.2 — Implement transparent OAuth2 token refresh** · `P2` · `1 pt` · deps: `T-11.6.2.1`
- **Description:** Pre-emptive refresh ahead of token expiry and one transparent retry on an expiry-driven rejection.
- **Acceptance Criteria:** Simulated mid-operation expiry completes successfully with exactly one transparent retry; no infinite refresh loop.
- **Dependencies:** `T-11.6.2.1`

---

### F-11.7 — Wire-level ACS adapter ⛔ BLOCKED

The actual translation between VMS domain types and the UAL wire format. This is the seam ADR-0002 exists to protect, and it cannot be written against a contract we do not have.

**Provenance:** `BLOCKED` TODO-02 · implements `SRS-CON` CON-01, CON-02 (SRS B1) · **Priority:** P0 (when unblocked) · **Points:** 18 · **Stories:** 3

> ⛔ **This entire feature is Stage B and is not startable.** It requires the published UAL ACS API
> contract (TODO-02) and the ACS non-production endpoint (TDD §9.3). No task below may enter a
> sprint until TODO-02 is dispositioned. Estimates are planning figures carrying real uncertainty:
> if the published contract maps poorly onto `AcsPort`, the port itself may need revision, which is
> a `BREAKING CHANGE` and a re-baseline, not a bug fix. The stories are written now so the phase has
> an honest shape and cost — not because they are ready.

#### US-11.7.1 — Implement the wire-level ACS adapter against the published contract

**As a** System Administrator **I want** the ACS adapter implemented against the real UAL contract **so that** VMS provisions credentials on the actual access control system rather than a simulation of it.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 8 |
| **Provenance** | `BLOCKED` TODO-02 · `SRS-CON` CON-01, CON-02 (SRS B1) |
| **Dependencies** | US-11.1.1, US-11.6.1 |
| **Blocked by** | **TODO-02** — published UAL ACS API contract |
| **ACS Stage** | B (real contract) |

**Acceptance Criteria**
- **AC-1 — Given** the published UAL contract, **When** the adapter is implemented, **Then** every `AcsPort` operation is fulfilled against real endpoints and no ACS DTO, enum, error code or vocabulary appears outside the adapter package.
- **AC-2 — Given** a credential creation response, **When** it is translated, **Then** the ACS credential reference and QR payload are mapped into `vms.credentials.acs_credential_id` and `qr_payload` with no ACS-shaped field surviving the translation.
- **AC-3 — Given** the contract's error codes, **When** a call fails, **Then** each code is mapped explicitly onto the VMS failure taxonomy from T-11.1.1.3, with retryability decided by VMS rules.
- **AC-4 (negative) — Given** an ACS error code the mapping does not recognise, **When** it is received, **Then** it is classified as a permanent failure and alerted — never defaulted to retryable, which would produce an unbounded retry loop against a rejection.
- **AC-5 (negative) — Given** the contract's idempotency semantics differ from our assumption, **When** a retried create is sent, **Then** the adapter honours the contract's idempotency key mechanism and does not create a second credential for one visitor, preserving `ux_credentials_active_per_visitor`.

**Development Tasks**

**T-11.7.1.1 — Review the published contract against `AcsPort` and record the gap** · `P0` · `2 pts` · deps: `TODO-02`
- **Description:** Formal comparison of the UAL contract to the port; produce a gap analysis and, if a port revision is needed, an ADR superseding the relevant part of ADR-0002.
- **Acceptance Criteria:** Every port operation mapped or flagged; gaps raised as issues; any port change proposed as a `BREAKING CHANGE` with migration notes.
- **Dependencies:** TODO-02

**T-11.7.1.2 — Implement request translation and HTTP client** · `P0` · `3 pts` · deps: `T-11.7.1.1`
- **Description:** Adapter-side translation from VMS value objects to the wire format, with timeouts, connection pooling and the F-11.6 authentication strategy.
- **Acceptance Criteria:** All operations implemented; timeouts configured per operation; fitness test still passes; no ACS type outside the adapter package.
- **Dependencies:** `T-11.7.1.1`

**T-11.7.1.3 — Implement response translation and error-code mapping** · `P0` · `2 pts` · deps: `T-11.7.1.2`
- **Description:** Wire-to-domain translation, explicit error-code mapping table, and deny-by-default classification of unknown codes.
- **Acceptance Criteria:** Every documented code mapped; unknown code classified permanent and alerted; mapping table is data, not scattered conditionals.
- **Dependencies:** `T-11.7.1.2`

**T-11.7.1.4 — Honour the contract's idempotency mechanism on retry** · `P0` · `1 pt` · deps: `T-11.7.1.3`
- **Description:** Attach the contract's idempotency key, derived from the `vms.acs_requests` id, to every retryable operation.
- **Acceptance Criteria:** A retried create against the non-production endpoint produces exactly one ACS credential; verified against the real endpoint, not the simulator.
- **Dependencies:** `T-11.7.1.3`

#### US-11.7.2 — Consumer-driven contract tests against the ACS contract

**As a** developer **I want** contract tests that fail when ACS changes its API **so that** a vendor-side change is caught in CI rather than at a reception desk.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` TODO-02 · `SRS-NFR` NFR-MNT-01 (SRS B1) |
| **Dependencies** | US-11.7.1 |
| **Blocked by** | **TODO-02** |
| **ACS Stage** | B (real contract) |

**Acceptance Criteria**
- **AC-1 — Given** the published contract, **When** contract tests run, **Then** they assert the adapter's expectations of every endpoint it consumes, per the workflow document's Contract test level.
- **AC-2 — Given** the contract tests pass, **When** the simulator is run against the same expectations, **Then** the simulator is confirmed to satisfy them — closing ADR-0002's "simulator drift" risk.
- **AC-3 (negative) — Given** ACS changes a field name or an error code, **When** contract tests run, **Then** they fail with a message naming the specific divergence.

**Development Tasks**

**T-11.7.2.1 — Author consumer-driven contract tests** · `P1` · `3 pts` · deps: `US-11.7.1`
- **Description:** Contract test suite covering every consumed endpoint, request shape, success shape and documented error shape.
- **Acceptance Criteria:** Suite added to the required CI checks; divergence messages name the field or code; runs without the live endpoint using recorded pacts.
- **Dependencies:** `US-11.7.1`

**T-11.7.2.2 — Verify the simulator against the same contract expectations** · `P1` · `2 pts` · deps: `T-11.7.2.1`
- **Description:** Run the contract expectations against the F-11.2 simulator and correct the simulator where it diverges.
- **Acceptance Criteria:** Simulator satisfies every expectation; divergences fixed or documented as deliberate; a drift-detection job runs in CI.
- **Dependencies:** `T-11.7.2.1`

#### US-11.7.3 — Verify Stage A workflows against the ACS non-production endpoint

**As a** Technical Product Manager **I want** every Stage A workflow re-executed against the real ACS test endpoint **so that** requirements can be reported as verified rather than merely "done against simulator".

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` TODO-02 · ADR-0002 "honest limitation" · TDD §9.3 |
| **Dependencies** | US-11.7.1, US-11.7.2 |
| **Blocked by** | **TODO-02** (contract and non-production endpoint), TODO-07 (NFR-PRF-01 target) |
| **ACS Stage** | B (real contract) |

**Acceptance Criteria**
- **AC-1 — Given** the ACS non-production endpoint, **When** the Stage A end-to-end journey (request → approve → issue → enter → exit → card return) is executed against it, **Then** every step succeeds and the resulting VMS state matches the simulator-verified expectation.
- **AC-2 — Given** a successful end-to-end run, **When** the traceability matrix is updated, **Then** each affected requirement moves from "done against simulator" to **verified**, with the run recorded.
- **AC-3 (negative) — Given** real ACS behaviour diverges from the simulator, **When** divergence is found, **Then** it is raised as a defect against the simulator as well as the adapter, so the simulator stops lying to us.
- **AC-4 (negative) — Given** NFR-PRF-01 has no agreed numeric target, **When** performance is measured, **Then** the measurement is recorded as an observation and no pass/fail claim is made until TODO-07 resolves.

**Development Tasks**

**T-11.7.3.1 — Build the end-to-end verification harness against the non-production endpoint** · `P0` · `3 pts` · deps: `US-11.7.1`
- **Description:** Automated harness driving the full journey against the ACS test endpoint, with environment isolation and test-data cleanup.
- **Acceptance Criteria:** Harness runs on demand and nightly; state asserted at each step; no test data left behind on ACS.
- **Dependencies:** `US-11.7.1`

**T-11.7.3.2 — Record measured latency as observations pending TODO-07** · `P1` · `1 pt` · deps: `T-11.7.3.1`
- **Description:** Capture p50/p95/p99 for credential creation end-to-end at the reception UI; report as observation only.
- **Acceptance Criteria:** Percentiles recorded per run; report explicitly states no target exists (TODO-07); no pass/fail gate applied.
- **Dependencies:** `T-11.7.3.1`

**T-11.7.3.3 — Convert simulator-verified stories to verified in the traceability matrix** · `P0` · `1 pt` · deps: `T-11.7.3.1`
- **Description:** Update the traceability matrix delivery log for every Stage A story, changing its verification status and citing the harness run.
- **Acceptance Criteria:** Every Stage A story reviewed; those that pass are marked verified; those that fail are reopened with a defect linked.
- **Dependencies:** `T-11.7.3.1`

---

### F-11.8 — Inbound event consumption & idempotency

Access, exit and card events from ACS are consumed exactly once, keyed on the `vms.access_events.acs_event_id` unique constraint.

**Provenance:** `TDD-DERIVED` TDD §6.2 · supports `SRS` FR-NOT-02 (SRS B1), FR-REP-01 (SRS B1) · **Priority:** P0 · **Points:** 13 · **Stories:** 3

> ⚠️ `TDD-DERIVED`: the inbound event mechanism is a TDD design element. The *need* for it is
> SRS-backed — FR-NOT-02 (SRS B1) requires VMS to receive exit events, and FR-CRD-02 (SRS B1)
> requires card return status from ACS — but the delivery mechanism (push/webhook vs poll) is
> undefined until TODO-02. The stories below are written mechanism-agnostic behind the port.

#### US-11.8.1 — Receive inbound ACS events through the port

**As a** System Administrator **I want** inbound ACS events received through the same anti-corruption boundary as outbound calls **so that** the delivery mechanism can change without touching VMS workflow logic.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §6.2 · `SRS-NFR` NFR-MNT-01 (SRS B1) |
| **Dependencies** | US-11.1.1, US-11.2.3 |
| **Blocked by** | TODO-02 (push vs poll delivery mechanism unknown) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** an inbound ACS event, **When** it arrives, **Then** it is translated into a VMS-shaped event at the adapter and no ACS type reaches the application layer.
- **AC-2 — Given** the inbound handler, **When** it processes an event, **Then** it accepts events from either a push endpoint or a poll loop through the same application-layer entry point.
- **AC-3 — Given** an inbound event, **When** it is received, **Then** it is logged to `vms.acs_api_log` with `direction = 'inbound'` per FR-API-02 (SRS B1).
- **AC-4 (negative) — Given** a structurally malformed inbound event, **When** it is received, **Then** it is rejected, logged with its raw form redacted, and routed to the dead-letter path — it never produces a partial `vms.access_events` row.
- **AC-5 (negative) — Given** an inbound event referencing an `acs_credential_id` VMS does not know, **When** it is processed, **Then** the event is still persisted with null `visitor_id` and `credential_id` rather than discarded, and a reconciliation flag is raised for F-11.9.

**Development Tasks**

**T-11.8.1.1 — Define the inbound event port and application-layer handler** · `P0` · `2 pts` · deps: `US-11.1.1`
- **Description:** Application-layer inbound handler taking VMS-shaped events, with the adapter responsible for translation regardless of transport.
- **Acceptance Criteria:** One entry point for all inbound events; transport-agnostic; fitness test confirms no ACS type crosses the boundary.
- **Dependencies:** `US-11.1.1`

**T-11.8.1.2 — Implement a push receiver and a poll loop behind the adapter** · `P0` · `2 pts` · deps: `T-11.8.1.1`
- **Description:** Both transports implemented adapter-side, selectable by configuration, so whichever mechanism TODO-02 reveals is already supported.
- **Acceptance Criteria:** Both transports drive the same handler; selectable by configuration; push receiver authenticated; poll loop resumable from a checkpoint.
- **Dependencies:** `T-11.8.1.1`

**T-11.8.1.3 — Handle malformed and unknown-credential events** · `P0` · `1 pt` · deps: `T-11.8.1.2`
- **Description:** Validation and quarantine path for malformed events; orphan-tolerant persistence for events referencing unknown credentials.
- **Acceptance Criteria:** Malformed event quarantined with no `vms.access_events` write; unknown-credential event persisted with null foreign keys and flagged for reconciliation.
- **Dependencies:** `T-11.8.1.2`

#### US-11.8.2 — Process inbound events idempotently on `acs_event_id`

**As a** System Administrator **I want** a redelivered ACS event to have no additional effect **so that** at-least-once delivery does not produce duplicate visits, duplicate exit alerts, or a corrupted reconciliation count.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §6.2 · Schema `vms.access_events.acs_event_id UNIQUE` · ADR-0002 decision 5 |
| **Dependencies** | US-11.8.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** an inbound event with a new `acs_event_id`, **When** it is processed, **Then** exactly one `vms.access_events` row is written and exactly one downstream domain event is published.
- **AC-2 — Given** an event with an `acs_event_id` already present, **When** it is redelivered, **Then** the insert conflict is detected, the event is acknowledged as already processed, and **no** second domain event is published.
- **AC-3 — Given** duplicate detection, **When** it occurs, **Then** it is recorded as a metric and a debug-level log line, not as an error — redelivery is normal, not exceptional.
- **AC-4 (negative) — Given** two instances process the same event concurrently, **When** both attempt the insert, **Then** the `acs_event_id` unique constraint causes exactly one to succeed and the other to take the already-processed path without failing the delivery.
- **AC-5 (negative) — Given** an event whose `event_time` is in the future relative to VMS server time (clock skew), **When** it is processed, **Then** it is persisted with the ACS-reported `event_time` unchanged, the skew is recorded, and no validity-window decision is made on the basis of VMS local time alone.

**Development Tasks**

**T-11.8.2.1 — Implement conflict-tolerant persistence on `acs_event_id`** · `P0` · `2 pts` · deps: `US-11.8.1`
- **Description:** Insert into `vms.access_events` relying on the `acs_event_id` unique constraint, treating a conflict as the already-processed signal rather than an error.
- **Acceptance Criteria:** Duplicate insert produces no exception to the caller and no second row; concurrent-insert integration test with two threads asserts exactly one row.
- **Dependencies:** `US-11.8.1`

**T-11.8.2.2 — Publish downstream domain events exactly once** · `P0` · `2 pts` · deps: `T-11.8.2.1`
- **Description:** Publish the VMS domain event to Kafka only on a genuinely new row, using the transactional outbox pattern so persistence and publication cannot diverge.
- **Acceptance Criteria:** Redelivery produces no second publication; crash between write and publish is recovered without duplicate publication; consumers observe exactly one event.
- **Dependencies:** `T-11.8.2.1`

**T-11.8.2.3 — Record and surface clock skew** · `P1` · `1 pt` · deps: `T-11.8.2.1`
- **Description:** Compare ACS `event_time` to VMS receive time, record the delta as a metric, and alert beyond a configurable threshold.
- **Acceptance Criteria:** Skew metric emitted per event; threshold breach alerts; ACS-reported `event_time` is always stored verbatim.
- **Dependencies:** `T-11.8.2.1`

#### US-11.8.3 — Publish normalised access events onto the VMS event stream

**As a** developer **I want** normalised entry and exit events on Kafka **so that** the entry lifecycle, notifications and reporting consume one contract instead of each reaching into ACS.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` TDD §6.2, §3 · supports `SRS` FR-NOT-02 (SRS B1), FR-REP-01 (SRS B1) |
| **Dependencies** | US-11.8.2 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a new access event, **When** it is persisted, **Then** a normalised domain event carrying visitor reference, credential reference, direction, `gate_ref` and `event_time` is published to the access-event topic.
- **AC-2 — Given** the published event, **When** a consumer reads it, **Then** it needs no ACS-specific knowledge to interpret it.
- **AC-3 (negative) — Given** the Kafka broker is unavailable, **When** an event is persisted, **Then** the `vms.access_events` row is still written and publication is retried from the outbox — the event is not lost.

**Development Tasks**

**T-11.8.3.1 — Define and version the normalised access-event schema** · `P1` · `1 pt` · deps: `US-11.8.2`
- **Description:** Versioned event schema in VMS vocabulary, registered and documented for downstream consumers.
- **Acceptance Criteria:** Schema versioned and backward compatible; no ACS field names; documented in the integration reference.
- **Dependencies:** `US-11.8.2`

**T-11.8.3.2 — Implement the publisher with broker-outage tolerance** · `P1` · `2 pts` · deps: `T-11.8.3.1`
- **Description:** Transactional-outbox-backed publisher with retry when the broker is unavailable.
- **Acceptance Criteria:** Broker-down integration test proves no event loss and eventual publication; no duplicate publication after recovery.
- **Dependencies:** `T-11.8.3.1`

---

### F-11.9 — Periodic VMS↔ACS state synchronisation

A scheduled job that detects and reports divergence between VMS credential state and ACS credential state.

**Provenance:** `TDD-DERIVED` FR-ENT-08 (TDD §4.4) · supports `SRS` FR-VMS-14 (SRS B1), FR-CRD-03 (SRS B1) · **Priority:** P1 · **Points:** 8 · **Stories:** 2

> ⚠️ `TDD-DERIVED`: FR-ENT-08 is not defined in the attached SRS (see catalogue §5, D-03). The job
> is nonetheless justified against NFR-REL-01 (SRS B1) and is the mechanism by which FR-VMS-12
> (SRS B1) can tolerate either answer to TODO-09. Held for TODO-01 disposition like all
> `TDD-DERIVED` items.

#### US-11.9.1 — Reconcile VMS credential state against ACS on a schedule

**As a** System Administrator **I want** a periodic job that compares VMS credential state to ACS **so that** silent divergence is detected rather than discovered when a visitor is refused at a barrier.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-ENT-08 (TDD §4.4) · `SRS-NFR` NFR-REL-01 (SRS B1) |
| **Dependencies** | US-11.1.1, US-14.2.1 |
| **Blocked by** | TODO-09 (whether VMS or ACS owns expiry changes what counts as divergence) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** credentials in `vms.credentials` with `state = 'active'`, **When** the sync job runs, **Then** each is queried through `queryCredentialStatus` and any mismatch between VMS and ACS state is recorded.
- **AC-2 — Given** ACS reports a credential as revoked or expired while VMS holds `active`, **When** divergence is detected, **Then** VMS updates its own state to match ACS — ACS is the system of record for physical credential state — and writes a `vms.audit_logs` entry for the correction.
- **AC-3 — Given** the job runs, **When** it completes, **Then** it records counts of checked, matched and diverged credentials as metrics.
- **AC-4 (negative) — Given** ACS is unavailable during the run, **When** the job executes, **Then** it aborts cleanly, changes no VMS state, records the failure, and retries on the next schedule — a failed sync must never be interpreted as "everything diverged".
- **AC-5 (negative) — Given** the job encounters a credential ACS has never heard of, **When** it is processed, **Then** it is flagged for operator review rather than automatically transitioned, because the cause may be a failed create rather than a revocation.

**Development Tasks**

**T-11.9.1.1 — Implement the sync job scheduler and batching** · `P1` · `2 pts` · deps: `US-11.1.1`
- **Description:** Scheduled job in the `interfaces` layer iterating active credentials in bounded batches with a rate limit, so a sync never floods ACS.
- **Acceptance Criteria:** Batch size and rate configurable; job is singleton across instances via a lock; run interval configurable in `vms.system_settings`.
- **Dependencies:** `US-11.1.1`

**T-11.9.1.2 — Implement divergence detection and correction** · `P1` · `2 pts` · deps: `T-11.9.1.1`
- **Description:** Compare ACS-reported status to `vms.credentials.state`, apply ACS-wins correction for revoked/expired, and flag unknown credentials for review.
- **Acceptance Criteria:** Each divergence class handled distinctly; correction writes `vms.audit_logs`; unknown credentials never auto-transitioned.
- **Dependencies:** `T-11.9.1.1`

**T-11.9.1.3 — Make the job abort-safe under ACS unavailability** · `P0` · `1 pt` · deps: `T-11.9.1.2`
- **Description:** Circuit-breaking abort when ACS failure rate crosses a threshold mid-run, with no partial state changes committed as corrections.
- **Acceptance Criteria:** Simulator-injected outage mid-run aborts the job; no credential state changed; failure recorded and alerted.
- **Dependencies:** `T-11.9.1.2`

#### US-11.9.2 — Report synchronisation drift to operators

**As a** System Administrator **I want** a drift report from each sync run **so that** a systematic integration problem is visible as a trend rather than as isolated incidents.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-ENT-08 (TDD §4.4) · `ENABLER` (operability) |
| **Dependencies** | US-11.9.1 |
| **Blocked by** | TODO-11 (reconciliation timing and recipient undefined) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a completed sync run, **When** the report is produced, **Then** it lists diverged credentials with VMS state, ACS state and the correction applied.
- **AC-2 — Given** repeated runs, **When** drift is viewed over time, **Then** counts per divergence class are available as a trend.
- **AC-3 (negative) — Given** drift exceeds a configured threshold in one run, **When** the report is generated, **Then** an operator alert is raised because bulk divergence indicates an integration fault, not isolated drift.

> **Recipient deliberately unset.** Who receives this report and through which channel overlaps
> TODO-11. Until it resolves, the report is available on request in the administrative view and is
> not pushed to any named recipient.

**Development Tasks**

**T-11.9.2.1 — Build the drift report projection and view** · `P2` · `2 pts` · deps: `US-11.9.1`
- **Description:** Read-model projection of sync run outcomes with per-run detail and per-class trend, exposed in the administrative view.
- **Acceptance Criteria:** Per-run detail and trend both available; authorised by `settings.manage`; read-only, writes to no other context.
- **Dependencies:** `US-11.9.1`

**T-11.9.2.2 — Alert on bulk divergence** · `P2` · `1 pt` · deps: `T-11.9.2.1`
- **Description:** Threshold-based alert when a single run's divergence count or proportion exceeds configuration.
- **Acceptance Criteria:** Threshold configurable; alert distinguishes proportion from absolute count; suppressed when the run aborted under AC-4 of US-11.9.1.
- **Dependencies:** `T-11.9.2.1`

---

## EPIC-12 — Arrival Verification & Entry

**Provenance:** `SRS` FR-VMS-04 (SRS B1), FR-VMS-15 (SRS B1) · `TDD-DERIVED` FR-ENT-06/07/10 (TDD §4.4)
**Requirement IDs:** FR-VMS-04 (SRS B1), FR-VMS-15 (SRS B1); `TDD-DERIVED` FR-ENT-06, FR-ENT-07, FR-ENT-10
**Priority:** P0 · **Story points:** 35 · **Stories:** 9 · **Tasks:** 23

The moment a visitor reaches the desk. The central receptionist looks up the pre-registered record,
confirms the visitor is appointed, and moves them into the building; entry and exit events arriving
from ACS drive the visitor's status through `checked_in` → `inside` → `checked_out` without anyone
re-keying anything; and a visitor-facing display at the desk shows the visitor their own credential
and status. FR-VMS-04 (SRS B1) is the SRS anchor and is pure VMS workflow with no ACS dependency —
which is why most of this epic is Stage N/A and can be built and demonstrated regardless of TODO-02.
The status-tracking and event-application halves depend on inbound events from F-11.8 and are
Stage A. The display (F-12.4) is under-specified by TODO-17 and stays deliberately minimal.

---

### F-12.1 — Arrival lookup & appointment confirmation

The central receptionist finds an arriving visitor's pre-registered record and confirms whether they are appointed.

**Provenance:** `SRS` FR-VMS-04 (SRS B1) · **Priority:** P0 · **Points:** 11 · **Stories:** 3

#### US-12.1.1 — Look up an arriving visitor

**As a** Central Receptionist **I want** to find an arriving visitor's pre-registered record quickly **so that** I can serve them without keeping them waiting at the desk.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-04 (SRS B1) |
| **Dependencies** | F-08.1 (Phase 2 pre-registration), F-07.4 (Phase 2 approval) |
| **Blocked by** | — |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a visitor is pre-registered, **When** the receptionist searches by name, company, host, phone or booking reference, **Then** matching `vms.visitors` records are returned with visitor name, host, tenant, appointment window and current `status`.
- **AC-2 — Given** several visitors share a name, **When** results are shown, **Then** each row carries enough distinguishing context (host, tenant, appointment time) to pick the right one without opening each.
- **AC-3 — Given** the search runs, **When** results are ordered, **Then** visitors whose appointment window includes now are ranked above those outside it, using the `idx_visitors_appt` index path.
- **AC-4 (negative) — Given** no record matches, **When** the search completes, **Then** the receptionist is offered the walk-in registration path (F-13.1) rather than an empty screen.
- **AC-5 (negative) — Given** a receptionist without permission to view visitor personal data, **When** they search, **Then** the request is denied at the API boundary per NFR-SEC-01 (SRS B1) and the denial is audited.

**Development Tasks**

**T-12.1.1.1 — Implement the arrival search query and read model** · `P0` · `2 pts` · deps: `—`
- **Description:** Read model over `vms.visitors` joined to `vms.visitor_requests`, `vms.hosts` and `vms.tenants`, supporting multi-field search with appointment-window ranking.
- **Acceptance Criteria:** Search covers all five fields; results ranked by appointment relevance; query plan uses `idx_visitors_appt` and `idx_visitors_status`; p95 measured under a 500-visitor day per NFR-SCL-01 (SRS B1).
- **Dependencies:** `—`

**T-12.1.1.2 — Build the reception arrival search UI** · `P0` · `2 pts` · deps: `T-12.1.1.1`
- **Description:** Reception-facing search screen with a single search box, typed results and a clear no-results path into walk-in registration.
- **Acceptance Criteria:** Keyboard-first operation with no mouse required for the common path (NFR-USA-01, SRS B1); WCAG 2.1 AA per the Phase 1 baseline; no-results state offers walk-in.
- **Dependencies:** `T-12.1.1.1`

**T-12.1.1.3 — Enforce authorisation and audit on visitor data access** · `P0` · `1 pt` · deps: `T-12.1.1.1`
- **Description:** Permission check on the search endpoint plus a `vms.audit_logs` entry recording who searched for what.
- **Acceptance Criteria:** Unauthorised search denied and audited; authorised search audited with the query terms and result count, not the full result payload.
- **Dependencies:** `T-12.1.1.1`

#### US-12.1.2 — Confirm the visitor is appointed

**As a** Central Receptionist **I want** VMS to tell me plainly whether this visitor is appointed right now **so that** I do not have to interpret dates and approval states myself under time pressure.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-04 (SRS B1) |
| **Dependencies** | US-12.1.1 |
| **Blocked by** | — |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a selected visitor whose request is `approved` and whose appointment window includes now, **When** the record is opened, **Then** VMS states unambiguously that the visitor is appointed and may proceed to credential issuance.
- **AC-2 — Given** a visitor whose request status is `submitted`, `rejected` or `cancelled`, **When** the record is opened, **Then** VMS states they are not appointed and names the reason, and credential issuance is not offered.
- **AC-3 — Given** a visitor arriving before their window opens, **When** the record is opened, **Then** VMS shows an early-arrival state with the window start time and does not treat it as a rejection.
- **AC-4 (negative) — Given** a visitor arriving after `appointment_to` has passed, **When** the record is opened, **Then** VMS shows the appointment as elapsed and offers no automatic extension — extending a window is a decision, not a default.
- **AC-5 (negative) — Given** the visitor's status is already `checked_in` or `inside`, **When** the record is opened, **Then** VMS warns of a possible duplicate arrival rather than silently proceeding to a second credential.

**Development Tasks**

**T-12.1.2.1 — Implement appointment confirmation evaluation** · `P0` · `2 pts` · deps: `US-12.1.1`
- **Description:** Domain service evaluating request status, `appointment_from`/`appointment_to` and `vms.visitors.status` into one explicit confirmation outcome.
- **Acceptance Criteria:** Every combination produces exactly one named outcome; pure domain logic with no framework imports; exhaustive unit tests including boundary instants.
- **Dependencies:** `US-12.1.1`

**T-12.1.2.2 — Render the confirmation outcome at reception** · `P0` · `1 pt` · deps: `T-12.1.2.1`
- **Description:** Reception UI treatment for each outcome, with issuance offered only for the appointed outcome.
- **Acceptance Criteria:** Each outcome visually distinct and stated in plain language; issuance control absent (not merely disabled) for non-appointed outcomes; duplicate-arrival warning requires acknowledgement.
- **Dependencies:** `T-12.1.2.1`

#### US-12.1.3 — Handle arrivals with no matching record

**As a** Central Receptionist **I want** a clear path when an arriving visitor has no pre-registration **so that** an unexpected arrival is handled as a defined flow rather than improvised.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-04 (SRS B1), FR-VMS-08 (SRS B1) |
| **Dependencies** | US-12.1.1 |
| **Blocked by** | — |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a search returns nothing, **When** the receptionist chooses to proceed, **Then** they are taken into walk-in registration (F-13.1) with the search terms pre-filled.
- **AC-2 — Given** a near match exists (similar name, same host), **When** results are shown, **Then** near matches are offered before the walk-in path, to avoid creating a duplicate visitor record.
- **AC-3 (negative) — Given** the receptionist proceeds to walk-in despite a near match, **When** the record is created, **Then** the potential duplicate is flagged on the record for later review rather than blocking the visitor at the desk.

**Development Tasks**

**T-12.1.3.1 — Implement near-match detection** · `P1` · `2 pts` · deps: `US-12.1.1`
- **Description:** Fuzzy matching over visitor name plus exact host or tenant match, surfaced as suggestions in the no-results state.
- **Acceptance Criteria:** Suggestions returned for realistic misspellings; threshold configurable; no suggestion when confidence is below threshold.
- **Dependencies:** `US-12.1.1`

**T-12.1.3.2 — Hand off to walk-in registration with context** · `P1` · `1 pt` · deps: `T-12.1.3.1`
- **Description:** Carry the search terms and any dismissed near match into the walk-in form, flagging possible duplication on the created record.
- **Acceptance Criteria:** Search terms pre-filled; dismissed near match recorded on the new visitor record; flag visible in later review.
- **Dependencies:** `T-12.1.3.1`

---

### F-12.2 — Check-in / check-out status tracking

The visitor's lifecycle status moves through `vms.visitor_status` as they arrive and leave.

**Provenance:** `TDD-DERIVED` FR-ENT-10 (TDD §4.4) · Schema `vms.visitors.status` · **Priority:** P1 · **Points:** 6 · **Stories:** 2

> ⚠️ `TDD-DERIVED`: FR-ENT-10 is undefined in the attached SRS (D-03). The status columns exist in
> the schema and the workflow is unusable without them, but this feature is held for TODO-01
> disposition like every `TDD-DERIVED` item.

#### US-12.2.1 — Check a visitor in

**As a** Central Receptionist **I want** to check an arriving visitor in **so that** the building has an accurate record of who is on site.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-ENT-10 (TDD §4.4) |
| **Dependencies** | US-12.1.2 |
| **Blocked by** | TODO-01 |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** an appointed visitor, **When** the receptionist checks them in, **Then** `vms.visitors.status` moves to `checked_in`, `checked_in_at` is set to the server timestamp, and a domain event is published.
- **AC-2 — Given** the check-in commits, **When** it is recorded, **Then** a `vms.audit_logs` entry attributes it to the acting user.
- **AC-3 (negative) — Given** a visitor already in status `checked_in`, `inside` or `checked_out`, **When** check-in is attempted again, **Then** the transition is refused with the current status stated, and `checked_in_at` is not overwritten.
- **AC-4 (negative) — Given** a visitor whose request is not `approved`, **When** check-in is attempted, **Then** it is refused — check-in is not a route around approval.

**Development Tasks**

**T-12.2.1.1 — Implement the visitor status state machine** · `P1` · `2 pts` · deps: `US-12.1.2`
- **Description:** Domain state machine over `vms.visitor_status` defining every legal transition and rejecting the rest; the single authority for visitor status changes.
- **Acceptance Criteria:** Legal transitions enumerated; illegal transitions rejected with a named reason; unit tests cover the full transition matrix including `no_show` and `expired` as schedule-driven states.
- **Dependencies:** `US-12.1.2`

**T-12.2.1.2 — Implement the check-in use case with audit and event** · `P1` · `1 pt` · deps: `T-12.2.1.1`
- **Description:** Application use case applying the transition, setting `checked_in_at`, writing `vms.audit_logs` and publishing the domain event in one transaction.
- **Acceptance Criteria:** Single-transaction commit; duplicate check-in refused; event published exactly once via the transactional outbox.
- **Dependencies:** `T-12.2.1.1`

**T-12.2.1.3 — Build the check-in control at reception** · `P1` · `1 pt` · deps: `T-12.2.1.2`
- **Description:** Reception UI check-in action on the confirmed-visitor view, showing the resulting status and refusing states inline.
- **Acceptance Criteria:** Action available only for the appointed outcome; refusal reasons rendered in plain language; keyboard-operable per NFR-USA-01 (SRS B1).
- **Dependencies:** `T-12.2.1.2`

#### US-12.2.2 — Check a visitor out

**As a** Central Receptionist **I want** a visitor's departure recorded **so that** the on-site roll is accurate and their card can be reconciled.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-ENT-10 (TDD §4.4) · supports `SRS` FR-CRD-03 (SRS B1) |
| **Dependencies** | US-12.2.1 |
| **Blocked by** | TODO-01 |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a visitor in status `checked_in` or `inside`, **When** they are checked out, **Then** `status` becomes `checked_out`, `checked_out_at` is set, and a domain event is published for downstream exit alerting.
- **AC-2 — Given** check-out occurs, **When** the visitor holds an unreturned RFID card, **Then** the outstanding card is surfaced to the receptionist at that moment (F-15.2), because check-out is the last opportunity to recover it.
- **AC-3 (negative) — Given** a visitor already `checked_out`, **When** check-out is attempted again, **Then** it is refused and `checked_out_at` is preserved.
- **AC-4 (negative) — Given** a manual check-out and an ACS exit event for the same visitor arrive close together, **When** both are processed, **Then** the status settles at `checked_out` once, and exactly one exit domain event is published.

**Development Tasks**

**T-12.2.2.1 — Implement the check-out use case** · `P1` · `2 pts` · deps: `US-12.2.1`
- **Description:** Use case applying the `checked_out` transition, setting `checked_out_at`, auditing and publishing the exit domain event.
- **Acceptance Criteria:** Transition enforced by the state machine; idempotent on repeat; exit event published exactly once.
- **Dependencies:** `US-12.2.1`

**T-12.2.2.2 — Surface outstanding cards at check-out** · `P1` · `1 pt` · deps: `T-12.2.2.1`
- **Description:** Query `vms.card_issuances` for rows with null `returned_at` for the visitor and present them during check-out, using `idx_cards_open`.
- **Acceptance Criteria:** Outstanding cards listed with `acs_card_id` and `issued_at`; check-out is not blocked by an unreturned card but the discrepancy is recorded for F-15.4.
- **Dependencies:** `T-12.2.2.1`

---

### F-12.3 — Access & exit event processing

Entry and exit events from ACS drive the visitor lifecycle without manual intervention.

**Provenance:** `TDD-DERIVED` FR-ENT-06, FR-ENT-07 (TDD §4.4) · supports `SRS` FR-NOT-02 (SRS B1) · **Priority:** P1 · **Points:** 10 · **Stories:** 2

#### US-12.3.1 — Apply an ACS entry event to the visitor lifecycle

**As a** Host **I want** my visitor's status to update when they actually pass the barrier **so that** I know they are in the building without reception telling me.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-ENT-06 (TDD §4.4) |
| **Dependencies** | US-11.8.2, US-12.2.1 |
| **Blocked by** | TODO-01 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a persisted access event with `direction = 'entry'` resolvable to a visitor, **When** it is applied, **Then** `vms.visitors.status` moves to `inside` and a domain event is published for host notification.
- **AC-2 — Given** the visitor was in status `approved` and never manually checked in, **When** an entry event arrives, **Then** the visitor moves to `inside` and `checked_in_at` is set from the event's `event_time`, not from VMS local time.
- **AC-3 — Given** an entry event, **When** it is applied, **Then** the `vms.access_events` row is linked to both `visitor_id` and `credential_id` so reporting can join without inferring.
- **AC-4 (negative) — Given** a redelivered entry event, **When** it is processed, **Then** the idempotency guard in US-11.8.2 prevents a second status transition and a second host notification.
- **AC-5 (negative) — Given** an entry event for a visitor already `checked_out`, **When** it is applied, **Then** the transition is refused, the event is still persisted for audit, and the anomaly is flagged for operator review — the event is evidence even when it is unexpected.

**Development Tasks**

**T-12.3.1.1 — Resolve inbound events to visitor and credential** · `P1` · `2 pts` · deps: `US-11.8.2`
- **Description:** Resolution from the event's `acs_credential_id` to `vms.credentials` via `idx_credentials_acsid`, then to `vms.visitors`, populating the foreign keys on `vms.access_events`.
- **Acceptance Criteria:** Resolution succeeds for known credentials; unresolvable events retain null foreign keys and are flagged; resolution is a single indexed lookup.
- **Dependencies:** `US-11.8.2`

**T-12.3.1.2 — Apply the entry transition through the state machine** · `P1` · `2 pts` · deps: `T-12.3.1.1`
- **Description:** Consumer applying `inside` via the US-12.2.1 state machine, deriving timestamps from ACS `event_time`.
- **Acceptance Criteria:** Transition applied only when legal; timestamps sourced from the event; illegal transitions flagged rather than forced.
- **Dependencies:** `T-12.3.1.1`

**T-12.3.1.3 — Flag anomalous entry events for review** · `P2` · `1 pt` · deps: `T-12.3.1.2`
- **Description:** Anomaly record for entry events that cannot be legally applied, surfaced in the administrative view.
- **Acceptance Criteria:** Each anomaly class recorded distinctly; event still persisted; anomalies visible without querying the database directly.
- **Dependencies:** `T-12.3.1.2`

#### US-12.3.2 — Apply an ACS exit event to the visitor lifecycle

**As a** Host **I want** my visitor's departure recorded from the actual exit **so that** the exit alert required by FR-NOT-02 (SRS B1) reflects reality.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-ENT-07 (TDD §4.4) · supports `SRS` FR-NOT-02 (SRS B1) |
| **Dependencies** | US-12.3.1 |
| **Blocked by** | TODO-01 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** an access event with `direction = 'exit'` resolvable to a visitor, **When** it is applied, **Then** `vms.visitors.status` becomes `checked_out`, `checked_out_at` is set from `event_time`, and an exit domain event is published for Phase 4 alerting.
- **AC-2 — Given** a one-time-restriction credential, **When** the exit event is applied, **Then** the credential is eligible for deactivation via F-14.1 and the eligibility is recorded.
- **AC-3 (negative) — Given** an exit event arrives before any entry event for that visitor (out-of-order delivery), **When** it is applied, **Then** the exit is persisted, the missing entry is flagged as an anomaly, and the visitor is not left permanently in `inside`.
- **AC-4 (negative) — Given** an exit event whose `event_time` precedes the visitor's `checked_in_at`, **When** it is applied, **Then** the inconsistency is flagged and the ACS-reported times are stored verbatim without correction.

**Development Tasks**

**T-12.3.2.1 — Apply the exit transition and publish the exit event** · `P1` · `2 pts` · deps: `US-12.3.1`
- **Description:** Consumer applying `checked_out` via the state machine and publishing the exit domain event consumed by Phase 4 notifications.
- **Acceptance Criteria:** Transition legal-only; timestamps from `event_time`; exactly one exit event published per `acs_event_id`.
- **Dependencies:** `US-12.3.1`

**T-12.3.2.2 — Handle out-of-order and inconsistent-time exits** · `P1` · `2 pts` · deps: `T-12.3.2.1`
- **Description:** Tolerant handling for exit-before-entry and exit-before-check-in, with anomaly flagging and no timestamp rewriting.
- **Acceptance Criteria:** Both cases covered by integration tests using the simulator's out-of-order emission (T-11.2.3.2); ACS times stored verbatim; visitor never stranded in `inside`.
- **Dependencies:** `T-12.3.2.1`

**T-12.3.2.3 — Mark one-time credentials eligible for deactivation on exit** · `P2` · `1 pt` · deps: `T-12.3.2.1`
- **Description:** For `restriction = 'one_time'`, record deactivation eligibility for F-14.1 to act on.
- **Acceptance Criteria:** Eligibility recorded only for one-time credentials; time-bound credentials unaffected; no ACS call made from this path.
- **Dependencies:** `T-12.3.2.1`

---

### F-12.4 — Visitor-facing reception display

A "slave" display at the reception desk showing the visitor their QR code and current status.

**Provenance:** `SRS` FR-VMS-15 (SRS B1) · ⚠️ **TODO-17** · **Priority:** P2 · **Points:** 8 · **Stories:** 2

> ⚠️ **Under-specified.** FR-VMS-15 (SRS B1) states the requirement but TODO-17 leaves the physical
> and interaction model undefined: dedicated hardware or a second monitor, browser-based or native,
> how it pairs to a reception workstation, what it shows when idle, and how it is secured against a
> visitor interacting with it. The stories below implement the narrowest defensible reading — a
> browser-based, read-only, paired display — and explicitly do not decide the hardware question.

#### US-12.4.1 — Show the visitor their credential and status on the reception display

**As a** Visitor **I want** to see my own QR code and status on a screen at the desk **so that** I can capture my pass without the receptionist turning their monitor towards me.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-15 (SRS B1) |
| **Dependencies** | F-09.5 (Phase 2 QR rendering), US-12.1.2 |
| **Blocked by** | **TODO-17** (physical and interaction model undefined) |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a reception workstation with a paired display, **When** a credential is issued to the visitor in front of it, **Then** the display shows that visitor's QR code and current status as returned by VMS.
- **AC-2 — Given** the display is showing a visitor, **When** the receptionist moves to the next visitor, **Then** the display switches within a short bounded interval and never shows two visitors' data at once.
- **AC-3 — Given** the display is idle, **When** no visitor is active, **Then** it shows a neutral holding screen containing no personal data.
- **AC-4 (negative) — Given** the display's connection to the server drops, **When** it reconnects, **Then** it returns to the idle screen rather than resuming stale visitor data.
- **AC-5 (negative) — Given** a credential request that has failed or is still queued (US-11.3.3), **When** the display renders, **Then** it shows a neutral "please wait" state and never a partial or fabricated QR code.

> **Conservative by design.** Pairing is by a workstation-scoped token; the hardware form factor is
> not decided here. Any richer interaction model waits for TODO-17.

**Development Tasks**

**T-12.4.1.1 — Implement display pairing to a reception workstation** · `P2` · `2 pts` · deps: `—`
- **Description:** Pairing mechanism binding a display session to a `vms.receptions` workstation via a short-lived, revocable token issued by an authenticated receptionist.
- **Acceptance Criteria:** Display cannot subscribe without a valid pairing; token revocable and expiring; unpaired display shows only the idle screen.
- **Dependencies:** `—`

**T-12.4.1.2 — Implement the read-only display surface and live update channel** · `P2` · `2 pts` · deps: `T-12.4.1.1`
- **Description:** Browser-based read-only view subscribing to the paired workstation's current-visitor state, rendering QR, visitor name and status.
- **Acceptance Criteria:** Switches within the bounded interval; never renders two visitors; reconnect returns to idle; no interactive control present in the DOM.
- **Dependencies:** `T-12.4.1.1`

**T-12.4.1.3 — Implement idle and pending states** · `P2` · `1 pt` · deps: `T-12.4.1.2`
- **Description:** Neutral idle screen with no personal data, and a pending state for queued or failed credential requests.
- **Acceptance Criteria:** Idle screen contains no visitor data; pending state renders no QR; both states covered by integration tests including the simulator-unavailable case.
- **Dependencies:** `T-12.4.1.2`

#### US-12.4.2 — Secure the visitor-facing display against interaction and data exposure

**As a** System Administrator **I want** the visitor display to be unusable as an entry point into VMS **so that** a public-facing screen does not become an access control failure.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-15 (SRS B1) · `SRS-NFR` NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-12.4.1 |
| **Blocked by** | **TODO-17** (securing against visitor interaction is explicitly an open question) |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** the display session, **When** its permissions are inspected, **Then** it holds read access to exactly one workstation's current-visitor projection and nothing else.
- **AC-2 — Given** a visitor interacts with the screen, **When** any input is attempted, **Then** no navigation, no data entry and no access to other visitors' data is possible.
- **AC-3 — Given** the display shows a visitor, **When** the visitor leaves the desk, **Then** the display clears after a configurable inactivity interval without receptionist action.
- **AC-4 (negative) — Given** someone obtains a display pairing token, **When** they use it from another device, **Then** it grants only the same read-only single-workstation projection and its use is audited.

**Development Tasks**

**T-12.4.2.1 — Scope and harden the display session** · `P2` · `2 pts` · deps: `US-12.4.1`
- **Description:** Minimal-privilege session scoped to one workstation projection, with navigation and input suppressed and a restrictive content security policy.
- **Acceptance Criteria:** Session cannot read any other endpoint (asserted by test); no interactive elements; token use audited to `vms.audit_logs`.
- **Dependencies:** `US-12.4.1`

**T-12.4.2.2 — Implement inactivity auto-clear** · `P2` · `1 pt` · deps: `T-12.4.2.1`
- **Description:** Configurable inactivity timer returning the display to idle.
- **Acceptance Criteria:** Interval configurable in `vms.system_settings`; timer resets on visitor change; clearing requires no receptionist action.
- **Dependencies:** `T-12.4.2.1`

---

## EPIC-13 — Walk-in & Express Entry

**Provenance:** `SRS` FR-VMS-08 (SRS B1), FR-VMS-09 (SRS B1) · `BLOCKED` FR-VMS-10 (SRS B1) / TODO-03
**Requirement IDs:** FR-VMS-08 (SRS B1), FR-VMS-09 (SRS B1), FR-VMS-10 (SRS B1 — gated)
**Priority:** P1 · **Story points:** 32 · **Stories:** 8 · **Tasks:** 21

The unscheduled visitor. FR-VMS-08 (SRS B1) requires a walk-in to report to reception, where
approval is confirmed from the host or FM Admin before a credential is requested; FR-VMS-09 (SRS B1)
requires that credential to come through the same ACS API path as a pre-scheduled visitor — which is
precisely why F-13.3 is a thin composition over EPIC-11 rather than a second integration. The
approval half is constrained by TODO-18: nobody has said whether "approval is confirmed" means an
in-app approval action, a phone call the receptionist records, or either, and those are a workflow
and a checkbox respectively. F-13.4 (express entry) is gated on TODO-03 and is the phase's most
likely descope.

---

### F-13.1 — Walk-in visitor registration

Reception registers an unscheduled visitor who has no pre-registration record.

**Provenance:** `SRS` FR-VMS-08 (SRS B1) · **Priority:** P1 · **Points:** 8 · **Stories:** 2

#### US-13.1.1 — Register a walk-in visitor at reception

**As a** Central Receptionist **I want** to register an unscheduled visitor at the desk **so that** someone who arrives without an appointment can still be processed through the same controlled workflow.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-08 (SRS B1) |
| **Dependencies** | US-12.1.3, F-04.3 (Phase 1 tenant master data), F-10.1 (Phase 2 host directory) |
| **Blocked by** | — |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** an unscheduled visitor, **When** the receptionist registers them, **Then** a `vms.visitor_requests` row is created with `visit_kind = 'walk_in'` and a `vms.visitors` row with the visitor's details and `status = 'pending'`.
- **AC-2 — Given** the walk-in form, **When** it is submitted, **Then** it requires the visitor's name, the tenant being visited and the host, because a walk-in with no host cannot be approved by anyone.
- **AC-3 — Given** registration completes, **When** the record is created, **Then** an appointment window is set from the pass type's `default_valid_hours` and can be shortened but not silently extended.
- **AC-4 — Given** registration, **When** it commits, **Then** a `vms.audit_logs` entry attributes the creation to the acting receptionist.
- **AC-5 (negative) — Given** the named host does not exist in the host directory for that tenant, **When** the form is submitted, **Then** it is rejected with a clear message rather than creating an orphan host record on the fly.
- **AC-6 (negative) — Given** a walk-in registration is created, **When** its status is inspected, **Then** it is `pending` and **not** eligible for credential issuance until F-13.2 records an approval — registration is not approval.

**Development Tasks**

**T-13.1.1.1 — Implement the walk-in registration use case** · `P1` · `2 pts` · deps: `—`
- **Description:** Use case creating a `visit_kind = 'walk_in'` request plus visitor record in one transaction, deriving the appointment window from the selected pass type.
- **Acceptance Criteria:** Both rows created atomically; `visit_kind` correct; window derived from `vms.pass_types.default_valid_hours`; visitor `status = 'pending'`.
- **Dependencies:** `—`

**T-13.1.1.2 — Build the walk-in registration form** · `P1` · `2 pts` · deps: `T-13.1.1.1`
- **Description:** Reception form with tenant and host selection from master data, minimal required fields, and pre-fill from the arrival search hand-off (T-12.1.3.2).
- **Acceptance Criteria:** Required fields enforced client and server side; host list scoped to the selected tenant; pre-filled search terms populate correctly; WCAG 2.1 AA.
- **Dependencies:** `T-13.1.1.1`

**T-13.1.1.3 — Enforce host validity and audit the registration** · `P1` · `1 pt` · deps: `T-13.1.1.1`
- **Description:** Server-side validation that the host belongs to the selected tenant and is active, plus the audit entry.
- **Acceptance Criteria:** Invalid or inactive host rejected; no host auto-created; audit entry written with acting user and created entity ids.
- **Dependencies:** `T-13.1.1.1`

#### US-13.1.2 — Detect a returning or duplicate walk-in visitor

**As a** Central Receptionist **I want** to be told when a walk-in has been here before **so that** I do not create a second record for the same person every visit.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-08 (SRS B1) · `ENABLER` (data quality for FR-REP-01, SRS B1) |
| **Dependencies** | US-13.1.1 |
| **Blocked by** | TODO-12 (retention rules affect how long prior visits may be searched), TODO-13 (ID capture out of scope, so matching is name/contact only) |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** the receptionist enters a walk-in's name and contact details, **When** a prior visitor record matches, **Then** prior visits are offered so details can be reused rather than re-typed.
- **AC-2 — Given** a prior record is reused, **When** the new walk-in is created, **Then** it is a new `vms.visitors` row under a new request — history is never overwritten.
- **AC-3 (negative) — Given** the receptionist ignores the suggestion, **When** the record is created, **Then** the possible duplicate is flagged for later review and the visitor is not delayed at the desk.
- **AC-4 (negative) — Given** two different people share a common name, **When** matching runs, **Then** a name-only match is presented as a suggestion and never auto-applied.

**Development Tasks**

**T-13.1.2.1 — Implement prior-visitor matching** · `P2` · `2 pts` · deps: `US-13.1.1`
- **Description:** Matching over name plus email or phone across historical `vms.visitors`, returning ranked suggestions with last-visit context.
- **Acceptance Criteria:** Exact contact match ranked above name-only; suggestions never auto-applied; matching respects the visitor-data authorisation rules.
- **Dependencies:** `US-13.1.1`

**T-13.1.2.2 — Reuse details without merging records** · `P2` · `1 pt` · deps: `T-13.1.2.1`
- **Description:** Copy-forward of contact details into the new record, with a duplicate flag when a suggestion is dismissed.
- **Acceptance Criteria:** New row always created; no historical row mutated; dismissal recorded as a flag.
- **Dependencies:** `T-13.1.2.1`

**T-13.1.2.3 — Surface flagged duplicates for later review** · `P3` · `1 pt` · deps: `T-13.1.2.2`
- **Description:** Administrative view listing visitor records flagged as possible duplicates, for offline data-quality review.
- **Acceptance Criteria:** Flagged records listed with both candidates; review is advisory only and performs no automatic merge; authorised by an FM Admin permission.
- **Dependencies:** `T-13.1.2.2`

---

### F-13.2 — Host / FM Admin approval confirmation

Approval is confirmed from the host or FM Admin before a walk-in credential is requested.

**Provenance:** `SRS` FR-VMS-08 (SRS B1) · ⚠️ **TODO-18** · **Priority:** P1 · **Points:** 8 · **Stories:** 2

> ⚠️ **Approval channel undefined.** FR-VMS-08 (SRS B1) says approval "is confirmed from the host or
> FM Admin" without saying how. TODO-18 lists the readings: an in-app approval action, a phone call
> the receptionist records, or either. These are materially different builds. US-13.2.1 implements
> the reading that is defensible under every interpretation — a recorded, attributed confirmation
> gate — and US-13.2.2 holds the in-app approval flow until TODO-18 resolves.

#### US-13.2.1 — Record an approval confirmation before a walk-in credential is requested

**As a** Central Receptionist **I want** to record who approved a walk-in and how **so that** no credential is issued without an attributable approval, whatever channel it came through.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-08 (SRS B1) |
| **Dependencies** | US-13.1.1 |
| **Blocked by** | **TODO-18** (approval channel undefined) |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a walk-in in status `pending`, **When** the receptionist records a confirmation, **Then** the approver identity, the approval channel and the timestamp are captured, and the request moves to `approved`.
- **AC-2 — Given** the confirmation is recorded, **When** it commits, **Then** `vms.visitor_requests.approved_by` is set where the approver is a VMS user, and a `vms.audit_logs` entry records the confirmation including the channel.
- **AC-3 — Given** the approver is a host who is not a VMS user, **When** the confirmation is recorded, **Then** the host is identified from `vms.hosts` and the record states the receptionist attested to an out-of-band confirmation.
- **AC-4 (negative) — Given** no confirmation has been recorded, **When** credential issuance is attempted for the walk-in, **Then** it is refused — the gate cannot be bypassed from the UI or the API.
- **AC-5 (negative) — Given** a receptionist attempts to record themselves as the approver, **When** the confirmation is submitted, **Then** it is refused, because self-approval defeats the purpose of the gate.

> **Conservative by design.** This story deliberately records *that* an approval occurred and who
> gave it, without asserting the channel is in-app. It is valid under every TODO-18 reading. It must
> not be extended into an approval workflow until TODO-18 is answered.

**Development Tasks**

**T-13.2.1.1 — Implement the walk-in approval confirmation use case** · `P1` · `2 pts` · deps: `US-13.1.1`
- **Description:** Use case capturing approver identity, channel and timestamp, transitioning the request to `approved`, writing `approved_by` and the audit entry.
- **Acceptance Criteria:** Transition applied via the request state machine; self-approval refused; channel captured as a constrained value, not free text.
- **Dependencies:** `US-13.1.1`

**T-13.2.1.2 — Enforce the approval gate on walk-in credential issuance** · `P0` · `1 pt` · deps: `T-13.2.1.1`
- **Description:** Server-side guard preventing credential issuance for any `walk_in` request without a recorded confirmation.
- **Acceptance Criteria:** Guard enforced in the use case, not only the UI; direct API call without approval is refused and audited; integration test proves the bypass fails.
- **Dependencies:** `T-13.2.1.1`

#### US-13.2.2 — In-app host approval request for a walk-in ⛔ HELD

**As a** Central Receptionist **I want** to request approval from the host inside VMS and see their response **so that** a walk-in approval does not depend on me making a phone call.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` TODO-18 · would implement `SRS` FR-VMS-08 (SRS B1) under one reading only |
| **Dependencies** | US-13.2.1 |
| **Blocked by** | **TODO-18** — whether an in-app approval flow is required at all |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** TODO-18 confirms an in-app approval channel is required, **When** the receptionist requests approval, **Then** the host receives an actionable approval request and their approve/reject decision transitions the request.
- **AC-2 — Given** an in-app approval is granted, **When** it commits, **Then** it satisfies the US-13.2.1 confirmation gate with the channel recorded as in-app and the approver as the host's own identity.
- **AC-3 (negative) — Given** the host does not respond within a configured interval, **When** the interval elapses, **Then** the receptionist is offered the out-of-band confirmation path from US-13.2.1 rather than the visitor being stranded.

> ⛔ **Held.** This story is written so the option is costed, not because it is authorised. Building
> an approval workflow when TODO-18 turns out to mean "a checkbox" is invented scope. Do not pull it
> into a sprint until TODO-18 is dispositioned.

**Development Tasks**

**T-13.2.2.1 — Implement the in-app approval request and response flow** · `P2` · `3 pts` · deps: `US-13.2.1`
- **Description:** Approval request targeted at a host identity, with approve/reject actions feeding the confirmation gate.
- **Acceptance Criteria:** Only the named host or an FM Admin may respond; response recorded through the US-13.2.1 use case; **blocked pending TODO-18**.
- **Dependencies:** `US-13.2.1`

**T-13.2.2.2 — Implement approval timeout fallback** · `P2` · `2 pts` · deps: `T-13.2.2.1`
- **Description:** Configurable timeout after which the receptionist is offered the out-of-band confirmation path; the pending in-app request is closed as expired.
- **Acceptance Criteria:** Timeout configurable; expired request cannot later be approved; fallback path recorded with its own channel value; **blocked pending TODO-18**.
- **Dependencies:** `T-13.2.2.1`

---

### F-13.3 — Walk-in credential request

Reception requests a temporary QR or RFID credential for a walk-in through the same ACS API used for pre-scheduled visitors.

**Provenance:** `SRS` FR-VMS-09 (SRS B1) · **Priority:** P1 · **Points:** 8 · **Stories:** 2

#### US-13.3.1 — Request a credential for an approved walk-in

**As a** Central Receptionist **I want** to request a temporary credential for an approved walk-in **so that** they can enter the building on the same footing as a pre-scheduled visitor.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-09 (SRS B1) — *"through the same ACS API used for pre-scheduled visitors"* |
| **Dependencies** | US-13.2.1, US-11.3.1, F-09.1 (Phase 2 credential orchestration) |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a walk-in whose approval is recorded, **When** a credential is requested, **Then** the request goes through the same `AcsPort.createCredential` path and the same F-09.1 orchestration as a pre-scheduled visitor, with no walk-in-specific ACS code.
- **AC-2 — Given** the request succeeds, **When** it is stored, **Then** a `vms.credentials` row exists with `acs_credential_id` (and `qr_payload` for QR), `valid_from`/`valid_to` from the walk-in's window, and `state = 'active'`.
- **AC-3 — Given** either credential type, **When** the receptionist chooses, **Then** both `qr` and `rfid` are selectable, and an RFID issuance additionally creates the `vms.card_issuances` record via F-15.1.
- **AC-4 — Given** the credential is created, **When** the audit trail is inspected, **Then** the credential lifecycle action is recorded in `vms.audit_logs` with the acting receptionist.
- **AC-5 (negative) — Given** the walk-in has no recorded approval, **When** issuance is attempted, **Then** it is refused by the US-13.2.1 gate before any ACS call is made — no wasted `vms.acs_requests` row.
- **AC-6 (negative) — Given** the visitor already holds an active credential, **When** a second is requested, **Then** it is refused, because `ux_credentials_active_per_visitor` permits only one active credential per visitor and the constraint must be enforced in the domain, not discovered at the database.

**Development Tasks**

**T-13.3.1.1 — Compose walk-in issuance over the shared credential orchestration** · `P1` · `2 pts` · deps: `US-13.2.1`
- **Description:** Walk-in issuance use case delegating entirely to the F-09.1 orchestration and `AcsPort`; no ACS branching on `visit_kind`.
- **Acceptance Criteria:** No walk-in-specific code below the application layer (asserted by review and by the F-11.1.2 fitness test); integration test proves identical ACS interaction for both visit kinds.
- **Dependencies:** `US-13.2.1`

**T-13.3.1.2 — Enforce the single-active-credential rule in the domain** · `P1` · `2 pts` · deps: `T-13.3.1.1`
- **Description:** Domain invariant refusing a second active credential per visitor, aligned with the `ux_credentials_active_per_visitor` partial unique index.
- **Acceptance Criteria:** Refusal happens before any ACS call; a concurrent double-submit surfaces as a clean refusal rather than a constraint-violation stack trace; integration test drives concurrent requests.
- **Dependencies:** `T-13.3.1.1`

**T-13.3.1.3 — Build the walk-in issuance UI and audit the action** · `P1` · `1 pt` · deps: `T-13.3.1.1`
- **Description:** Reception issuance control with credential-type selection and the `vms.audit_logs` entry for the lifecycle action.
- **Acceptance Criteria:** Both credential types selectable; RFID selection triggers the F-15.1 card issuance path; audit entry names the acting user and the credential id.
- **Dependencies:** `T-13.3.1.1`

#### US-13.3.2 — Handle ACS unavailability during walk-in issuance

**As a** Central Receptionist **I want** a clear outcome when a walk-in credential cannot be created **so that** I can make a decision about the person standing in front of me instead of waiting on a spinner.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-API-01 (SRS B1), FR-VMS-09 (SRS B1) · `SRS-NFR` NFR-REL-01 (SRS B1) |
| **Dependencies** | US-13.3.1, US-11.3.3 |
| **Blocked by** | TODO-07 (no agreed wait threshold) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** ACS is unreachable, **When** a walk-in credential is requested, **Then** the request is queued in `vms.acs_requests`, the receptionist is notified per US-11.3.3, and the walk-in registration and approval are preserved intact (NFR-REL-01, SRS B1).
- **AC-2 — Given** the queued request later succeeds, **When** it completes, **Then** the credential appears against the visitor and the reception view updates without re-registration.
- **AC-3 (negative) — Given** the request eventually dead-letters, **When** the receptionist checks the visitor, **Then** the credential shows `state = 'failed'` with the reason, and the visitor record remains usable for a fresh attempt after the ACS problem is fixed.
- **AC-4 (negative) — Given** ACS is slow rather than down, **When** the wait threshold elapses, **Then** the receptionist is told the request is still in progress and is not offered a control that would create a duplicate.

**Development Tasks**

**T-13.3.2.1 — Wire walk-in issuance into the queue-and-notify path** · `P1` · `2 pts` · deps: `US-13.3.1`
- **Description:** Ensure the walk-in path inherits the F-11.3 outbox, retry and receptionist notification behaviour without duplicating it.
- **Acceptance Criteria:** Simulator-unavailable integration test shows the queued row, the notification and intact visitor data; no walk-in-specific retry logic exists.
- **Dependencies:** `US-13.3.1`

**T-13.3.2.2 — Allow a fresh attempt after dead-letter without re-registration** · `P1` · `1 pt` · deps: `T-13.3.2.1`
- **Description:** Permit a new credential request against an existing walk-in visitor whose prior credential is `failed`, without recreating the visitor or re-recording approval.
- **Acceptance Criteria:** New request allowed only when the prior credential is `failed` or `cancelled`; approval record reused; both attempts visible in the audit trail.
- **Dependencies:** `T-13.3.2.1`

**T-13.3.2.3 — Render in-progress and failed issuance states at reception** · `P1` · `1 pt` · deps: `T-13.3.2.1`
- **Description:** Reception treatment for queued, in-progress and failed walk-in issuance, with no control that could produce a duplicate request.
- **Acceptance Criteria:** Three states visually distinct; no "retry now" control while a request is in flight; state updates without full page reload.
- **Dependencies:** `T-13.3.2.1`

---

### F-13.4 — Express entry — barrier scan bypassing reception ⛔ BLOCKED / DESCOPE CANDIDATE

Pre-scheduled visitors scan directly at the flap barrier and bypass reception entirely.

**Provenance:** `BLOCKED` `SRS` FR-VMS-10 (SRS B1) · TODO-03 · **Priority:** P3 · **Points:** 8 · **Stories:** 2

> ⛔ **Blocked on TODO-03, and a strong descope candidate.** FR-VMS-10 (SRS B1) requires ACS to
> recognise VMS-issued credentials with no reception step. **The SRS itself notes that the vendor's
> current submission describes a fully reception-mediated flow** — meaning the requirement may not be
> achievable with the delivered ACS at all. TODO-03 asks for explicit vendor confirmation or a
> decision to descope FR-VMS-10 entirely.
>
> The stories below exist so the option is costed and the requirement is not silently dropped from
> the traceability matrix. They are **not authorised for implementation**, they are Stage B, and
> **no other story in this phase depends on them** — that isolation is deliberate, so that descoping
> F-13.4 removes 8 points and breaks nothing else.
>
> This feature also touches TODO-19 (D-08): if express entry were built such that VMS participates
> in the gate decision, it would cross the SRS §1.2 out-of-scope boundary. Any eventual
> implementation must keep the grant/deny decision unconditionally with ACS.

#### US-13.4.1 — Provision a pre-scheduled credential for direct barrier use

**As a** Visitor **I want** my pre-issued credential to work at the barrier without visiting reception **so that** I can go straight to my meeting.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` `SRS` FR-VMS-10 (SRS B1) · TODO-03 |
| **Dependencies** | US-11.7.1 |
| **Blocked by** | **TODO-03** (vendor confirmation that express entry is achievable), **TODO-02**, TODO-19 |
| **ACS Stage** | B (real contract) |

**Acceptance Criteria**
- **AC-1 — Given** TODO-03 confirms ACS supports recognising VMS-issued credentials without a reception step, **When** a pre-scheduled credential is provisioned, **Then** it is accepted at the barrier and an entry event returns to VMS through F-11.8.
- **AC-2 — Given** an express entry occurs, **When** the entry event is applied, **Then** the visitor moves to `inside` with no reception interaction, through the same F-12.3 path as any other entry.
- **AC-3 — Given** express entry is used, **When** the grant/deny decision is examined, **Then** it was made entirely by ACS — VMS provisioned data and received an event, and made no access decision (SRS §1.2 out-of-scope, CON-01, CON-02, SRS B1).
- **AC-4 (negative) — Given** the credential is outside its validity window, **When** it is presented at the barrier, **Then** ACS refuses it and VMS neither overrides nor is consulted.
- **AC-5 (negative) — Given** TODO-03 resolves as "not achievable", **When** this feature is dispositioned, **Then** FR-VMS-10 (SRS B1) is formally descoped in the traceability matrix with the vendor statement recorded — not left silently unimplemented.

**Development Tasks**

**T-13.4.1.1 — Confirm express-entry feasibility with the vendor and record the disposition** · `P3` · `2 pts` · deps: `TODO-03`
- **Description:** Obtain a written vendor statement on whether ACS recognises VMS-issued credentials with no reception step, and record the disposition against FR-VMS-10 (SRS B1).
- **Acceptance Criteria:** Written statement obtained; traceability matrix updated to implemented-or-descoped; TODO-03 closed either way. **Blocked pending TODO-03.**
- **Dependencies:** TODO-03

**T-13.4.1.2 — Provision express-eligible credentials through the existing port** · `P3` · `2 pts` · deps: `T-13.4.1.1`
- **Description:** Any express-entry flag or eligibility marker required by the contract is set through `AcsPort` with no new integration path.
- **Acceptance Criteria:** No new ACS code path; eligibility expressed in VMS domain terms; **blocked pending TODO-02 and TODO-03**.
- **Dependencies:** `T-13.4.1.1`

**T-13.4.1.3 — Verify VMS makes no gate decision** · `P3` · `1 pt` · deps: `T-13.4.1.2`
- **Description:** Explicit verification, referencing TODO-19 and D-08, that no VMS component participates in the barrier grant/deny decision.
- **Acceptance Criteria:** Architecture review records that grant/deny stays with ACS; any edge component is display/status only; **blocked pending TODO-19**.
- **Dependencies:** `T-13.4.1.2`

#### US-13.4.2 — Reconcile express entries with no reception record

**As an** FM Admin **I want** express entries to appear in the visit record even though reception never saw the visitor **so that** the building's record of who entered stays complete.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 3 |
| **Provenance** | `BLOCKED` `SRS` FR-VMS-10 (SRS B1) · TODO-03 · supports FR-REP-01 (SRS B1) |
| **Dependencies** | US-13.4.1 |
| **Blocked by** | **TODO-03**, **TODO-02** |
| **ACS Stage** | B (real contract) |

**Acceptance Criteria**
- **AC-1 — Given** an express entry event, **When** it is processed, **Then** the visitor's record shows entry with the reception step recorded as bypassed rather than missing.
- **AC-2 — Given** reporting runs, **When** express entries are included, **Then** they are distinguishable from reception-mediated entries in FR-REP-01 (SRS B1) output.
- **AC-3 (negative) — Given** an express entry for a visitor whose approval was later withdrawn, **When** the event arrives, **Then** it is persisted and flagged as an anomaly — VMS records what happened and does not attempt to retro-justify it.

**Development Tasks**

**T-13.4.2.1 — Record the reception-bypassed state on the visit** · `P3` · `2 pts` · deps: `US-13.4.1`
- **Description:** Represent "entered without a reception step" explicitly on the visitor record rather than as an absent check-in.
- **Acceptance Criteria:** State distinguishable from a missing check-in; visible in the visit record; **blocked pending TODO-03**.
- **Dependencies:** `US-13.4.1`

**T-13.4.2.2 — Distinguish express entries in reporting** · `P3` · `1 pt` · deps: `T-13.4.2.1`
- **Description:** Expose the entry route to the Phase 4 reporting projections.
- **Acceptance Criteria:** Reporting can filter and count by entry route; no reporting write-back into the entry context; **blocked pending TODO-03**.
- **Dependencies:** `T-13.4.2.1`

---

## EPIC-14 — Credential Lifecycle Operations

**Provenance:** `SRS` FR-VMS-12 (SRS B1), FR-VMS-14 (SRS B1) · `TDD-DERIVED` TDD §4.3 · `BLOCKED` TODO-04
**Requirement IDs:** FR-VMS-12 (SRS B1 — ambiguous, TODO-09), FR-VMS-14 (SRS B1), CON-02 (SRS B1)
**Priority:** P1 · **Story points:** 27 · **Stories:** 7 · **Tasks:** 19

What happens to a credential after it is issued. FR-VMS-12 (SRS B1) requires VMS to deactivate a
credential at the end of its validity window — *"unless ACS performs this automatically and reports
status back to VMS."* Both branches are stated as acceptable, which means we cannot know whether we
are building a scheduler or a reconciler until TODO-09 is answered; F-14.1 is therefore designed to
tolerate both and to be safe under either. FR-VMS-14 (SRS B1) requires on-demand status query, which
F-14.2 delivers with a Redis cache that must never be allowed to assert a credential is active when
ACS says otherwise. F-14.3 covers cancellation and revocation. F-14.4 has no SRS requirement at all
and must not be built.

---

### F-14.1 — Credential deactivation at end of validity

VMS calls the ACS API to deactivate a credential at the end of its validity window, unless ACS does it automatically.

**Provenance:** `SRS` FR-VMS-12 (SRS B1) · ⚠️ **TODO-09** · **Priority:** P1 · **Points:** 10 · **Stories:** 2

> ⚠️ **The requirement contains its own alternative.** FR-VMS-12 (SRS B1) permits either VMS-driven
> deactivation or ACS-driven expiry reported back. TODO-09 says the design must tolerate both until
> the ACS behaviour is known (which itself depends on TODO-02). Both stories below are built to be
> correct under either answer; neither may be closed until TODO-09 resolves.

#### US-14.1.1 — Deactivate a credential at the end of its validity window

**As an** FM Admin **I want** credentials deactivated when their validity window ends **so that** a visitor's pass does not remain usable after their visit.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-12 (SRS B1) · `SRS-CON` CON-02 (SRS B1) |
| **Dependencies** | US-11.3.1, F-09.7 (Phase 2 credential state machine) |
| **Blocked by** | **TODO-09** (whether VMS or ACS performs expiry), TODO-02 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a credential in `state = 'active'` whose `valid_to` has passed, **When** the deactivation scheduler runs, **Then** `AcsPort.deactivateCredential` is invoked through the F-11.3 outbox and, on success, `vms.credentials.state` becomes `expired` with `deactivated_at` set.
- **AC-2 — Given** ACS already expired the credential itself, **When** VMS attempts deactivation, **Then** the "already inactive" outcome is treated as **success**, not as an error — VMS-driven and ACS-driven expiry must converge on the same end state.
- **AC-3 — Given** the deactivation is performed, **When** it commits, **Then** a `vms.audit_logs` entry records the credential lifecycle action, its trigger (scheduled or event-driven), and the resulting state.
- **AC-4 — Given** a one-time credential marked deactivation-eligible by T-12.3.2.3, **When** the scheduler runs, **Then** it is deactivated without waiting for `valid_to`.
- **AC-5 (negative) — Given** ACS is unreachable at the scheduled deactivation time, **When** the attempt is made, **Then** the operation is queued and retried per F-11.3, the credential remains `active` in VMS until confirmation, and the divergence is visible to the F-11.9 sync job — VMS never marks a credential expired on the basis of a call it could not make.
- **AC-6 (negative) — Given** a credential whose `valid_to` passes while the visitor is still `inside`, **When** deactivation runs, **Then** it proceeds and the still-inside visitor is flagged for operator attention, because an unreturned visitor is an operational fact, not a reason to extend access silently.

> **Conservative by design.** The scheduler is written so that it is harmless if ACS turns out to
> expire credentials itself: every attempt is idempotent and "already inactive" is a success. If
> TODO-09 answers "ACS does it", the scheduler is disabled by configuration rather than removed, and
> F-11.9 becomes the mechanism of record.

**Development Tasks**

**T-14.1.1.1 — Implement the deactivation scheduler** · `P1` · `2 pts` · deps: `US-11.3.1`
- **Description:** Scheduled job selecting `vms.credentials` rows in `state = 'active'` with elapsed `valid_to` (or one-time eligibility), enqueueing `deactivate_credential` through the outbox, using `idx_credentials_state`.
- **Acceptance Criteria:** Batch-bounded and rate-limited; singleton across instances; interval and enable/disable both configurable in `vms.system_settings` so it can be switched off if TODO-09 answers "ACS".
- **Dependencies:** `US-11.3.1`

**T-14.1.1.2 — Make deactivation idempotent and convergent** · `P1` · `2 pts` · deps: `T-14.1.1.1`
- **Description:** Treat "already inactive at ACS" as success; ensure repeat deactivation of an already-`expired` credential is a no-op.
- **Acceptance Criteria:** Simulator returns already-inactive and the credential converges to `expired`; repeat runs produce no additional `vms.acs_requests` rows; unit and integration tests cover both orderings.
- **Dependencies:** `T-14.1.1.1`

**T-14.1.1.3 — Hold state on unconfirmed deactivation and flag inside-visitors** · `P1` · `1 pt` · deps: `T-14.1.1.2`
- **Description:** Keep `state = 'active'` until ACS confirms; raise an operator flag when a credential expires while its visitor is still `inside`.
- **Acceptance Criteria:** ACS-unreachable run changes no credential state; flag raised and visible; audit entry written for the flag, not only for the state change.
- **Dependencies:** `T-14.1.1.2`

#### US-14.1.2 — Accept ACS-driven expiry reported back to VMS

**As a** System Administrator **I want** VMS to accept expiry that ACS performed itself **so that** the system is correct under whichever half of FR-VMS-12 (SRS B1) turns out to apply.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-12 (SRS B1) — the *"unless ACS performs this automatically"* branch |
| **Dependencies** | US-11.8.2, US-11.9.1 |
| **Blocked by** | **TODO-09**, TODO-02 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** ACS reports a credential as expired or revoked, **When** the report is processed (by inbound event or by the F-11.9 sync job), **Then** `vms.credentials.state` is updated to match with `deactivated_at` set from the ACS-reported time.
- **AC-2 — Given** both VMS-driven deactivation and an ACS-driven expiry report occur for the same credential, **When** both are processed, **Then** the final state is the same and no duplicate audit entry claims two separate deactivations.
- **AC-3 — Given** an ACS-driven expiry, **When** it is applied, **Then** the audit entry records ACS as the actor rather than attributing the change to a VMS user.
- **AC-4 (negative) — Given** ACS reports a credential as still active after `valid_to` has passed, **When** the sync job runs, **Then** the divergence is reported (F-11.9) and **not** silently corrected in either direction — a credential outliving its window is a finding, not a data-entry error.

**Development Tasks**

**T-14.1.2.1 — Apply ACS-reported credential state changes** · `P1` · `2 pts` · deps: `US-11.8.2`
- **Description:** Handler applying ACS-reported expiry/revocation to `vms.credentials`, sourcing `deactivated_at` from the ACS-reported time and attributing the audit entry to ACS.
- **Acceptance Criteria:** State and timestamp applied from the report; audit actor is ACS; idempotent on redelivery.
- **Dependencies:** `US-11.8.2`

**T-14.1.2.2 — Converge VMS-driven and ACS-driven deactivation** · `P1` · `2 pts` · deps: `T-14.1.2.1`
- **Description:** Ensure both paths reach one final state with exactly one logical deactivation recorded, whichever arrives first.
- **Acceptance Criteria:** Both orderings tested against the simulator; single deactivation recorded; no state flapping.
- **Dependencies:** `T-14.1.2.1`

**T-14.1.2.3 — Report, do not auto-correct, an over-running credential** · `P1` · `1 pt` · deps: `T-14.1.2.2`
- **Description:** Where ACS reports active past `valid_to`, raise a divergence finding through F-11.9 without changing state.
- **Acceptance Criteria:** Finding raised with both states; no automatic correction; documented as a deliberate TODO-09 conservatism.
- **Dependencies:** `T-14.1.2.2`

---

### F-14.2 — Credential status query & caching

VMS queries the ACS API for a credential's current status on demand, with a Redis cache that never overstates validity.

**Provenance:** `SRS` FR-VMS-14 (SRS B1) · TDD §3 (Redis) · **Priority:** P1 · **Points:** 8 · **Stories:** 2

#### US-14.2.1 — Query a credential's current status from ACS on demand

**As a** Central Receptionist **I want** to check a credential's current status **so that** I can answer a visitor asking why their pass did not work.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-VMS-14 (SRS B1) · `SRS-CON` CON-02 (SRS B1) |
| **Dependencies** | US-11.1.1, US-11.3.1 |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a credential with an `acs_credential_id`, **When** status is queried, **Then** `AcsPort.queryCredentialStatus` is invoked and the ACS-reported status (active, expired, revoked) is returned to the caller.
- **AC-2 — Given** the query completes, **When** it is recorded, **Then** a `vms.acs_requests` row with `operation = 'query_status'` and the `vms.acs_api_log` entries exist per FR-API-02 (SRS B1).
- **AC-3 — Given** the ACS-reported status differs from `vms.credentials.state`, **When** the result is returned, **Then** both are shown and the divergence is recorded for F-11.9 rather than one silently overwriting the other in the UI.
- **AC-4 (negative) — Given** ACS is unreachable, **When** status is queried, **Then** the caller receives an explicit "status unknown" result with the last known VMS state clearly labelled as stale — never a fabricated "active".
- **AC-5 (negative) — Given** a credential whose `acs_credential_id` is null because creation never succeeded, **When** status is queried, **Then** VMS returns the local `failed`/`requested` state without calling ACS at all.

**Development Tasks**

**T-14.2.1.1 — Implement the status query use case** · `P1` · `2 pts` · deps: `US-11.1.1`
- **Description:** Application use case invoking the port, recording the outbox and log rows, and surfacing both ACS and VMS state.
- **Acceptance Criteria:** Query recorded as `query_status`; null `acs_credential_id` short-circuits without an ACS call; divergence recorded.
- **Dependencies:** `US-11.1.1`

**T-14.2.1.2 — Represent "status unknown" explicitly** · `P0` · `1 pt` · deps: `T-14.2.1.1`
- **Description:** A distinct unknown outcome for ACS-unreachable queries, rendered as stale-with-timestamp rather than as a current status.
- **Acceptance Criteria:** Unknown is a first-class outcome, not a null or a default to active; UI labels staleness with the time of last known state; integration test under simulator outage.
- **Dependencies:** `T-14.2.1.1`

**T-14.2.1.3 — Expose the status check to reception** · `P2` · `1 pt` · deps: `T-14.2.1.2`
- **Description:** Reception-facing status check on the credential view, showing ACS-reported status, VMS state, and any divergence between them.
- **Acceptance Criteria:** Both states shown side by side; divergence called out rather than resolved in the UI; unknown state clearly labelled stale.
- **Dependencies:** `T-14.2.1.2`

#### US-14.2.2 — Cache credential status without overstating validity

**As a** System Administrator **I want** credential status cached in Redis with conservative invalidation **so that** repeated checks do not hammer ACS while never reporting a revoked credential as active.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-VMS-14 (SRS B1) · `TDD-DERIVED` TDD §3 (Redis) · `SRS-NFR` NFR-PRF-01 (SRS B1) |
| **Dependencies** | US-14.2.1 |
| **Blocked by** | TODO-07 (no latency target to size the cache TTL against) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a status query, **When** a fresh cached value exists in Redis, **Then** it is returned without an ACS call and is labelled with its age.
- **AC-2 — Given** a credential is deactivated, revoked or cancelled by any path, **When** the state change commits, **Then** its cache entry is invalidated immediately — a negative state change must never wait for a TTL.
- **AC-3 — Given** an inbound ACS event or sync result changes a credential's state, **When** it is applied, **Then** the cache entry is invalidated as part of the same operation.
- **AC-4 (negative) — Given** Redis is unavailable, **When** a status query is made, **Then** it falls through to ACS and the request succeeds — the cache is an optimisation, never a dependency.
- **AC-5 (negative) — Given** a cached `active` status whose credential's `valid_to` has since passed, **When** the cache is read, **Then** the entry is treated as stale regardless of TTL, because the validity window is knowable locally.
- **AC-6 (negative) — Given** a cache write fails, **When** the query completes, **Then** the correct ACS-sourced result is still returned and the cache failure is logged, not surfaced to the receptionist.

**Development Tasks**

**T-14.2.2.1 — Implement the Redis credential status cache** · `P1` · `2 pts` · deps: `US-14.2.1`
- **Description:** Read-through cache keyed on credential id, storing status plus retrieval timestamp, with a conservative configurable TTL.
- **Acceptance Criteria:** Cache hit avoids the ACS call; every returned value carries its age; TTL configurable in `vms.system_settings`.
- **Dependencies:** `US-14.2.1`

**T-14.2.2.2 — Invalidate on every state-changing path** · `P0` · `2 pts` · deps: `T-14.2.2.1`
- **Description:** Invalidation hooked into deactivation, revocation, cancellation, inbound event application and sync correction — every writer, not only the obvious one.
- **Acceptance Criteria:** Each of the five paths invalidates (one integration test per path); a revoked credential never reads back as active from cache.
- **Dependencies:** `T-14.2.2.1`

**T-14.2.2.3 — Degrade safely when Redis is unavailable** · `P1` · `1 pt` · deps: `T-14.2.2.1`
- **Description:** Fail-open-to-source behaviour on any Redis error, with logging and metrics but no user-visible failure.
- **Acceptance Criteria:** Redis-down integration test still returns correct status from ACS; cache errors logged and counted; no exception reaches the caller.
- **Dependencies:** `T-14.2.2.1`

---

### F-14.3 — Credential cancellation & revocation

Cancelling a credential before it is used, and revoking one already active.

**Provenance:** `TDD-DERIVED` TDD §4.3 · Schema `vms.credential_state` values `cancelled`, `revoked` (D-07) · **Priority:** P2 · **Points:** 8 · **Stories:** 2

> ⚠️ `TDD-DERIVED`: no SRS requirement defines the `cancelled` or `revoked` transitions — D-07 flags
> exactly this. The operations are schema-supported and operationally necessary (a visit cancelled
> after issuance leaves an active credential in the building), but they are held for TODO-01
> disposition.

#### US-14.3.1 — Cancel a credential that was requested but never activated

**As a** Central Receptionist **I want** to cancel a credential request that is no longer needed **so that** a visit cancelled before arrival does not leave a pending ACS operation behind.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` TDD §4.3 |
| **Dependencies** | US-11.3.1 |
| **Blocked by** | TODO-01 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a credential in `state = 'requested'` with no ACS confirmation yet, **When** it is cancelled, **Then** `state` becomes `cancelled` and any pending `vms.acs_requests` row for it is marked so it will not be retried.
- **AC-2 — Given** cancellation, **When** it commits, **Then** a `vms.audit_logs` entry records the acting user and the reason.
- **AC-3 (negative) — Given** the ACS create actually succeeded but the response had not yet been processed, **When** cancellation races with it, **Then** the credential ends up active-then-revoked rather than cancelled-but-live-at-ACS — VMS must never leave a working credential it believes is cancelled.
- **AC-4 (negative) — Given** a credential already `active`, **When** cancellation is attempted, **Then** it is refused and the user is directed to revocation (US-14.3.2), which requires an ACS call.

**Development Tasks**

**T-14.3.1.1 — Implement the cancellation use case** · `P2` · `2 pts` · deps: `US-11.3.1`
- **Description:** Cancel a `requested` credential, suppress its pending outbox operation, and audit the action with a reason.
- **Acceptance Criteria:** Only `requested` credentials cancellable; pending outbox row taken out of retry; audit entry with reason.
- **Dependencies:** `US-11.3.1`

**T-14.3.1.2 — Resolve the cancel-versus-late-success race safely** · `P1` · `1 pt` · deps: `T-14.3.1.1`
- **Description:** When a late ACS success arrives for a cancelled credential, transition it to active and immediately enqueue a revocation instead of leaving it stranded.
- **Acceptance Criteria:** Simulator-driven race test ends with a revoked credential at ACS and `state = 'revoked'` in VMS; no credential remains live while VMS shows `cancelled`.
- **Dependencies:** `T-14.3.1.1`

**T-14.3.1.3 — Refuse cancellation of an active credential and route to revocation** · `P2` · `1 pt` · deps: `T-14.3.1.1`
- **Description:** Guard refusing cancellation once a credential is `active`, directing the user to the revocation path which requires an ACS call.
- **Acceptance Criteria:** Refusal enforced server-side, not only in the UI; message names revocation as the correct action; audited as a refused attempt.
- **Dependencies:** `T-14.3.1.1`

#### US-14.3.2 — Revoke an active credential

**As an** FM Admin **I want** to revoke an active credential immediately **so that** access can be withdrawn during a visit when circumstances change.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §4.3 · `SRS-CON` CON-02 (SRS B1) |
| **Dependencies** | US-14.3.1, US-11.3.1 |
| **Blocked by** | TODO-01 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** an `active` credential, **When** revocation is requested, **Then** `AcsPort.deactivateCredential` is invoked through the outbox and, on confirmation, `state` becomes `revoked` with `deactivated_at` set.
- **AC-2 — Given** revocation, **When** it commits, **Then** the Redis cache entry is invalidated immediately (AC-2 of US-14.2.2) and a `vms.audit_logs` entry records the acting user and the reason, which is mandatory.
- **AC-3 — Given** the visitor is currently `inside`, **When** their credential is revoked, **Then** the revocation proceeds, the operator is told the visitor is still in the building, and any outstanding card is flagged for recovery (F-15.4).
- **AC-4 (negative) — Given** ACS cannot be reached, **When** revocation is requested, **Then** the operation is queued and retried, the credential is **not** marked revoked locally until confirmed, and the operator is told plainly that the credential may still be usable — an unconfirmed revocation must never be displayed as complete.
- **AC-5 (negative) — Given** a user without the credential revocation permission, **When** they attempt it, **Then** it is denied at the API boundary and the denial is audited.

**Development Tasks**

**T-14.3.2.1 — Implement the revocation use case with mandatory reason** · `P2` · `2 pts` · deps: `US-14.3.1`
- **Description:** Revocation through the port and outbox, requiring a reason, invalidating cache and auditing.
- **Acceptance Criteria:** Reason mandatory and stored; cache invalidated in the same operation; audit entry names user, credential and reason.
- **Dependencies:** `US-14.3.1`

**T-14.3.2.2 — Withhold the revoked state until ACS confirms** · `P0` · `2 pts` · deps: `T-14.3.2.1`
- **Description:** Keep `state = 'active'` with a pending-revocation indicator until ACS confirms; surface the ambiguity explicitly to the operator.
- **Acceptance Criteria:** Simulator-outage test leaves the credential `active` with a pending indicator; operator message states the credential may still work; state converges to `revoked` on retry success.
- **Dependencies:** `T-14.3.2.1`

**T-14.3.2.3 — Handle revocation of a credential for an inside visitor** · `P2` · `1 pt` · deps: `T-14.3.2.1`
- **Description:** Operator warning and card-recovery flag when revoking for a visitor in `inside` status.
- **Acceptance Criteria:** Warning shown; outstanding `vms.card_issuances` rows flagged for F-15.4; revocation not blocked.
- **Dependencies:** `T-14.3.2.1`

---

### F-14.4 — Manual override ⛔ BLOCKED — DO NOT BUILD

Administrative forced state change on a credential.

**Provenance:** `BLOCKED` TODO-04 · discrepancy **D-06** · **Priority:** P3 · **Points:** 1 · **Stories:** 1

> ⛔ **This feature has no SRS requirement and must not be implemented.**
>
> TDD §11 dependency 3 cites *"SRS Appendix B, Item 5"* for manual override. **The attached SRS
> Appendix B contains items 1–4 only.** There is no Item 5, and manual override appears nowhere in
> the attached SRS — yet it is a named ACS operation in TDD §6.1 and an enum value
> (`manual_override`) in the schema's `vms.acs_op` type, with a matching `credential.override`
> permission seeded in `vms.permissions`. That is discrepancy **D-06**, tracked as TODO-04.
>
> Manual override is an administrative forced state change on a physical access credential. It is
> the single most security-sensitive operation in the system, and it is the one operation with the
> weakest requirement backing — none at all. Building it would mean inventing a security-critical
> requirement, which project rule 2 forbids outright.
>
> **Do not build this.** Do not implement the `manual_override` value of `vms.acs_op`. Do not grant
> the seeded `credential.override` permission to any role. Do not add an override control to any
> screen, "just in case". The single placeholder story below exists solely so that the schema and
> TDD artefacts are accounted for in the traceability matrix and are not mistaken for an oversight.
> It has one point, and that point is documentation.

#### US-14.4.1 — Disposition manual override (documentation only) ⛔

**As a** Technical Product Manager **I want** manual override formally dispositioned **so that** a security-sensitive, unrequirmented operation is either properly requirementised or explicitly descoped, and never quietly implemented because the schema hinted at it.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 1 |
| **Provenance** | `BLOCKED` TODO-04 · D-06 |
| **Dependencies** | — |
| **Blocked by** | **TODO-04** — no SRS requirement exists (D-06) |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** the `manual_override` value in `vms.acs_op` and the `credential.override` permission in `vms.permissions`, **When** the codebase is inspected, **Then** neither is referenced by any application code path, and a test asserts that no code path emits an `acs_op` of `manual_override`.
- **AC-2 — Given** the `credential.override` permission, **When** role assignments are inspected, **Then** it is granted to no role in any environment.
- **AC-3 (negative) — Given** a developer adds an override code path, **When** CI runs, **Then** the guard test fails with a message citing TODO-04 and D-06.

> **Explicitly: this story authorises no implementation.** If TODO-04 later resolves with a written
> SRS requirement, a new feature is raised against that requirement, with its own security review,
> its own approval model, and its own audit design. It does not resume from here.

**Development Tasks**

**T-14.4.1.1 — Add a guard test asserting manual override is unimplemented** · `P3` · `1 pt` · deps: `—`
- **Description:** Test asserting that no code path constructs an `acs_op` of `manual_override` and that `credential.override` is granted to no role, with a failure message citing TODO-04 and D-06.
- **Acceptance Criteria:** Test present in the required CI checks; fails if an override path is introduced; message names the open question and the discrepancy. **No override functionality is implemented by this task.**
- **Dependencies:** `—`

---

## EPIC-15 — Card Accountability & Reconciliation

**Provenance:** `SRS` FR-CRD-01 (SRS B1), FR-CRD-02 (SRS B1), FR-CRD-03 (SRS B1)
**Requirement IDs:** FR-CRD-01 (SRS B1), FR-CRD-02 (SRS B1), FR-CRD-03 (SRS B1 — under-specified, TODO-11)
**Priority:** P1 · **Story points:** 28 · **Stories:** 8 · **Tasks:** 20

Physical RFID cards are the one part of this system that can walk out of the building. FR-CRD-01
(SRS B1) requires VMS to log each card issued including the identifier ACS returns; FR-CRD-02
(SRS B1) requires each return logged using status ACS reports, for example from a card-issuer swipe;
FR-CRD-03 (SRS B1) requires a daily reconciliation of issued-versus-returned counts using data
retrieved from the ACS API, flagging discrepancies that indicate a missing card. All three are
SRS-backed and all three run over `vms.card_issuances`. The reconciliation half is materially
under-specified by TODO-11 — run time, timezone, the cut-off defining a "day", who receives the
discrepancy flag and through which channel, and what happens to an unreturned card at the day
boundary are all undefined — so F-15.3 and F-15.4 compute and record faithfully while deliberately
declining to invent a schedule or a recipient.

---

### F-15.1 — RFID card issuance logging

Every RFID card issued to a visitor is logged with the card identifier ACS returns.

**Provenance:** `SRS` FR-CRD-01 (SRS B1) · **Priority:** P1 · **Points:** 6 · **Stories:** 2

#### US-15.1.1 — Log an RFID card issuance

**As a** Central Receptionist **I want** every RFID card I hand out recorded against the visitor **so that** the building knows who holds which card.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-CRD-01 (SRS B1) — *"including the card identifier returned by ACS at issuance"* |
| **Dependencies** | US-13.3.1, F-09.1 (Phase 2 credential orchestration) |
| **Blocked by** | — |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a credential with `credential_type = 'rfid'` is created successfully, **When** ACS returns the physical card identifier, **Then** a `vms.card_issuances` row is written with `visitor_id`, `credential_id`, `acs_card_id`, `issued_at` and `issued_by` set to the acting user.
- **AC-2 — Given** the issuance is logged, **When** it commits, **Then** it does so in the same transaction as the credential state change, so a card can never be issued without a record.
- **AC-3 — Given** the issuance, **When** the audit trail is inspected, **Then** a `vms.audit_logs` entry records the card issuance as a credential lifecycle action.
- **AC-4 (negative) — Given** ACS confirms credential creation but returns no card identifier, **When** the response is processed, **Then** the issuance is flagged as incomplete and raised for operator attention — `acs_card_id` is `NOT NULL` and must not be filled with a placeholder to satisfy the constraint.
- **AC-5 (negative) — Given** a QR credential, **When** it is created, **Then** no `vms.card_issuances` row is written — card accountability applies to physical cards only.

**Development Tasks**

**T-15.1.1.1 — Implement card issuance logging on RFID credential creation** · `P1` · `2 pts` · deps: `US-13.3.1`
- **Description:** Write the `vms.card_issuances` row within the credential creation transaction when the ACS response carries a card identifier.
- **Acceptance Criteria:** Row written only for `rfid`; all required columns populated; same-transaction commit proven by an injected-failure integration test.
- **Dependencies:** `US-13.3.1`

**T-15.1.1.2 — Handle a missing card identifier as an incomplete issuance** · `P1` · `1 pt` · deps: `T-15.1.1.1`
- **Description:** Detect an RFID creation response without a card identifier, flag it for operator attention, and refuse to invent a placeholder value.
- **Acceptance Criteria:** No row written with a synthetic `acs_card_id`; operator flag raised; simulator malformed-response mode covers the case.
- **Dependencies:** `T-15.1.1.1`

**T-15.1.1.3 — Audit card issuance as a credential lifecycle action** · `P1` · `1 pt` · deps: `T-15.1.1.1`
- **Description:** Write a `vms.audit_logs` entry for each card issuance, naming the acting receptionist, the visitor and the `acs_card_id`.
- **Acceptance Criteria:** Entry written within the issuance transaction; card identifier recorded; no entry written for QR credentials.
- **Dependencies:** `T-15.1.1.1`

#### US-15.1.2 — See which cards a visitor currently holds

**As a** Central Receptionist **I want** to see the cards a visitor holds **so that** I can ask for them back before the visitor leaves.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-CRD-01 (SRS B1), FR-CRD-03 (SRS B1) |
| **Dependencies** | US-15.1.1 |
| **Blocked by** | — |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** a visitor with issued cards, **When** their record is opened, **Then** outstanding cards (rows with null `returned_at`) are listed with `acs_card_id` and `issued_at`, using the `idx_cards_open` partial index.
- **AC-2 — Given** returned cards, **When** the record is viewed, **Then** the return history is available but visually separated from outstanding cards.
- **AC-3 (negative) — Given** a visitor holds a card from an earlier visit that was never returned, **When** their record is opened, **Then** the historical outstanding card is shown as well, because an unreturned card does not stop being missing when a new visit starts.

**Development Tasks**

**T-15.1.2.1 — Build the visitor card-holding projection** · `P2` · `2 pts` · deps: `US-15.1.1`
- **Description:** Read model over `vms.card_issuances` for a visitor, partitioned into outstanding and returned, including prior visits.
- **Acceptance Criteria:** Query uses `idx_cards_visitor` and `idx_cards_open`; historical outstanding cards included; read-only projection.
- **Dependencies:** `US-15.1.1`

**T-15.1.2.2 — Surface card holdings in the reception visitor view** · `P2` · `1 pt` · deps: `T-15.1.2.1`
- **Description:** Reception UI presentation of outstanding versus returned cards on the visitor record.
- **Acceptance Criteria:** Outstanding cards visually prominent; return history collapsible; WCAG 2.1 AA.
- **Dependencies:** `T-15.1.2.1`

---

### F-15.2 — Card return logging

Each card's return is logged using return status reported by ACS.

**Provenance:** `SRS` FR-CRD-02 (SRS B1) · **Priority:** P1 · **Points:** 8 · **Stories:** 2

#### US-15.2.1 — Log a card return reported by ACS

**As an** FM Admin **I want** card returns recorded from ACS's own report **so that** the return record reflects the physical card going back into the issuer, not someone remembering to tick a box.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-CRD-02 (SRS B1) — *"using return status reported by ACS (e.g. from a card-issuer swipe event)"* |
| **Dependencies** | US-11.8.2, US-15.1.1 |
| **Blocked by** | TODO-02 (card return event shape and delivery mechanism unknown) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** ACS reports a card return for a known `acs_card_id`, **When** the event is processed, **Then** the matching `vms.card_issuances` row has `returned_at` set from the ACS-reported time.
- **AC-2 — Given** the return is logged, **When** it commits, **Then** it is idempotent on the inbound event's `acs_event_id` per US-11.8.2 — a redelivered return event does not overwrite `returned_at` with a later timestamp.
- **AC-3 — Given** a card return, **When** it is applied, **Then** the associated credential is eligible for deactivation via F-14.1 if it is one-time or its window has elapsed.
- **AC-4 (negative) — Given** a return event for an `acs_card_id` VMS has no issuance record for, **When** it is processed, **Then** the event is persisted and flagged for reconciliation rather than discarded — an unknown card being returned is a finding.
- **AC-5 (negative) — Given** a return event whose reported time precedes the recorded `issued_at`, **When** it is applied, **Then** it is rejected by the `returned_at >= issued_at` check, the event is retained, and the inconsistency is flagged rather than the timestamp being adjusted to fit.
- **AC-6 (negative) — Given** a card already marked returned, **When** a second return event arrives, **Then** the original `returned_at` is preserved and the duplicate is recorded as such.

**Development Tasks**

**T-15.2.1.1 — Implement ACS card return event handling** · `P1` · `2 pts` · deps: `US-11.8.2`
- **Description:** Handler resolving the reported `acs_card_id` to a `vms.card_issuances` row and setting `returned_at` from the ACS-reported time, within the F-11.8 idempotency guarantees.
- **Acceptance Criteria:** Return applied for known cards; idempotent on redelivery; original `returned_at` never overwritten.
- **Dependencies:** `US-11.8.2`

**T-15.2.1.2 — Handle unknown cards and inconsistent return times** · `P1` · `2 pts` · deps: `T-15.2.1.1`
- **Description:** Flagging paths for returns of unknown cards and for return times preceding issuance, honouring the table's CHECK constraint without mutating reported times.
- **Acceptance Criteria:** Neither case throws an unhandled constraint violation; both produce reconciliation flags; ACS-reported times stored verbatim in the event record.
- **Dependencies:** `T-15.2.1.1`

**T-15.2.1.3 — Trigger deactivation eligibility on return** · `P2` · `1 pt` · deps: `T-15.2.1.1`
- **Description:** Mark the associated credential deactivation-eligible for F-14.1 when the card is returned and the credential is one-time or elapsed.
- **Acceptance Criteria:** Eligibility set only under those conditions; no ACS call made from this handler; time-bound credentials inside their window untouched.
- **Dependencies:** `T-15.2.1.1`

#### US-15.2.2 — Record a card return at reception when ACS has not reported one

**As a** Central Receptionist **I want** to record a card return I have physically taken back **so that** a card in my hand is not counted as missing because the issuer did not report it.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-CRD-02 (SRS B1), FR-CRD-03 (SRS B1) |
| **Dependencies** | US-15.2.1 |
| **Blocked by** | TODO-11 (whether a manually recorded return satisfies the daily reconciliation is undefined) |
| **ACS Stage** | N/A |

**Acceptance Criteria**
- **AC-1 — Given** an outstanding card, **When** the receptionist records its return, **Then** `returned_at` and `returned_by` are set and the return source is recorded as reception-recorded rather than ACS-reported.
- **AC-2 — Given** a reception-recorded return, **When** ACS later reports the same return, **Then** the ACS report is recorded, the original `returned_at` is preserved, and no duplicate return is counted.
- **AC-3 — Given** any manual return, **When** it commits, **Then** a `vms.audit_logs` entry attributes it to the acting receptionist, because a manual return is an assertion by a person rather than an observation by a machine.
- **AC-4 (negative) — Given** reconciliation counts, **When** they are produced, **Then** ACS-reported and reception-recorded returns are counted separately, so a rise in manual returns is visible rather than hidden inside a single total.

> **Conservative by design.** FR-CRD-02 (SRS B1) specifies ACS-reported returns. This story adds a
> reception-recorded return because a card physically handed back with no ACS event is otherwise
> permanently "missing" — but it keeps the two sources distinguishable rather than merging them,
> and it does not decide whether a manual return closes a FR-CRD-03 (SRS B1) discrepancy. That is
> TODO-11's to answer.

**Development Tasks**

**T-15.2.2.1 — Implement the reception-recorded return with source tracking** · `P2` · `2 pts` · deps: `US-15.2.1`
- **Description:** Manual return use case setting `returned_at`/`returned_by`, recording the return source, and auditing the action.
- **Acceptance Criteria:** Source distinguishable from ACS-reported; audit entry written; a later ACS report does not overwrite the timestamp.
- **Dependencies:** `US-15.2.1`

**T-15.2.2.2 — Offer manual return during check-out** · `P2` · `1 pt` · deps: `T-15.2.2.1`
- **Description:** Surface outstanding cards from T-12.2.2.2 with an inline return control at check-out.
- **Acceptance Criteria:** Return recordable without leaving check-out; check-out still not blocked by an outstanding card; unreturned cards flow to F-15.4.
- **Dependencies:** `T-15.2.2.1`

**T-15.2.2.3 — Count return sources separately in reconciliation input** · `P2` · `1 pt` · deps: `T-15.2.2.1`
- **Description:** Expose ACS-reported and reception-recorded returns as distinct counts to the F-15.3 reconciliation computation.
- **Acceptance Criteria:** Two counts available separately and as a total; a rise in manual returns is visible in the reconciliation output; no merging of the two sources.
- **Dependencies:** `T-15.2.2.1`

---

### F-15.3 — Daily issued-vs-returned reconciliation

VMS reconciles issued-versus-returned card counts daily using data retrieved from the ACS API.

**Provenance:** `SRS` FR-CRD-03 (SRS B1) · ⚠️ **TODO-11** · **Priority:** P1 · **Points:** 8 · **Stories:** 2

> ⚠️ **Materially under-specified.** FR-CRD-03 (SRS B1) requires a daily reconciliation but TODO-11
> leaves undefined: the run time and timezone, the cut-off that defines a "day", who receives the
> discrepancy flag and through which channel, and what happens to an unreturned card at the day
> boundary. The stories below compute and record the reconciliation faithfully and make the schedule
> configuration rather than a decision. **No default run time, cut-off or recipient is treated as
> agreed**, and the feature cannot close until TODO-11 resolves.

#### US-15.3.1 — Run a daily card reconciliation against ACS data

**As an** FM Admin **I want** a daily reconciliation of cards issued against cards returned **so that** a missing card is discovered the same day rather than at an audit.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-CRD-03 (SRS B1) — *"using data retrieved from the ACS API"* |
| **Dependencies** | US-15.2.1, US-11.9.1 |
| **Blocked by** | **TODO-11** (run time, timezone, day cut-off undefined), TODO-02 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** the reconciliation runs for a defined period, **When** it executes, **Then** it compares `vms.card_issuances` rows against card state retrieved from ACS and produces counts of issued, returned and outstanding.
- **AC-2 — Given** a card ACS reports as returned but VMS shows outstanding, **When** the difference is found, **Then** the VMS record is updated from the ACS report and the correction is audited.
- **AC-3 — Given** the run completes, **When** results are stored, **Then** reconciled rows have `is_reconciled` set to `true` and the run's counts are retained for the FR-REP-02 (SRS B1) end-of-day report.
- **AC-4 — Given** the run schedule, **When** it is configured, **Then** run time, timezone and day cut-off are all configuration values in `vms.system_settings`, each documented as provisional pending TODO-11.
- **AC-5 (negative) — Given** ACS is unavailable when the run starts, **When** it executes, **Then** it aborts without marking anything reconciled, records the failure, alerts, and retries — a reconciliation that could not read ACS must never report zero discrepancies.
- **AC-6 (negative) — Given** a card issued shortly before the day cut-off and returned shortly after, **When** reconciliation runs, **Then** it is reported as outstanding for that day and resolved in the next run, and the boundary behaviour is recorded as a TODO-11 assumption rather than presented as agreed.

**Development Tasks**

**T-15.3.1.1 — Implement the reconciliation computation** · `P1` · `2 pts` · deps: `US-15.2.1`
- **Description:** Period-bounded comparison of `vms.card_issuances` against ACS-retrieved card state, producing issued/returned/outstanding counts and a per-card difference list.
- **Acceptance Criteria:** Counts correct across seeded fixtures including boundary cases; per-card differences classified; computation is pure and unit-testable independent of the scheduler.
- **Dependencies:** `US-15.2.1`

**T-15.3.1.2 — Implement the scheduled run with configurable window** · `P1` · `2 pts` · deps: `T-15.3.1.1`
- **Description:** Scheduled job with run time, timezone and cut-off from `vms.system_settings`, singleton across instances, plus manual on-demand execution.
- **Acceptance Criteria:** All three schedule values configurable and documented as provisional (TODO-11); on-demand run available to FM Admin; no hard-coded schedule anywhere.
- **Dependencies:** `T-15.3.1.1`

**T-15.3.1.3 — Make the run abort-safe and mark reconciled rows** · `P0` · `1 pt` · deps: `T-15.3.1.2`
- **Description:** Abort cleanly on ACS unavailability with nothing marked reconciled; on success set `is_reconciled` and audit ACS-sourced corrections.
- **Acceptance Criteria:** Simulator-outage run marks nothing and alerts; successful run sets `is_reconciled`; every correction audited.
- **Dependencies:** `T-15.3.1.2`

#### US-15.3.2 — Provide reconciliation results for the end-of-day report

**As a** Central Receptionist **I want** the day's issued and returned totals available **so that** I can pull the end-of-day report FR-REP-02 (SRS B1) requires.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-CRD-03 (SRS B1), FR-REP-02 (SRS B1) |
| **Dependencies** | US-15.3.1 |
| **Blocked by** | TODO-11 |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a completed reconciliation run, **When** the end-of-day data is requested, **Then** totals for credentials issued, cards issued, cards returned and cards outstanding are available for the period.
- **AC-2 — Given** the data is exposed, **When** Phase 4 reporting consumes it, **Then** it reads a projection and writes nothing back into the entry context.
- **AC-3 (negative) — Given** no reconciliation has run for the period (for example it aborted), **When** end-of-day data is requested, **Then** the response states that reconciliation did not complete rather than returning unreconciled counts as if they were final.

**Development Tasks**

**T-15.3.2.1 — Build the end-of-day reconciliation projection** · `P2` · `2 pts` · deps: `US-15.3.1`
- **Description:** Read model exposing per-period reconciliation totals and run status for Phase 4 reporting.
- **Acceptance Criteria:** Totals match the run's computed counts; run status included; projection is read-only.
- **Dependencies:** `US-15.3.1`

**T-15.3.2.2 — Represent an incomplete reconciliation explicitly** · `P1` · `1 pt` · deps: `T-15.3.2.1`
- **Description:** Distinct "reconciliation did not complete" state so unreconciled counts are never presented as final.
- **Acceptance Criteria:** Aborted-run integration test returns the incomplete state; counts suppressed or clearly labelled provisional; no silent zero.
- **Dependencies:** `T-15.3.2.1`

---

### F-15.4 — Discrepancy flagging & missing-card alerts

Discrepancies indicating a missing card are flagged and raised.

**Provenance:** `SRS` FR-CRD-03 (SRS B1) · ⚠️ **TODO-11** · **Priority:** P1 · **Points:** 6 · **Stories:** 2

#### US-15.4.1 — Flag a card discrepancy

**As an** FM Admin **I want** every unreturned card flagged with its context **so that** I can chase a specific card and visitor rather than a number in a report.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-CRD-03 (SRS B1) — *"flag discrepancies indicating a missing card"* |
| **Dependencies** | US-15.3.1 |
| **Blocked by** | **TODO-11** (what happens to an unreturned card at the day boundary is undefined) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** a reconciliation run finds outstanding cards, **When** discrepancies are flagged, **Then** each flag carries `acs_card_id`, visitor, host, tenant, `issued_at`, `issued_by` and the visitor's current status.
- **AC-2 — Given** a discrepancy, **When** the card is later returned, **Then** the flag is resolved automatically with the resolution recorded — resolution is never a manual tidy-up of a stale flag.
- **AC-3 — Given** a card outstanding across several days, **When** subsequent runs execute, **Then** one persistent discrepancy with an increasing age is maintained rather than a new flag per day.
- **AC-4 (negative) — Given** a visitor is still `inside` at the reconciliation cut-off, **When** their card is evaluated, **Then** it is reported as outstanding-but-expected rather than missing, and the distinction is recorded as a TODO-11 assumption, not as an agreed rule.
- **AC-5 (negative) — Given** a card never returned and the visitor long since `checked_out`, **When** the discrepancy ages beyond a configured threshold, **Then** it escalates in severity — the case FR-CRD-03 (SRS B1) exists to catch.

**Development Tasks**

**T-15.4.1.1 — Implement discrepancy records with context and ageing** · `P1` · `2 pts` · deps: `US-15.3.1`
- **Description:** Persistent discrepancy record per outstanding card, carrying full context, ageing across runs and auto-resolving on return.
- **Acceptance Criteria:** One record per card regardless of run count; age increments; auto-resolution on return recorded with the resolving event; still-inside cards classified distinctly.
- **Dependencies:** `US-15.3.1`

**T-15.4.1.2 — Build the discrepancy review view** · `P1` · `1 pt` · deps: `T-15.4.1.1`
- **Description:** FM Admin view listing open discrepancies sorted by age and severity, with full context and resolution history.
- **Acceptance Criteria:** Sortable and filterable; shows resolved discrepancies on request; authorised by an FM Admin permission; read-only projection.
- **Dependencies:** `T-15.4.1.1`

#### US-15.4.2 — Raise a missing-card alert

**As an** FM Admin **I want** to be alerted about missing cards **so that** a card that has left the building is chased while there is still a chance of recovering it.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-CRD-03 (SRS B1) |
| **Dependencies** | US-15.4.1 |
| **Blocked by** | **TODO-11** (recipient and channel undefined), TODO-10 (channel selection rule undefined) |
| **ACS Stage** | A (simulator) |

**Acceptance Criteria**
- **AC-1 — Given** open discrepancies after a reconciliation run, **When** alerting runs, **Then** one aggregated alert summarising count, oldest age and highest severity is raised — not one alert per card.
- **AC-2 — Given** a discrepancy escalates past the age threshold, **When** alerting runs, **Then** it is named individually within the alert.
- **AC-3 — Given** the alert is raised, **When** it is delivered, **Then** it is available in the administrative view and, once TODO-11 and TODO-10 are resolved, routed to the agreed recipient over the agreed channel.
- **AC-4 (negative) — Given** the reconciliation run aborted, **When** alerting is evaluated, **Then** no missing-card alert is raised, because an aborted run has no basis for asserting anything is missing.
- **AC-5 (negative) — Given** no agreed recipient exists, **When** an alert is generated, **Then** it is recorded and surfaced in-app and is **not** sent to a guessed recipient such as "all FM Admins".

> **Recipient deliberately unset.** FR-CRD-03 (SRS B1) does not name who receives the flag, and
> TODO-11 records that gap. Alerts are generated and visible in-app; routing waits for the answer.

**Development Tasks**

**T-15.4.2.1 — Implement aggregated missing-card alert generation** · `P2` · `2 pts` · deps: `US-15.4.1`
- **Description:** Post-run alert aggregating open discrepancies with count, oldest age and escalated cards named individually; suppressed when the run aborted.
- **Acceptance Criteria:** One alert per run; escalated cards named; no alert after an aborted run; thresholds configurable.
- **Dependencies:** `US-15.4.1`

**T-15.4.2.2 — Surface alerts in-app pending a routing decision** · `P2` · `1 pt` · deps: `T-15.4.2.1`
- **Description:** In-app alert surface for FM Admins, with routing left unconfigured and documented against TODO-11 and TODO-10.
- **Acceptance Criteria:** Alerts visible in-app; no external channel configured by default; settings entry documents the open questions.
- **Dependencies:** `T-15.4.2.1`

---

## Phase 3 summary

### Per-epic totals

| Epic | Title | Features | Stories | Tasks | Points | Stage A | Stage B | N/A |
|---|---|---|---|---|---|---|---|---|
| EPIC-11 | ACS Integration Client (Anti-Corruption Layer) | 9 | 23 | 63 | 98 | 20 | 3 | 0 |
| EPIC-12 | Arrival Verification & Entry | 4 | 9 | 23 | 35 | 2 | 0 | 7 |
| EPIC-13 | Walk-in & Express Entry | 4 | 8 | 21 | 32 | 2 | 2 | 4 |
| EPIC-14 | Credential Lifecycle Operations | 4 | 7 | 19 | 27 | 6 | 0 | 1 |
| EPIC-15 | Card Accountability & Reconciliation | 4 | 8 | 20 | 28 | 6 | 0 | 2 |
| **Total** | | **25** | **55** | **146** | **220** | **36** | **5** | **14** |

### Points by ACS stage

| Stage | Stories | Points | Status |
|---|---|---|---|
| A (simulator) | 36 | 146 | Buildable now; closes at "done against simulator" |
| B (real contract) | 5 | 26 | ⛔ Unstartable until TODO-02 (and TODO-03 for F-13.4) |
| N/A | 14 | 48 | Buildable now; closes as fully done |

### Requirement coverage for this phase

| Requirement | Feature | Status |
|---|---|---|
| FR-VMS-04 (SRS B1) | F-12.1 | Stage N/A — fully closeable |
| FR-VMS-08 (SRS B1) | F-13.1, F-13.2 | Partial — F-13.2 held on TODO-18 |
| FR-VMS-09 (SRS B1) | F-13.3 | Stage A |
| FR-VMS-10 (SRS B1) | F-13.4 | ⛔ Blocked TODO-03 — **descope candidate** |
| FR-VMS-12 (SRS B1) | F-14.1 | Stage A — cannot close until TODO-09 |
| FR-VMS-14 (SRS B1) | F-14.2 | Stage A |
| FR-VMS-15 (SRS B1) | F-12.4 | Stage N/A — held on TODO-17 |
| FR-CRD-01 (SRS B1) | F-15.1 | Stage A |
| FR-CRD-02 (SRS B1) | F-15.2 | Stage A |
| FR-CRD-03 (SRS B1) | F-15.3, F-15.4 | Stage A — cannot close until TODO-11 |
| FR-API-01 (SRS B1) | F-11.3, F-11.4 | Stage A |
| FR-API-02 (SRS B1) | F-11.5 | Stage A |
| FR-API-03 (SRS B1) | F-11.6 | Stage A — mechanism confirmed in Stage B |
| CON-01, CON-02 (SRS B1) | F-11.1, F-11.7 | F-11.1 Stage A; F-11.7 Stage B |
| NFR-MNT-01, NFR-REL-01 (SRS B1) | F-11.1, F-11.3, F-11.4 | Stage A |
| *(no requirement)* | F-14.4 | ⛔ **Do not build** — TODO-04, D-06 |

### Dependency graph

```
PHASE 1 / PHASE 2 INPUTS
  F-05.2 secrets mgmt ─────────────┐
  F-09.1 credential orchestration ─┤
  F-09.5 QR rendering ─────────────┤
  F-07.4 approval ─────────────────┤
  F-08.1 pre-registration ─────────┤
                                   │
EPIC-11 — ACS INTEGRATION CLIENT   │
                                   ▼
  US-11.1.1 AcsPort ──┬──► US-11.1.2 fitness test (CI gate)
                      │
                      ├──► US-11.2.1 simulator ──┬──► US-11.2.2 latency/failure injection
                      │                          └──► US-11.2.3 event emission
                      │
                      ├──► US-11.3.1 durable outbox ──┬──► US-11.3.2 retry + backoff ──► US-11.4.1 dead-letter
                      │                               │                                     ├──► US-11.4.2 replay
                      │                               │                                     └──► US-11.4.3 alerting
                      │                               ├──► US-11.3.3 receptionist notify
                      │                               └──► US-11.5.1 API log ──► US-11.5.2 redaction
                      │
                      ├──► US-11.6.1 ACS auth (secrets) ──► US-11.6.2 rotation
                      │
                      ├──► US-11.8.1 inbound receive ──► US-11.8.2 idempotency (acs_event_id)
                      │                                        └──► US-11.8.3 normalised event stream
                      │
                      └──► [STAGE B] US-11.7.1 wire adapter ──► US-11.7.2 contract tests
                                                             └──► US-11.7.3 verify vs non-prod endpoint
                                                                     ▲
                                                                  TODO-02

  US-14.2.1 status query ──► US-11.9.1 periodic sync ──► US-11.9.2 drift report

EPIC-12 — ARRIVAL VERIFICATION
  US-12.1.1 lookup ──┬──► US-12.1.2 appointment confirmation ──► US-12.2.1 check-in ──► US-12.2.2 check-out
                     └──► US-12.1.3 no-match handling ──────────────┐
                                                                    │
  US-11.8.2 ──► US-12.3.1 entry event ──► US-12.3.2 exit event      │
                                                                    │
  US-12.1.2 ──► US-12.4.1 visitor display ──► US-12.4.2 display hardening   [TODO-17]

EPIC-13 — WALK-IN                                                   │
  US-12.1.3 ────────────────────────────────────────────────────────┘
      └──► US-13.1.1 walk-in registration ──┬──► US-13.1.2 duplicate detection
                                            │
                                            └──► US-13.2.1 approval confirmation gate   [TODO-18]
                                                     ├──► [HELD] US-13.2.2 in-app approval
                                                     │
                                                     └──► US-13.3.1 walk-in credential ──► US-13.3.2 ACS-unavailable path
                                                              (reuses F-09.1 + AcsPort — no separate integration)

  [STAGE B / DESCOPE CANDIDATE — nothing depends on these]
      US-13.4.1 express entry ──► US-13.4.2 express reconciliation      ▲
                                                                   TODO-03

EPIC-14 — CREDENTIAL LIFECYCLE
  US-11.3.1 ──► US-14.1.1 scheduled deactivation ─┐
  US-11.8.2 ──► US-14.1.2 ACS-driven expiry ──────┴──► converge on one final state   [TODO-09]

  US-11.1.1 ──► US-14.2.1 status query ──► US-14.2.2 Redis cache + invalidation
                                                ▲
                        (invalidated by 14.1.1, 14.1.2, 14.3.1, 14.3.2, 11.9.1)

  US-14.3.1 cancellation ──► US-14.3.2 revocation

  [DO NOT BUILD] US-14.4.1 manual override disposition   ◄── TODO-04 / D-06

EPIC-15 — CARD ACCOUNTABILITY
  US-13.3.1 ──► US-15.1.1 issuance logging ──► US-15.1.2 visitor card holdings
  US-11.8.2 ──► US-15.2.1 ACS-reported return ──► US-15.2.2 reception-recorded return
                        │
                        └──► US-15.3.1 daily reconciliation ──┬──► US-15.3.2 end-of-day data
                                     ▲                        │
                                 US-11.9.1                    └──► US-15.4.1 discrepancy flagging ──► US-15.4.2 missing-card alert
                                                                              [TODO-11]

CRITICAL PATH
  US-11.1.1 ──► US-11.2.1 ──► US-11.3.1 ──► US-11.3.2 ──► US-13.3.1 ──► US-15.1.1 ──► US-15.3.1

EXTERNAL GATES
  TODO-02 (UAL contract) ──► F-11.7 ──► Stage B verification ──► every Stage A story → "verified"
  TODO-03 (express entry) ──► F-13.4 scope decision
  TODO-04 (manual override) ──► F-14.4 requirementise or delete
  TODO-09 (who expires) ──► F-14.1 closure
  TODO-11 (reconciliation) ──► F-15.3, F-15.4 closure
  TODO-17 (display model) ──► F-12.4 closure
  TODO-18 (walk-in approval) ──► F-13.2 closure
```

### Sequencing recommendation

1. **Sprint 1–2 — the boundary.** F-11.1 then F-11.2. Nothing else in the phase is safely testable
   until the port and the simulator exist, and the CI fitness test must land with the port or the
   boundary erodes immediately.
2. **Sprint 3–4 — reliability.** F-11.3, F-11.4, F-11.5, F-11.6. This is FR-API-01/02/03 (SRS B1) in
   full and is the highest-value unblocked work in the phase.
3. **Sprint 5 — inbound.** F-11.8, then F-12.1 and F-12.2 in parallel (F-12.1 has no ACS dependency
   at all and can start earlier if capacity allows).
4. **Sprint 6–7 — the workflow.** F-12.3, F-13.1, F-13.2, F-13.3, F-14.1, F-14.2, F-14.3.
5. **Sprint 8 — accountability.** F-15.1 … F-15.4, then F-11.9, then F-12.4.
6. **On TODO-02 landing — Stage B.** F-11.7 in full, then re-run every Stage A story against the
   non-production endpoint and convert the traceability matrix from "done against simulator" to
   "verified". Only then is FR-VMS-10 (F-13.4) evaluated.

**Do not plan capacity against F-13.4 or F-14.4.** One is a descope candidate; the other must not be
built at all.
