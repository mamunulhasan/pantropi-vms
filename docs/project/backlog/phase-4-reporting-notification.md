# Phase 4 — Reporting & Notification

**Baseline:** B1 · **Contract:** [`00-epic-feature-index.md`](00-epic-feature-index.md)
**TDD alignment:** §4.5 Reporting & Analytics Service, §8 Notification design, §3 technology stack
**Milestone goal:** the notification platform and the reporting suite.

---

## Phase summary

| Metric | Value |
|---|---|
| Epics | 4 (EPIC-16 … EPIC-19) |
| Features | 16 |
| User stories | 36 |
| Development tasks | 105 |
| Total story points | **166** |

| Epic | Features | Stories | Tasks | Points |
|---|---|---|---|---|
| EPIC-16 — Notification Platform | 5 | 13 | 39 | 62 |
| EPIC-17 — Notification Scenarios | 5 | 10 | 27 | 40 |
| EPIC-18 — Reporting Suite | 4 | 9 | 27 | 44 |
| EPIC-19 — Analytics & Export | 2 | 4 | 12 | 20 |

---

## ⚠️ Provenance warning — read this before planning a sprint

**This is the least SRS-backed phase in the project.** Only **four** SRS functional requirements
land in Phase 4:

| Requirement | Qualified reference | Lands in |
|---|---|---|
| Confirmation notification to host & visitor | `FR-NOT-01 (SRS B1)` | F-16.2, F-16.3, F-17.1 |
| Exit alerts to host & visitor | `FR-NOT-02 (SRS B1)` | F-17.2 |
| Daily / weekly / monthly visitor activity reports | `FR-REP-01 (SRS B1)` | F-18.1, F-18.3 |
| End-of-day credentials & cards report | `FR-REP-02 (SRS B1)` | F-18.2 |

Everything else in this phase is designed in the TDD and supported by the schema, but has **no
defined SRS requirement behind it**. The following IDs are cited by the TDD and referenced in this
backlog for traceability — they are **not defined in the attached 28-requirement SRS**:

`FR-NOT-03` (host notification) · `FR-NOT-04` (appointment reminder) · `FR-NOT-05` (system alert) ·
`FR-REP-06` (audit report) · `FR-ANL-01` (analytics dashboard) · `FR-EXP-01` (Excel/PDF export)

Per `01-requirements-catalogue.md` §5 and the provenance rule in the index, **no `TDD-DERIVED` story
in this phase may enter a sprint until TODO-01 is dispositioned.** They are planned, estimated and
sequenced here so the phase has a realistic shape and cost — that is all.

### Story provenance split

| Provenance | Stories | Points | Share |
|---|---|---|---|
| `SRS` — backed by FR-NOT-01/02, FR-REP-01/02 | **14** | 68 | 39% |
| `TDD-DERIVED` — backlog only pending TODO-01 | **18** | 74 | 50% |
| `BLOCKED` — gated on TODO-05 / TODO-10 | **4** | 16 | 11% |

**Half the story count and 45% of the points in this phase are not authorized for implementation
today.** If TODO-01 resolves as "the attached 28-requirement SRS is the delivery baseline," Phase 4
shrinks to roughly 68 points — EPIC-19 disappears entirely, EPIC-17 loses three of five features,
and EPIC-18 loses its audit report.

---

## Blocked features in this phase

| Feature | Gate | Effect |
|---|---|---|
| F-16.3 — WhatsApp channel | 🟠 **TODO-05** | Business API not approved. Schema already defaults `notification.whatsapp.enabled` to `false`. Build the port behind a disabled flag; **do not build the wire adapter**. Email proceeds regardless. |
| F-16.5 — Channel selection & recipient preferences | 🟠 **TODO-10** | `FR-NOT-01 (SRS B1)` says "email and/or WhatsApp" without saying *who chooses*. Per-recipient preference, per-tenant policy, and global setting are three different data models. Acceptance criteria here are deliberately conservative and resolve nothing. |
| F-19.2 — Excel & PDF export | 🟡 **TODO-16** | TDD §3/§4.5 require export (`FR-EXP-01`); **SRS §3.8 requires neither format**. The schema even seeds a `report.export` permission for a capability no requirement asks for. |

## ACS dependency — Phase 3 carry-through

Two SRS-backed areas of this phase cannot be verified without the ACS contract:

- **`FR-NOT-02 (SRS B1)`** — exit alerts are triggered by exit events arriving from ACS. Depends on
  **EPIC-11** (ACS integration client) and **EPIC-12** (access & exit event processing), and
  ultimately on 🔴 **TODO-02**.
- **`FR-REP-01 (SRS B1)`** — the activity report explicitly combines VMS pre-registration/approval
  data *with access and exit events retrieved from the ACS API*. Same dependency chain.

Per **ADR-0002**, both are implementable and testable against the ACS simulator. Neither can reach
Definition of Done in the client's sense until the real contract lands — they close at *"done against
simulator"*. Every affected story below is marked with an **ACS-gated** dependency note.

## 📌 Pull-forward recommendation — F-16.1 and F-16.2 into Phase 2

The notification service (F-16.1) and the email channel (F-16.2) subscribe **only to VMS domain
events** — `credential.issued`, `request.approved`, `visitor.checked_in`. They carry **no ACS
dependency whatsoever**.

Phase 2's **F-09.6 (pass delivery — email, on-screen, print)** implements `FR-VMS-07 (SRS B1)`, which
requires the credential be shareable *by email*. **Phase 2 cannot complete without an email
channel.** The index already flags this in its cross-phase note.

**Recommendation:** move F-16.1 and F-16.2 (33 points, 6 stories) into Phase 2 as scheduled work,
leaving Phase 4 to consume a platform that already exists and is production-hardened. This de-risks
the schedule twice over — it removes a false Phase 2 dependency, and it means the Phase 4 notification
scenarios (EPIC-17) start against a proven transport rather than a new one.

---

## Phase exit criteria

- [ ] `FR-NOT-01 (SRS B1)` demonstrated: visitor and host both receive a confirmation notification
      containing the QR credential and a welcome message, triggered by credential receipt from ACS.
- [ ] `FR-NOT-02 (SRS B1)` demonstrated: an ACS exit event triggers an automated exit alert to both
      host and visitor. *(Against simulator if TODO-02 is unresolved — record as such.)*
- [ ] `FR-REP-01 (SRS B1)` demonstrated: daily, weekly and monthly visitor activity reports generate
      automatically on schedule, combining VMS data with ACS access/exit events.
- [ ] `FR-REP-02 (SRS B1)` demonstrated: the central receptionist pulls an end-of-day report of
      credentials/cards issued and returned, reconciled against `vms.card_issuances`.
- [ ] Email delivery status is recorded in `vms.notification_logs` across the full
      `vms.delivery_status` lifecycle (`queued` → `sent` → `delivered` / `failed`).
- [ ] **No message body, QR payload, recipient email or visitor name appears in any application log
      or metric label.** `notification_logs.body_ref` holds a template id or storage reference only —
      never the rendered body. Verified by an automated log-scanning test.
- [ ] Every report and export endpoint enforces the `report.view` / `report.export` permission and
      applies role-based PII scoping. Verified by a deny-by-default authorization test per endpoint.
- [ ] Email gateway outage degrades gracefully: notifications queue durably, retry with backoff, and
      dead-letter with an operator alert. No visitor notification is silently lost.
- [ ] Reports over a period with zero data return an empty report, not an error.
- [ ] Report period boundaries are correct across a timezone/DST boundary, verified by test.
- [ ] WhatsApp remains **disabled** (`notification.whatsapp.enabled = false`) and no WhatsApp wire
      adapter has been built. TODO-05 disposition recorded.
- [ ] TODO-01 disposition recorded for every `TDD-DERIVED` story that shipped. Any that shipped
      without one is a process failure and is raised at sprint review.
- [ ] TODO-10 (channel selection) and TODO-16 (export formats) dispositions recorded.
- [ ] Architecture fitness tests pass — Reporting context holds **read models only** and writes to no
      other context's tables (per `05-workflow-and-branching.md` §8).

---

## EPIC-16 — Notification Platform

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` (TDD §8) with one `SRS`-backed feature and two `BLOCKED` features |
| **Requirement IDs** | `FR-NOT-01 (SRS B1)` — F-16.2 and F-16.3 only. The service, template and channel-selection layers have **no SRS backing** (TDD §8). |
| **Priority** | P0 — nothing in EPIC-17, and no report delivery in F-18.3, works without it |
| **Stories / Tasks / Points** | 13 · 39 · **62** |
| **Blocked** | F-16.3 (TODO-05), F-16.5 (TODO-10) |

**Goal.** Build the event-driven notification platform described in TDD §8: a Spring Boot service that
subscribes to VMS domain events on Kafka, resolves recipients and channels, renders a versioned
template, dispatches through a channel adapter, and records the outcome in `vms.notification_logs`
across the `vms.delivery_status` lifecycle. Only the email channel is authorized for release — the
schema's own seed data sets `notification.whatsapp.enabled` to `false`, and the SRS's "email and/or
WhatsApp" phrasing never says who chooses the channel. The platform is therefore built with a channel
abstraction and exactly one working adapter. Everything in this epic handles visitor PII and QR
credentials, so PII-safe logging is not a concern bolted on at the end — it is an acceptance criterion
on every story that touches a message body.

> **Note the provenance split inside this epic.** F-16.2 (email channel) is genuinely backed by
> `FR-NOT-01 (SRS B1)`. F-16.1 (the service itself) and F-16.4 (templates) are pure TDD §8 design —
> necessary to *deliver* FR-NOT-01, but not themselves required by any SRS statement. They are marked
> `TDD-DERIVED` honestly rather than laundered as enablers of an SRS requirement.

---

### F-16.1 — Notification service & domain event subscription

Event-driven notification service subscribing to VMS domain events on Kafka, with a durable dispatch
queue and idempotent delivery.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` (TDD §8, TDD §3) |
| **Priority** | P0 · **Points** 18 · **Stories** 3 |
| **Depends on** | EPIC-01 (F-01.3 Kafka, F-01.7 observability), EPIC-07 (F-07.5 domain events) |

---

#### US-16.1.1 — Subscribe to VMS domain events

**As a** System Administrator **I want** the notification service to consume VMS domain events from
Kafka **so that** a notification is triggered by what actually happened in the system rather than by a
caller remembering to send one.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 8 |
| **Provenance** | `TDD-DERIVED` (TDD §8 notification design, TDD §3 Kafka) — no SRS requirement defines a notification service |
| **Dependencies** | EPIC-01 F-01.3, EPIC-07 F-07.5 |
| **Blocked by** | — (TODO-01 disposition required before sprint entry) |

**Acceptance Criteria**
- **AC-1 — Given** a `credential.issued` domain event is published to Kafka **When** the notification
  service is running **Then** the event is consumed, a dispatch request is created, and the consumer
  offset advances only after that request is durably persisted.
- **AC-2 — Given** the service subscribes to the VMS event topics **When** an event type arrives that
  has no registered notification scenario **Then** the event is acknowledged and discarded without
  error and a counter metric increments — an unhandled event is not a failure.
- **AC-3 — Given** the service consumes an event **When** the event is logged for traceability
  **Then** the log line carries event id, event type, correlation id and entity UUIDs only —
  **no visitor name, email, phone or QR payload appears in any log line or metric label**.
- **AC-4 (negative) — Given** Kafka is unreachable at service startup **When** the service starts
  **Then** it starts in a degraded state, reports `DOWN` on the readiness probe, retries with backoff,
  and does not crash-loop.
- **AC-5 (negative) — Given** a malformed event payload that fails deserialization **When** it is
  consumed **Then** it is routed to a dead-letter topic with the payload PII-redacted, an error metric
  increments, and the consumer continues — one poison message does not halt the stream.

**Development Tasks**

**T-16.1.1.1 — Notification service module skeleton** · `P0` · `3 pts` · deps: `—`
- **Description:** Create the `notification` Spring Boot module following the Clean Architecture
  layering in `05-workflow-and-branching.md` §7 — `domain` (Notification aggregate and domain events,
  no framework imports), `application` (dispatch use cases plus `NotificationChannelPort`,
  `TemplateRendererPort`, `RecipientResolverPort`), `infrastructure` (Kafka consumer, JPA
  repositories), `interfaces` (status/admin REST controllers). Register it with the architecture
  fitness suite.
- **Acceptance Criteria:** module builds and is wired into the reactor build; fitness test asserts
  `domain` imports nothing from outer layers and carries no Spring or JPA annotation; fitness test
  asserts the service reads no other bounded context's tables directly; health and readiness endpoints
  respond.
- **Dependencies:** EPIC-01 F-01.2

**T-16.1.1.2 — Kafka consumer with manual offset commit** · `P0` · `2 pts` · deps: `T-16.1.1.1`
- **Description:** Implement the Kafka consumer in `infrastructure`, subscribing to the visitor
  request, credential and entry context topics. Use manual acknowledgement so the offset commits only
  after the dispatch request is persisted — at-least-once semantics that US-16.1.3's dedupe key then
  makes safe. Configure consumer group, concurrency, session and poll timeouts per TDD §3.
- **Acceptance Criteria:** consuming a published event creates a persisted dispatch request; killing
  the service between persist and commit then restarting redelivers rather than loses the event;
  offset does not advance on persist failure; consumer group id is environment-scoped.
- **Dependencies:** T-16.1.1.1

**T-16.1.1.3 — Event-to-scenario routing registry** · `P0` · `2 pts` · deps: `T-16.1.1.2`
- **Description:** Build the registry mapping a domain event type to zero or more notification
  scenarios, each declaring its `vms.notify_type` value (`confirmation`, `host`, `reminder`, `exit`,
  `system_alert`). Unregistered event types are acknowledged and counted, not errored. This registry
  is the extension point every EPIC-17 story plugs into.
- **Acceptance Criteria:** a registered event type produces one dispatch request per registered
  scenario; an unregistered type is acknowledged with a metric increment and no error; the registry
  fails at startup if a scenario declares a `notify_type` outside the `vms.notify_type` enum.
- **Dependencies:** T-16.1.1.2

**T-16.1.1.4 — PII-safe structured logging & poison-message handling** · `P0` · `1 pt` · deps: `T-16.1.1.2`
- **Description:** Configure structured logging emitting event id, event type, correlation id and
  entity UUIDs only. Add a deserialization-failure handler routing poison messages to a dead-letter
  topic with the payload redacted. Add an automated test that scans captured log output for PII
  patterns (email addresses, phone formats, QR payload prefixes).
- **Acceptance Criteria:** the log-scanning test fails the build if an email address, phone number or
  QR payload appears in log output during the consumer suite; a malformed payload reaches the
  dead-letter topic redacted; the consumer survives a poison message and keeps consuming.
- **Dependencies:** T-16.1.1.2, EPIC-01 F-01.7

---

#### US-16.1.2 — Durable dispatch queue with retry and dead-letter

**As a** System Administrator **I want** notification dispatch to be durably queued and retried
**so that** a transient gateway failure delays a visitor's confirmation rather than losing it.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` (TDD §8); reliability posture justified against `NFR-REL-01 (SRS B1)` |
| **Dependencies** | US-16.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a dispatch request in the queue **When** the worker picks it up **Then** exactly
  one worker instance processes it (row-level lock or equivalent) and the attempt is recorded before
  the channel adapter is called.
- **AC-2 — Given** a dispatch attempt fails with a retryable error **When** the retry policy applies
  **Then** the request is rescheduled with exponential backoff and jitter and the attempt count
  increments.
- **AC-3 — Given** a request exhausts its maximum attempts **When** the final attempt fails **Then**
  the `vms.notification_logs` row moves to `delivery_status = 'failed'`, a dead-letter record is
  written, and an operator alert event is emitted — **all in one transaction**, so a crash between
  them cannot lose the record.
- **AC-4 (negative) — Given** the gateway returns a permanent error (invalid recipient, 550 rejection)
  **When** the failure is classified **Then** it is treated as non-retryable, moves straight to
  `failed` without consuming retry attempts, and the classification is recorded.
- **AC-5 (negative, schema gap) — Given** `vms.notification_logs` has **no** `attempt_count`,
  `last_error` or `next_retry_at` column — unlike `vms.acs_requests`, which has all three — **When**
  retry state is persisted **Then** it lives in a notification-service-owned dispatch table and the
  gap is raised as a migration proposal. Retry state is **never** squeezed into `body_ref`.

**Development Tasks**

**T-16.1.2.1 — Dispatch queue table & migration** · `P1` · `2 pts` · deps: `T-16.1.1.1`
- **Description:** Add a `notification_dispatch` table owned by the notification service holding the
  state `vms.notification_logs` cannot express: `attempt_count`, `last_error`, `next_retry_at`,
  `dedupe_key`, `scenario_id`, and an FK to the `vms.notification_logs` row. It deliberately mirrors
  the shape `vms.acs_requests` already uses for outbound ACS calls. Ship as a migration per EPIC-01
  F-01.5 and raise the gap against the baseline DDL.
- **Acceptance Criteria:** migration applies and rolls back cleanly on Testcontainers PostgreSQL;
  index on `(status, next_retry_at)` mirrors `idx_acsreq_status`; unique index on `dedupe_key`;
  schema-gap note filed against `vms_schema_postgresql.sql`.
- **Dependencies:** T-16.1.1.1, EPIC-01 F-01.5

**T-16.1.2.2 — Dispatch worker with locking and backoff** · `P1` · `2 pts` · deps: `T-16.1.2.1`
- **Description:** Implement the polling dispatch worker claiming due rows with `SELECT … FOR UPDATE
  SKIP LOCKED` so multiple service instances never double-send. Apply exponential backoff with jitter,
  a capped attempt count read from `vms.system_settings`, and retryable-versus-permanent error
  classification supplied by the channel adapter.
- **Acceptance Criteria:** two concurrent worker instances against one queue produce exactly one send
  per request, proven by integration test; a retryable failure reschedules with increasing delay; a
  permanent failure does not retry; max attempts is configurable without redeploy.
- **Dependencies:** T-16.1.2.1

**T-16.1.2.3 — Dead-letter transaction & operator alert hook** · `P1` · `1 pt` · deps: `T-16.1.2.2`
- **Description:** On attempt exhaustion write the dead-letter record, set
  `vms.notification_logs.delivery_status = 'failed'`, and emit the operator-alert domain event in one
  transaction. F-17.5 consumes that alert once it exists; until then it surfaces as a metric and log
  event.
- **Acceptance Criteria:** a simulated crash between dead-letter write and status update leaves
  neither applied; `failed` status and dead-letter record always co-exist; dead-letter count is
  exposed as a metric; no message body is copied into the dead-letter record.
- **Dependencies:** T-16.1.2.2

---

#### US-16.1.3 — Idempotent dispatch and notification log persistence

**As a** host **I want** to receive exactly one notification per event **so that** a retry inside the
platform does not send me the same visitor confirmation three times.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` (TDD §8); the persistence target `vms.notification_logs` is schema-defined |
| **Dependencies** | US-16.1.1, US-16.1.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a dispatch request is created **When** it is persisted **Then** a row is written to
  `vms.notification_logs` with `recipient`, `channel` (`vms.notify_channel`), `notify_type`
  (`vms.notify_type`), `subject`, `body_ref` and `delivery_status = 'queued'`, plus `visitor_id`
  and/or `host_id` where applicable.
- **AC-2 — Given** the same domain event is delivered twice under at-least-once semantics **When**
  both deliveries are processed **Then** the dedupe key `(event_id, scenario_id, recipient, channel)`
  identifies the second as a duplicate, **exactly one** `vms.notification_logs` row exists, and
  exactly one message is sent.
- **AC-3 — Given** a notification is logged **When** the row is written **Then** `body_ref` holds a
  **template id and version, or a storage reference — never the rendered body**, honouring the
  schema's own comment on that column. The QR payload appears in neither `subject` nor `body_ref`.
- **AC-4 (negative) — Given** two service instances process the same event concurrently **When** both
  attempt to insert **Then** the unique index on the dedupe key makes one insert fail cleanly, that
  instance treats the conflict as "already handled", and no duplicate send occurs.
- **AC-5 (negative) — Given** an event whose recipient cannot be resolved — `vms.hosts.email` and
  `vms.visitors.email` are both nullable columns — **When** dispatch is attempted **Then** the request
  is recorded `failed` with a "recipient unresolvable" reason, no send is attempted, and the
  originating workflow is **not** blocked or rolled back.

**Development Tasks**

**T-16.1.3.1 — notification_logs repository & write path** · `P1` · `2 pts` · deps: `T-16.1.2.1`
- **Description:** Implement the JPA repository and mapping for `vms.notification_logs`, including the
  `vms.notify_channel`, `vms.notify_type` and `vms.delivery_status` PostgreSQL enum mappings. Enforce
  at the mapping layer that `body_ref` accepts only a reference token or storage URI, rejecting any
  value beyond a reference-length bound so a rendered body cannot be written by mistake.
- **Acceptance Criteria:** all three enum types round-trip correctly; writing a full rendered body to
  `body_ref` fails a unit test with a clear error; nullable `visitor_id` and `host_id` FKs behave per
  schema (`ON DELETE SET NULL`); repository integration-tested on Testcontainers PostgreSQL.
- **Dependencies:** T-16.1.2.1

**T-16.1.3.2 — Dedupe key derivation & uniqueness enforcement** · `P1` · `2 pts` · deps: `T-16.1.3.1`
- **Description:** Derive a deterministic dedupe key from `(event_id, scenario_id, recipient,
  channel)`, hashing the recipient so no email address is stored in the key. Enforce with the unique
  index from T-16.1.2.1 and treat the constraint violation as a benign "already handled" outcome
  rather than an error.
- **Acceptance Criteria:** replaying an identical event yields exactly one send and one log row; a
  concurrent insert conflict resolves without error or duplicate; the dedupe key contains no plaintext
  recipient address; changing any key component produces a distinct notification.
- **Dependencies:** T-16.1.3.1

**T-16.1.3.3 — Recipient resolution port with unresolvable handling** · `P1` · `1 pt` · deps: `T-16.1.3.1`
- **Description:** Define `RecipientResolverPort` in `application`, resolving a scenario plus entity
  ids to recipient addresses through the Visitor and Host contexts' published data — **not** by direct
  cross-context table reads. Treat the nullable `vms.visitors.email` and `vms.hosts.email` columns as
  an expected outcome, not an exception.
- **Acceptance Criteria:** the resolver returns a typed "unresolvable" result rather than throwing on
  a null email; an unresolvable notification is logged `failed` with a reason and no send attempt; a
  fitness test confirms the notification service never queries `vms.visitors` or `vms.hosts` directly.
- **Dependencies:** T-16.1.3.1

---


### F-16.2 — Email channel & delivery status logging

The email delivery channel and the `vms.delivery_status` lifecycle recorded against every message.

| | |
|---|---|
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)` — "via email and/or WhatsApp" |
| **Priority** | P0 · **Points** 15 · **Stories** 3 |
| **Depends on** | F-16.1 |

> 📌 **Pull-forward candidate.** Together with F-16.1 this feature depends only on VMS domain events —
> no ACS involvement. Phase 2's F-09.6 pass-delivery-by-email (`FR-VMS-07 (SRS B1)`) cannot complete
> without it. Recommend scheduling F-16.1 + F-16.2 in Phase 2.

---

#### US-16.2.1 — Send notifications over email

**As a** visitor **I want** to receive my visit notification by email **so that** I have my
credential and instructions before I arrive at the building.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)` |
| **Dependencies** | US-16.1.1, US-16.1.2, US-16.1.3 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a queued dispatch request with `channel = 'email'` **When** the worker processes it
  **Then** the email channel adapter sends the message through the configured gateway and the
  `vms.notification_logs` row moves to `delivery_status = 'sent'` with `sent_at` populated.
- **AC-2 — Given** the email adapter implements `NotificationChannelPort` **When** an architecture
  fitness test inspects the layering **Then** no gateway-specific type (SMTP session, provider SDK
  class) appears outside the email adapter package — the same isolation rule CON-01/NFR-MNT-01 impose
  on the ACS boundary, applied here by analogy.
- **AC-3 — Given** `notification.email.enabled` in `vms.system_settings` is `false` **When** an email
  dispatch is attempted **Then** no message is sent, the log row records a "channel disabled" outcome,
  and no error is raised to the originating workflow.
- **AC-4 (negative) — Given** the email gateway is unreachable or times out **When** the send is
  attempted **Then** the error is classified retryable, the request returns to the queue with backoff
  per US-16.2.3, and the row stays `queued` rather than falsely reporting `sent`.
- **AC-5 (negative) — Given** gateway credentials are rejected (authentication failure) **When** the
  send is attempted **Then** the failure is classified as an operator problem, an alert is raised, and
  **the credentials never appear in a log message or error response**.

**Development Tasks**

**T-16.2.1.1 — NotificationChannelPort and email adapter** · `P0` · `2 pts` · deps: `T-16.1.1.1`
- **Description:** Define `NotificationChannelPort` in `application` with a channel-agnostic send
  contract returning a typed outcome (sent, retryable failure, permanent failure, channel disabled).
  Implement the email adapter in `infrastructure` over Spring Mail / the configured gateway, with
  connection pooling and timeouts. Gateway configuration and secrets resolve through the EPIC-05 F-05.2
  secrets mechanism — never from a properties file in the repository.
- **Acceptance Criteria:** port carries no email-specific type in its signature; fitness test asserts
  no gateway SDK type escapes the adapter package; connect and read timeouts are configured and
  enforced; credentials load from the secrets provider and are absent from logs and stack traces.
- **Dependencies:** T-16.1.1.1, EPIC-05 F-05.2

**T-16.2.1.2 — Error classification and channel master switch** · `P0` · `2 pts` · deps: `T-16.2.1.1`
- **Description:** Implement classification of gateway responses into retryable (timeout, connection
  refused, 4xx throttle, 5xx) versus permanent (invalid address, 550 rejection, message-too-large),
  which drives the US-16.1.2 retry policy. Wire the `notification.email.enabled` master switch from
  `vms.system_settings` so the channel can be disabled at runtime without redeploy.
- **Acceptance Criteria:** each simulated gateway error maps to the documented classification, covered
  by unit tests; toggling `notification.email.enabled` to `false` suppresses sends within the setting
  cache TTL; a disabled channel records a distinct outcome, not a failure; classification decision is
  recorded on the dispatch row.
- **Dependencies:** T-16.2.1.1

**T-16.2.1.3 — Email gateway simulator for integration tests** · `P0` · `1 pt` · deps: `T-16.2.1.1`
- **Description:** Provide a test-scope SMTP/gateway simulator (GreenMail or equivalent) with
  configurable latency and failure injection, following the same strategy ADR-0002 sets for the ACS
  simulator. Integration tests assert on captured messages without reaching an external service.
- **Acceptance Criteria:** integration suite runs fully offline; simulator injects timeout, auth
  failure, permanent rejection and success on demand; captured message assertions cover recipient,
  subject and template reference; simulator is test-scope only and cannot ship in a production
  artifact.
- **Dependencies:** T-16.2.1.1

---

#### US-16.2.2 — Record delivery status through its full lifecycle

**As an** FM Admin **I want** every notification's delivery status recorded **so that** when a visitor
says they never received their pass I can tell whether we sent it, whether it was delivered, and when.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)`; status enum `vms.delivery_status` is schema-defined |
| **Dependencies** | US-16.2.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a notification progresses through dispatch **When** each stage completes **Then**
  `vms.notification_logs.delivery_status` transitions `queued` → `sent` → `delivered` or `failed`,
  and `sent_at` is stamped at the `sent` transition.
- **AC-2 — Given** the gateway supports delivery receipts or webhooks **When** a delivery confirmation
  arrives for a message **Then** the matching row moves to `delivered`; **and given** no receipt
  mechanism is available **Then** `sent` is the terminal success state and that limitation is
  documented rather than `delivered` being written speculatively.
- **AC-3 — Given** an FM Admin queries notification history for a visitor **When** the results return
  **Then** they show recipient, channel, type, status and timestamps — and **never the message body**,
  because the body was never stored (`body_ref` holds a template reference only).
- **AC-4 (negative) — Given** a delivery-status callback arrives for a message id that does not exist,
  or arrives twice **When** it is processed **Then** the unknown id is logged and ignored without
  error, and the duplicate is idempotent — no status regresses from `delivered` back to `sent`.
- **AC-5 (negative) — Given** a bounced delivery notification arrives after the row already reads
  `sent` **When** the bounce is processed **Then** the row moves to `failed` with the bounce reason
  recorded, and an alert is raised if the bounce rate for a period exceeds a configured threshold.

**Development Tasks**

**T-16.2.2.1 — Delivery status state machine** · `P1` · `2 pts` · deps: `T-16.1.3.1`
- **Description:** Implement the `vms.delivery_status` transition state machine in `domain`, allowing
  only `queued`→`sent`, `queued`→`failed`, `sent`→`delivered`, `sent`→`failed`, and rejecting every
  other transition including any regression from a terminal state. Stamp `sent_at` on the `sent`
  transition.
- **Acceptance Criteria:** every illegal transition is rejected with a typed error, covered by unit
  tests over the full transition matrix; `delivered` never regresses to `sent`; `sent_at` is set once
  and never overwritten; the state machine sits in `domain` with no framework imports.
- **Dependencies:** T-16.1.3.1

**T-16.2.2.2 — Delivery receipt and bounce ingestion** · `P1` · `2 pts` · deps: `T-16.2.2.1`
- **Description:** Build the inbound endpoint or poller that ingests gateway delivery receipts and
  bounce notifications, correlating them to a `vms.notification_logs` row by an opaque provider
  message id stored on the dispatch row. Handle unknown ids and duplicates idempotently. Add a bounce
  rate metric with a configurable alert threshold feeding F-17.5.
- **Acceptance Criteria:** a receipt moves the row to `delivered`; a bounce after `sent` moves it to
  `failed` with reason; an unknown message id is logged and ignored without a 5xx; a duplicate
  callback is a no-op; the inbound endpoint authenticates the caller and rejects unsigned callbacks.
- **Dependencies:** T-16.2.2.1

**T-16.2.2.3 — Notification history query endpoint** · `P1` · `1 pt` · deps: `T-16.2.2.1`
- **Description:** Expose a paginated read endpoint in `interfaces` returning notification history
  filtered by visitor, host, type, channel, status and date range. Enforce the RBAC permission from
  EPIC-03 and return no message body — the body is not stored, and the endpoint must not reconstruct
  it by re-rendering the template.
- **Acceptance Criteria:** endpoint denies by default without the required permission; response
  contains no body, QR payload or template variable values; pagination is stable under concurrent
  writes; queries use `idx_notif_visitor` and are verified by an execution-plan assertion.
- **Dependencies:** T-16.2.2.1, EPIC-03 F-03.2

---

#### US-16.2.3 — Survive an email gateway outage

**As a** Master Admin **I want** notifications to survive an email gateway outage **so that** an
infrastructure problem at 09:00 does not silently cost every morning visitor their credential email.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)`; resilience posture justified against `NFR-REL-01 (SRS B1)` and `NFR-AVL-01 (SRS B1)` |
| **Dependencies** | US-16.2.1, US-16.1.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the email gateway is unavailable **When** notifications are triggered during the
  outage **Then** every one is durably queued, none is lost, and the originating VMS workflow
  (credential issuance, approval) completes normally — notification failure never blocks the visitor
  journey.
- **AC-2 — Given** the gateway recovers **When** the worker resumes **Then** the queued backlog drains
  in creation order, rate-limited to the gateway's documented throughput so recovery does not trigger
  a second outage.
- **AC-3 — Given** repeated consecutive gateway failures **When** the failure count crosses the
  configured threshold **Then** a circuit breaker opens, further attempts short-circuit until the
  half-open probe succeeds, and an operator alert is raised.
- **AC-4 (negative) — Given** an outage longer than the maximum retry window **When** requests exhaust
  their attempts **Then** they move to `failed`, are dead-lettered, and appear on an operator
  reconciliation view — a stale notification is not sent hours late without visibility.
- **AC-5 (negative) — Given** the backlog drains after a long outage **When** a queued appointment
  reminder or exit alert has become factually stale (the visit already ended) **Then** it is suppressed
  with a recorded reason rather than sent, and the suppression is counted.

**Development Tasks**

**T-16.2.3.1 — Circuit breaker and throughput limiting** · `P1` · `2 pts` · deps: `T-16.2.1.2`
- **Description:** Wrap the email adapter in a circuit breaker (Resilience4j per TDD §3) with
  configurable failure-rate threshold, open duration and half-open probe. Add a rate limiter on
  backlog drain so recovery respects the gateway's throughput ceiling.
- **Acceptance Criteria:** breaker opens after the configured consecutive failure count; open breaker
  short-circuits without calling the gateway; half-open probe closes on success and re-opens on
  failure; drain rate stays within the configured ceiling under a 500-message backlog test; breaker
  state is exposed as a metric.
- **Dependencies:** T-16.2.1.2

**T-16.2.3.2 — Staleness suppression on backlog drain** · `P1` · `2 pts` · deps: `T-16.1.2.2`
- **Description:** Add a per-scenario staleness policy evaluated immediately before send. Time-sensitive
  scenarios (`reminder`, `exit`) declare a validity horizon; a request older than its horizon, or whose
  subject visit has already reached a terminal status, is suppressed with a recorded reason rather than
  dispatched. Non-time-sensitive scenarios (`confirmation`) have no horizon and always send.
- **Acceptance Criteria:** a reminder queued before an appointment that has since ended is suppressed,
  not sent; a confirmation delayed by an outage is still sent; suppression reason is persisted and
  counted as a metric; the horizon is configurable per scenario; suppression never applies to
  `confirmation`.
- **Dependencies:** T-16.1.2.2

**T-16.2.3.3 — Operator reconciliation view for failed notifications** · `P1` · `1 pt` · deps: `T-16.2.2.3`
- **Description:** Extend the notification history endpoint with an operator view filtered to `failed`
  and dead-lettered notifications over a date range, showing failure classification and attempt count,
  with a manual re-queue action guarded by an admin permission and written to `vms.audit_logs`.
- **Acceptance Criteria:** view lists failed notifications with reason and attempt count; re-queue
  requires an admin permission and denies by default; every re-queue writes an audit entry with actor,
  action and notification id; re-queue re-evaluates staleness rather than blindly sending.
- **Dependencies:** T-16.2.2.3, EPIC-05 F-05.1

---

### F-16.3 — WhatsApp channel ⛔ BLOCKED

WhatsApp Business API delivery channel — **port only, wire adapter not authorized**.

| | |
|---|---|
| **Provenance** | `BLOCKED` — `FR-NOT-01 (SRS B1)` mentions WhatsApp · gated on 🟠 **TODO-05** |
| **Priority** | P3 · **Points** 8 · **Stories** 2 |
| **Depends on** | F-16.1, F-16.2 |

> ⛔ **Blocked on TODO-05 — WhatsApp Business API approval status.** The Business API is not approved.
> The schema itself already seeds `notification.whatsapp.enabled` to `false` with the description
> *"WhatsApp disabled until Business API approval"* — the source documents have already decided this
> is off for release 1.0 unless something changes.
>
> **Strategy:** build the channel abstraction and a simulator-backed stub **behind a permanently
> disabled feature flag**, so that if approval lands the wire adapter is a contained piece of work
> rather than a re-architecture. **Do not build the wire-level adapter.** Email proceeds regardless and
> carries the whole of `FR-NOT-01 (SRS B1)` on its own — the requirement says "and/or".

---

#### US-16.3.1 — WhatsApp channel port behind a disabled flag

**As a** System Administrator **I want** the WhatsApp channel represented as a port behind a disabled
flag **so that** the platform is not email-shaped in a way that makes adding WhatsApp later expensive.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 3 |
| **Provenance** | `BLOCKED` TODO-05 · scaffolding justified against `NFR-MNT-01 (SRS B1)` |
| **Dependencies** | US-16.2.1 |
| **Blocked by** | 🟠 **TODO-05** — no wire adapter until the Business API is approved |

**Acceptance Criteria**
- **AC-1 — Given** the `vms.notify_channel` enum includes `whatsapp` **When** the channel registry is
  loaded **Then** a WhatsApp channel implementation of `NotificationChannelPort` is registered but
  reports itself disabled, and the platform starts normally.
- **AC-2 — Given** `notification.whatsapp.enabled` is `false` — its seeded default — **When** any
  notification resolves to the WhatsApp channel **Then** no send is attempted, the log row records a
  "channel disabled" outcome, and the notification is **not** silently re-routed to email without an
  explicit fallback policy (which TODO-10 has not yet defined).
- **AC-3 (negative) — Given** an operator sets `notification.whatsapp.enabled` to `true` while no wire
  adapter exists **When** a WhatsApp dispatch is attempted **Then** it fails fast with an explicit "not
  implemented, blocked on TODO-05" outcome and raises an operator alert — it does not hang, and it does
  not appear to succeed.

**Development Tasks**

**T-16.3.1.1 — WhatsApp channel stub implementing the port** · `P3` · `1 pt` · deps: `T-16.2.1.1`
- **Description:** Implement a WhatsApp `NotificationChannelPort` in `infrastructure` that reports
  disabled and fails fast with an explicit not-implemented outcome referencing TODO-05. No HTTP client,
  no Business API SDK dependency is added to the build.
- **Acceptance Criteria:** stub registers as the `whatsapp` channel; returns "channel disabled" while
  the flag is false; returns explicit not-implemented when force-enabled; no WhatsApp SDK or HTTP
  client dependency appears in the dependency tree, asserted by a build check.
- **Dependencies:** T-16.2.1.1

**T-16.3.1.2 — Feature flag wiring from system_settings** · `P3` · `1 pt` · deps: `T-16.3.1.1`
- **Description:** Wire `notification.whatsapp.enabled` from `vms.system_settings` through the channel
  registry with a startup assertion that the value is `false` in every non-development environment
  until TODO-05 is dispositioned.
- **Acceptance Criteria:** flag reads from `vms.system_settings` not from application properties;
  production and staging profiles fail startup validation if the flag is `true`; flag state is visible
  on an admin status endpoint; changing the flag is audited to `vms.audit_logs`.
- **Dependencies:** T-16.3.1.1

**T-16.3.1.3 — Channel-disabled outcome handling and documentation** · `P3` · `1 pt` · deps: `T-16.3.1.1`
- **Description:** Ensure a channel-disabled outcome is a distinct terminal state, not a failure, so
  disabled-channel notifications do not pollute the failure rate metric or trigger the F-16.2 bounce
  alert. Record the TODO-05 dependency in the module README and the traceability matrix.
- **Acceptance Criteria:** disabled outcome is excluded from failure-rate metrics and alerting;
  distinct metric counts disabled-channel notifications; traceability matrix row links F-16.3 to
  TODO-05; module README states the wire adapter is not authorized.
- **Dependencies:** T-16.3.1.1

---

#### US-16.3.2 — WhatsApp Business API adapter ⛔ DO NOT BUILD

**As a** visitor **I want** to receive my visit confirmation on WhatsApp **so that** I have my QR code
in the messaging app I actually use.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` — `FR-NOT-01 (SRS B1)` names WhatsApp but 🟠 **TODO-05** is unresolved |
| **Dependencies** | US-16.3.1 |
| **Blocked by** | 🟠 **TODO-05** — **this story must not enter a sprint** |

> ⛔ **Carried in the backlog for cost visibility only.** The acceptance criteria below are provisional
> and will change once the approved Business API contract is known — template pre-approval rules,
> the 24-hour customer service window, and per-message pricing all materially affect the design. They
> are written to size the work, not to be built against.

**Acceptance Criteria (provisional — do not implement)**
- **AC-1 — Given** the Business API is approved and `notification.whatsapp.enabled` is `true` **When**
  a notification resolves to WhatsApp **Then** the message is sent via a pre-approved message template
  and the `vms.notification_logs` row records `channel = 'whatsapp'` with the delivery lifecycle.
- **AC-2 — Given** WhatsApp requires pre-approved templates for business-initiated messages **When** a
  template is dispatched **Then** the platform sends a registered template identifier with parameters,
  **not** free-form text, and a template whose approval has lapsed is rejected before send.
- **AC-3 (negative) — Given** a recipient has no WhatsApp account, has blocked the business, or the
  message falls outside the permitted messaging window **When** the send is attempted **Then** the
  failure is classified permanent, the row moves to `failed`, and the fallback behaviour follows
  whatever policy TODO-10 defines — **not** an assumption made here.

**Development Tasks (provisional — do not implement)**

**T-16.3.2.1 — Business API client and authentication** · `P3` · `2 pts` · deps: `T-16.3.1.1`
- **Description:** *(Blocked.)* Implement the WhatsApp Business API HTTP client with token
  authentication resolved through EPIC-05 F-05.2 secrets, retry/backoff, and provider error mapping
  into the US-16.2.1 classification scheme.
- **Acceptance Criteria:** blocked pending TODO-05 — provisional criteria are the approved contract's
  auth flow, timeouts, rate-limit handling and error taxonomy.
- **Dependencies:** T-16.3.1.1, **TODO-05**

**T-16.3.2.2 — Template registration and parameter mapping** · `P3` · `2 pts` · deps: `T-16.3.2.1`
- **Description:** *(Blocked.)* Map F-16.4 internal templates to pre-approved WhatsApp templates,
  including QR delivery as a media attachment and parameter ordering per the approved template
  definition.
- **Acceptance Criteria:** blocked pending TODO-05 — provisional criteria are template id resolution,
  parameter validation before send, and rejection of lapsed template approvals.
- **Dependencies:** T-16.3.2.1, **TODO-05**

**T-16.3.2.3 — Delivery receipt webhook ingestion** · `P3` · `1 pt` · deps: `T-16.3.2.1`
- **Description:** *(Blocked.)* Ingest WhatsApp delivery and read receipts into the US-16.2.2 status
  state machine, with webhook signature verification.
- **Acceptance Criteria:** blocked pending TODO-05 — provisional criteria are signature verification,
  idempotent receipt handling, and mapping provider statuses onto `vms.delivery_status`.
- **Dependencies:** T-16.3.2.1, T-16.2.2.2, **TODO-05**

---

### F-16.4 — Notification templates & localisation

Versioned, reviewable message templates with safe rendering and locale/timezone awareness.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` (TDD §8) — no SRS requirement defines templating or localisation |
| **Priority** | P1 · **Points** 13 · **Stories** 3 |
| **Depends on** | F-16.1 |

> `FR-NOT-01 (SRS B1)` requires the confirmation to include "the QR code and a welcome message". That
> is the only content the SRS specifies anywhere. Versioning, localisation and a template registry are
> TDD §8 design decisions — sensible, but unrequirmented.

---

#### US-16.4.1 — Versioned template registry

**As an** FM Admin **I want** notification templates versioned and referenced by id **so that** a
`vms.notification_logs.body_ref` from six months ago still identifies exactly what we sent.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` (TDD §8); `body_ref` semantics are schema-defined |
| **Dependencies** | US-16.1.3 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a template exists **When** it is referenced **Then** it is addressed by a stable
  `(template_id, version)` pair, and that pair is exactly what is written to
  `vms.notification_logs.body_ref` — satisfying the column's "template id or storage ref, not full
  body" contract.
- **AC-2 — Given** a template is edited **When** the change is saved **Then** a new version is created
  and prior versions remain immutable and resolvable, so a historic `body_ref` never silently changes
  meaning.
- **AC-3 — Given** each notification scenario **When** the registry loads at startup **Then** every
  registered scenario resolves to an existing template for every enabled channel, and startup fails
  loudly if one does not.
- **AC-4 (negative) — Given** a `body_ref` referencing a template version that no longer exists (a bad
  migration, a purged record) **When** notification history is rendered **Then** the entry displays
  with an "unresolvable template reference" marker rather than erroring the whole query.
- **AC-5 (negative) — Given** a template body containing a variable placeholder no scenario supplies
  **When** the template is validated at registration **Then** registration is rejected with the
  offending placeholder named — a missing variable is caught at deploy time, not at send time in front
  of a visitor.

**Development Tasks**

**T-16.4.1.1 — Template storage and versioning model** · `P1` · `2 pts` · deps: `T-16.1.3.1`
- **Description:** Add a notification-service-owned template table holding `template_id`, `version`,
  `channel`, `locale`, `subject_template`, `body_template` and `is_active`, with prior versions
  immutable. Templates ship as migration seed data so they are code-reviewed and environment-consistent
  rather than hand-edited in production.
- **Acceptance Criteria:** `(template_id, version, channel, locale)` is unique; an update creates a new
  version and never mutates an existing row, enforced by a DB rule or trigger; seed templates load via
  migration; resolving a historic version returns the original content byte-for-byte.
- **Dependencies:** T-16.1.3.1

**T-16.4.1.2 — Registry resolution and startup validation** · `P1` · `2 pts` · deps: `T-16.4.1.1`
- **Description:** Implement `TemplateRegistry` in `application` resolving scenario plus channel plus
  locale to a concrete template version, with a startup validation pass asserting every registered
  scenario resolves for every enabled channel and that every placeholder in each template is satisfiable
  from that scenario's declared variable set.
- **Acceptance Criteria:** startup fails with a named scenario and channel when a template is missing;
  registration of a template with an unsatisfiable placeholder is rejected naming the placeholder;
  resolution is cached in Redis per TDD §3 with invalidation on template change.
- **Dependencies:** T-16.4.1.1

**T-16.4.1.3 — body_ref writing and unresolvable-reference handling** · `P1` · `1 pt` · deps: `T-16.4.1.2`
- **Description:** Write the resolved `(template_id, version)` into `vms.notification_logs.body_ref` in
  a documented, parseable format, and handle unresolvable references gracefully in the history query
  from T-16.2.2.3.
- **Acceptance Criteria:** `body_ref` format is documented and round-trip parseable; an unresolvable
  reference renders as a marker and does not error the history query; `body_ref` never contains
  rendered content or variable values, verified by test.
- **Dependencies:** T-16.4.1.2, T-16.2.2.3

---

#### US-16.4.2 — Render templates safely

**As a** visitor **I want** the email I receive to be correctly formatted with my details **so that**
I can actually use the pass instead of receiving a broken message.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` (TDD §8); QR content requirement from `FR-NOT-01 (SRS B1)` |
| **Dependencies** | US-16.4.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a resolved template and a scenario variable set **When** the message is rendered
  **Then** all placeholders are substituted, and the rendered body is passed directly to the channel
  adapter **without ever being persisted**.
- **AC-2 — Given** a variable value containing HTML or script content — a visitor name entered as
  markup — **When** it is substituted into an HTML email **Then** it is context-appropriately escaped,
  and an XSS payload in `vms.visitors.full_name` cannot execute in the recipient's mail client.
- **AC-3 — Given** the confirmation scenario **When** the message is rendered **Then** the QR credential
  is embedded per `FR-NOT-01 (SRS B1)` (inline image or attachment), and the QR payload appears in
  **neither** the log, the `subject`, nor `body_ref`.
- **AC-4 (negative) — Given** template rendering throws — malformed template syntax, a null variable
  with no default **When** the failure occurs **Then** the dispatch is marked `failed` with a "render
  failure" classification, the failure is **non-retryable** (retrying a broken template just fails
  again), an operator alert is raised, and **no partially rendered message is sent**.
- **AC-5 (negative) — Given** the rendered message exceeds the gateway's size limit — typically a large
  embedded QR image **When** the size check runs before send **Then** the send is rejected with a
  permanent classification and a clear reason, rather than being attempted and bounced.

**Development Tasks**

**T-16.4.2.1 — Rendering engine with context-aware escaping** · `P1` · `2 pts` · deps: `T-16.4.1.2`
- **Description:** Implement `TemplateRendererPort` and its adapter over a sandboxed template engine
  (Thymeleaf or Freemarker with a restricted resolver, per TDD §3), with HTML-context escaping on every
  substituted variable and no template-side expression access to application beans or the filesystem.
- **Acceptance Criteria:** an XSS payload in a visitor name renders escaped and inert, covered by test;
  the template engine cannot invoke application beans, read files, or reach the classpath; a plain-text
  alternative part is generated alongside the HTML part; rendering has a timeout.
- **Dependencies:** T-16.4.1.2

**T-16.4.2.2 — QR embedding and message size guard** · `P1` · `2 pts` · deps: `T-16.4.2.1`
- **Description:** Embed the QR credential in the confirmation message as an inline (CID) image or
  attachment, sourcing the payload through the Credential context's published data rather than a direct
  read of `vms.credentials`. Add a pre-send size guard against the configured gateway limit.
- **Acceptance Criteria:** QR renders and scans correctly in the integration test; the QR payload
  appears in no log line, `subject` or `body_ref`; a message over the size limit is rejected before
  send with a permanent classification; a fitness test confirms no direct `vms.credentials` read.
- **Dependencies:** T-16.4.2.1

**T-16.4.2.3 — Render failure classification and alerting** · `P1` · `1 pt` · deps: `T-16.4.2.1`
- **Description:** Classify render failures as permanent, mark the dispatch `failed` without consuming
  retry attempts, emit an operator alert, and guarantee no partial output reaches the channel adapter.
  Add a template smoke-render test over every registered template with representative variables, run in
  CI.
- **Acceptance Criteria:** render failure never retries; no partial message is dispatched; the operator
  alert names template id and version; CI smoke-renders every registered template and fails the build
  on any render error.
- **Dependencies:** T-16.4.2.1

---

#### US-16.4.3 — Render dates and times correctly for the recipient

**As a** visitor **I want** appointment times shown in the building's local timezone **so that** I
arrive at the right hour.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` (TDD §8); correctness concern arising from `timestamptz` columns throughout the schema |
| **Dependencies** | US-16.4.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** an appointment stored in `vms.visitors.appointment_from` as `timestamptz` **When**
  it is rendered into a message **Then** it displays in the building's configured local timezone with
  the zone named explicitly, never as a raw UTC instant.
- **AC-2 — Given** a locale is configured **When** a message renders **Then** date, time and number
  formats follow that locale, and the correct locale variant of the template is selected if one exists.
- **AC-3 (negative) — Given** no template exists for the requested locale **When** resolution runs
  **Then** it falls back to the default locale deterministically, records that a fallback occurred, and
  does not fail the send.
- **AC-4 (negative) — Given** an appointment falling on a daylight-saving transition **When** the time
  is rendered **Then** it is correct on both sides of the transition, verified by a test using an
  actual DST-observing zone — this is the same class of boundary bug that F-18.1's period reports must
  handle.

**Development Tasks**

**T-16.4.3.1 — Timezone and locale rendering helpers** · `P2` · `2 pts` · deps: `T-16.4.2.1`
- **Description:** Add rendering helpers that convert `timestamptz` values into the building timezone
  (from `vms.system_settings`) and format them per the resolved locale, exposed to templates as a
  restricted helper rather than allowing templates to call date APIs directly.
- **Acceptance Criteria:** rendered times carry an explicit zone designation; DST transition test
  passes on both sides using a DST-observing zone; templates cannot invoke arbitrary date or system
  APIs; the building timezone is a single configured source, not hard-coded.
- **Dependencies:** T-16.4.2.1

**T-16.4.3.2 — Locale resolution with deterministic fallback** · `P2` · `1 pt` · deps: `T-16.4.1.2`
- **Description:** Implement locale resolution for a notification — recipient locale where known,
  otherwise the system default — with deterministic fallback to the default locale template and a
  recorded fallback indicator.
- **Acceptance Criteria:** resolution order is documented and covered by test; a missing locale variant
  falls back without failing; the fallback is counted as a metric; the resolved locale is recorded on
  the dispatch row.
- **Dependencies:** T-16.4.1.2

---

### F-16.5 — Channel selection & recipient preferences ⛔ BLOCKED

Deciding which channel a given notification goes out on.

| | |
|---|---|
| **Provenance** | `BLOCKED` — gated on 🟠 **TODO-10** |
| **Priority** | P2 · **Points** 8 · **Stories** 2 |
| **Depends on** | F-16.2, F-16.3 |

> ⛔ **Blocked on TODO-10.** `FR-NOT-01 (SRS B1)` says the notification goes out "via email and/or
> WhatsApp" and **never says who chooses**. Per-recipient preference, per-tenant policy, and a global
> system setting are three different data models with three different administration surfaces and three
> different migration paths. Choosing one here would be exactly the guess
> `05-workflow-and-branching.md` §10 forbids.
>
> **Carve-out.** US-16.5.1 builds only the resolution *seam* with the single defensible default — the
> global setting that already exists in the schema. US-16.5.2 holds the preference model and **must not
> enter a sprint** until TODO-10 resolves.

---

#### US-16.5.1 — Resolve the delivery channel through a single seam

**As a** System Administrator **I want** channel selection isolated behind one resolution point
**so that** whichever selection rule TODO-10 lands on, it changes one component rather than every
notification scenario.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `BLOCKED` TODO-10 (carved-out portion) · seam justified against `NFR-MNT-01 (SRS B1)` |
| **Dependencies** | US-16.2.1, US-16.3.1 |
| **Blocked by** | 🟠 **TODO-10** for anything beyond the global-setting default |

**Acceptance Criteria**
- **AC-1 — Given** a notification is being dispatched **When** its channel is determined **Then** the
  decision comes from a single `ChannelResolverPort` and no scenario, template or adapter selects a
  channel independently.
- **AC-2 — Given** TODO-10 is unresolved **When** the resolver runs **Then** it applies the only rule
  the source documents support: the global master switches in `vms.system_settings`
  (`notification.email.enabled = true`, `notification.whatsapp.enabled = false`), yielding email for
  every notification. **This is a documented provisional default, recorded as such, not a decision.**
- **AC-3 — Given** the resolver returns a channel set **When** it is consumed **Then** it supports
  returning *multiple* channels, because `FR-NOT-01 (SRS B1)` says "and/or" — the seam does not
  presuppose a single-channel answer.
- **AC-4 (negative) — Given** every candidate channel is disabled **When** resolution runs **Then** the
  notification is recorded with a "no channel available" outcome and an operator alert is raised — it
  is not silently dropped, and the originating workflow is not blocked.
- **AC-5 (negative) — Given** the resolver is asked for `in_app`, a value present in the
  `vms.notify_channel` enum **When** resolution runs **Then** it reports the channel as unimplemented,
  since **no SRS requirement and no TDD §8 scenario describes in-app notification** — the enum value is
  an unexplained schema artifact, logged as such.

**Development Tasks**

**T-16.5.1.1 — ChannelResolverPort with global-setting strategy** · `P2` · `1 pt` · deps: `T-16.2.1.2`
- **Description:** Define `ChannelResolverPort` in `application` returning an ordered channel set for a
  given scenario and recipient, with a single implementation reading the global
  `notification.*.enabled` switches from `vms.system_settings`. Document in code that this is
  provisional pending TODO-10.
- **Acceptance Criteria:** port returns a set, not a single value; the global strategy yields email only
  under seeded settings; a fitness test asserts no scenario or adapter picks a channel outside the
  resolver; the provisional status is recorded in the module README and the traceability matrix.
- **Dependencies:** T-16.2.1.2

**T-16.5.1.2 — No-channel-available and unimplemented-channel handling** · `P2` · `1 pt` · deps: `T-16.5.1.1`
- **Description:** Handle the empty resolution result as a distinct recorded outcome with an operator
  alert, and treat `in_app` as explicitly unimplemented. Raise the unexplained `in_app` enum value as a
  documentation query against the schema.
- **Acceptance Criteria:** empty resolution records a "no channel available" outcome and alerts; the
  originating workflow is unaffected; `in_app` returns an unimplemented outcome rather than failing
  obscurely; schema query filed regarding the `in_app` enum value.
- **Dependencies:** T-16.5.1.1

**T-16.5.1.3 — Settings cache and runtime invalidation** · `P2` · `1 pt` · deps: `T-16.5.1.1`
- **Description:** Cache the channel master switches in Redis per TDD §3 with a bounded TTL and
  event-driven invalidation on `vms.system_settings` change, so toggling a channel takes effect quickly
  without a database read on every dispatch.
- **Acceptance Criteria:** resolution does not hit the database per message under load test; a settings
  change propagates within the documented TTL; cache miss falls back to a database read rather than a
  stale default; the cache key is environment-scoped.
- **Dependencies:** T-16.5.1.1, EPIC-04 F-04.9

---

#### US-16.5.2 — Recipient and tenant channel preferences ⛔ DO NOT BUILD

**As an** FM Admin **I want** control over which channel each recipient receives notifications on
**so that** notification delivery matches how each tenant and visitor prefers to be contacted.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` — 🟠 **TODO-10**; `FR-NOT-01 (SRS B1)` does not state the selection rule |
| **Dependencies** | US-16.5.1 |
| **Blocked by** | 🟠 **TODO-10** — **this story must not enter a sprint** |

> ⛔ **Held for cost visibility.** The three candidate models differ materially. Per-recipient preference
> needs a preference store, a capture surface (visitors are not VMS users and have no login), and a
> consent posture. Per-tenant policy needs administration under EPIC-04 and interacts with the
> unresolved tenant-scoping question in TODO-14. Global setting is already built in US-16.5.1. The
> criteria below are deliberately model-neutral and **resolve nothing**.

**Acceptance Criteria (provisional — do not implement)**
- **AC-1 — Given** TODO-10 has been answered **When** the selection rule is implemented **Then** it is
  implemented **behind the existing `ChannelResolverPort`** with no change to any notification scenario.
- **AC-2 — Given** whichever precedence order TODO-10 establishes **When** channel resolution runs
  **Then** the precedence is explicit, documented, and covered by a test per level — a global master
  switch that is `false` always wins, because a disabled channel cannot be enabled by a preference.
- **AC-3 (negative) — Given** a recipient preference names a channel that is globally disabled **When**
  resolution runs **Then** the global switch wins and the preference is ignored with a recorded reason —
  a preference must never be able to enable an unapproved channel such as WhatsApp under TODO-05.

**Development Tasks (provisional — do not implement)**

**T-16.5.2.1 — Preference data model** · `P3` · `2 pts` · deps: `T-16.5.1.1`
- **Description:** *(Blocked.)* Model the selection rule TODO-10 specifies — recipient preference store,
  tenant policy column, or extended system settings — with a migration and repository.
- **Acceptance Criteria:** blocked pending TODO-10; the model must not be chosen speculatively, since
  each option carries a different migration cost.
- **Dependencies:** T-16.5.1.1, **TODO-10**

**T-16.5.2.2 — Precedence resolution strategy** · `P3` · `2 pts` · deps: `T-16.5.2.1`
- **Description:** *(Blocked.)* Implement the layered resolution strategy behind `ChannelResolverPort`
  with global master switches as an absolute veto.
- **Acceptance Criteria:** blocked pending TODO-10; provisional criteria are one test per precedence
  level and a proof that a disabled global channel cannot be re-enabled by a lower layer.
- **Dependencies:** T-16.5.2.1, **TODO-10**

**T-16.5.2.3 — Preference administration surface** · `P3` · `1 pt` · deps: `T-16.5.2.1`
- **Description:** *(Blocked.)* Administration UI and API for whichever preference model is chosen,
  with RBAC and audit. Note that visitors are not VMS users, so a per-visitor preference has no
  self-service capture surface without additional scope.
- **Acceptance Criteria:** blocked pending TODO-10; provisional criteria are permission-gated
  administration and an audit entry per preference change.
- **Dependencies:** T-16.5.2.1, EPIC-03 F-03.2, **TODO-10**

---


## EPIC-17 — Notification Scenarios

| | |
|---|---|
| **Provenance** | Mixed — `SRS` for F-17.1 and F-17.2; `TDD-DERIVED` for F-17.3, F-17.4, F-17.5 |
| **Requirement IDs** | `FR-NOT-01 (SRS B1)` — F-17.1 · `FR-NOT-02 (SRS B1)` — F-17.2 · `FR-NOT-03`, `FR-NOT-04`, `FR-NOT-05` (**TDD §8 only — undefined in SRS**) |
| **Priority** | P1 |
| **Stories / Tasks / Points** | 10 · 27 · **40** |
| **ACS-gated** | F-17.2 — exit events arrive from ACS (EPIC-11, EPIC-12, 🔴 TODO-02) |

**Goal.** Register the concrete notification scenarios onto the EPIC-16 platform. Two of the five
features are genuine SRS requirements: the credential confirmation to visitor and host, and the exit
alert to both. The remaining three — host lifecycle notifications, appointment reminders, and system
alerts to administrators — are cited by TDD §8 as `FR-NOT-03/04/05`, and **none of those three IDs
exists in the attached SRS**. Each scenario is a small registration against the F-16.1 routing
registry plus a template; the work is concentrated in getting trigger conditions, suppression rules
and edge cases right, not in transport.

> ⚠️ **Three of five features here are unrequirmented.** F-17.3, F-17.4 and F-17.5 total 22 of this
> epic's 40 points. If TODO-01 confirms the 28-requirement SRS as the delivery baseline, they descope
> and EPIC-17 reduces to 18 points.

---

### F-17.1 — Credential confirmation to visitor & host

Confirmation notification carrying the QR code and welcome message, sent when the credential is
received from ACS.

| | |
|---|---|
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)` |
| **Priority** | P0 · **Points** 8 · **Stories** 2 |
| **Depends on** | F-16.1, F-16.2, F-16.4; EPIC-09 (credential issuance) |

---

#### US-17.1.1 — Notify the visitor when their credential is issued

**As a** visitor **I want** a confirmation email with my QR code and a welcome message once my pass is
issued **so that** I can enter the building without stopping at reception to collect anything.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)` |
| **Dependencies** | US-16.1.1, US-16.2.1, US-16.4.2; EPIC-09 F-09.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** VMS receives a credential from ACS and the `vms.credentials` row moves to
  `state = 'active'` with `acs_credential_id` populated **When** the `credential.issued` domain event is
  published **Then** a `notify_type = 'confirmation'` notification is dispatched to the visitor.
- **AC-2 — Given** the confirmation is rendered **When** the visitor receives it **Then** it contains
  the QR credential and a welcome message per `FR-NOT-01 (SRS B1)`, plus the appointment window in the
  building's local timezone and the host and tenant names.
- **AC-3 — Given** the confirmation is dispatched **When** it is logged **Then**
  `vms.notification_logs` records `visitor_id`, `channel`, `notify_type = 'confirmation'` and
  `body_ref`, and the QR payload from `vms.credentials.qr_payload` appears **nowhere** in the log row,
  application logs or metrics.
- **AC-4 — Given** an RFID credential (`credential_type = 'rfid'`) rather than QR **When** the
  confirmation is rendered **Then** it omits the QR image and instead instructs the visitor to collect
  their card at reception — the template does not render a broken image or an empty block.
- **AC-5 (negative) — Given** the credential request to ACS fails and the credential state becomes
  `failed` **When** the event is processed **Then** **no confirmation is sent** — a visitor must never
  receive a pass for a credential that does not exist.
- **AC-6 (negative) — Given** the visitor has no email address (`vms.visitors.email` is nullable and a
  walk-in may have none) **When** dispatch is attempted **Then** the notification is recorded as
  unresolvable per US-16.1.3 AC-5, reception can still display or print the pass under
  `FR-VMS-07 (SRS B1)`, and **credential issuance is not rolled back**.

**Development Tasks**

**T-17.1.1.1 — Register the visitor confirmation scenario** · `P0` · `2 pts` · deps: `T-16.1.1.3`
- **Description:** Register the `credential.issued` → visitor confirmation scenario in the F-16.1
  routing registry with `notify_type = 'confirmation'`, declaring its variable set (visitor name,
  appointment window, host name, tenant name, building, credential type, QR reference) and its
  recipient rule (the visitor). Assert the trigger fires only for credentials reaching `active`.
- **Acceptance Criteria:** a `credential.issued` event for an active credential produces exactly one
  visitor confirmation dispatch; a `failed` credential produces none; the declared variable set
  satisfies the template validation from T-16.4.1.2; the scenario declares no staleness horizon, so an
  outage-delayed confirmation still sends.
- **Dependencies:** T-16.1.1.3, T-16.4.1.2

**T-17.1.1.2 — Confirmation templates for QR and RFID** · `P0` · `2 pts` · deps: `T-16.4.2.2`
- **Description:** Author the visitor confirmation templates (HTML and plain-text alternative) in both
  QR and RFID variants, with the welcome message required by `FR-NOT-01 (SRS B1)` and appointment
  times rendered through the T-16.4.3.1 timezone helper. Ship as migration seed data so they are
  code-reviewed.
- **Acceptance Criteria:** QR variant embeds a scannable credential, verified by an integration test
  that decodes the rendered image; RFID variant renders collection instructions with no image
  placeholder; both variants pass the CI smoke-render; a plain-text part accompanies each HTML part.
- **Dependencies:** T-16.4.2.2, T-16.4.3.1

**T-17.1.1.3 — Credential-context data access and failure guards** · `P0` · `1 pt` · deps: `T-17.1.1.1`
- **Description:** Source confirmation variables from the credential domain event payload and the
  Credential context's published read data rather than direct reads of `vms.credentials` or
  `vms.visitors`. Guard against dispatch on non-active credential states and handle the unresolvable
  recipient case without failing the issuance workflow.
- **Acceptance Criteria:** fitness test confirms no direct cross-context table read; a `failed`,
  `revoked` or `cancelled` credential never triggers a confirmation; a missing visitor email records
  unresolvable and leaves the credential active; issuance latency is unaffected by notification
  dispatch, since dispatch is asynchronous.
- **Dependencies:** T-17.1.1.1

---

#### US-17.1.2 — Notify the host when their visitor's credential is issued

**As a** host **I want** a confirmation when my visitor's pass has been issued **so that** I know they
are cleared to enter and I can expect them.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` `FR-NOT-01 (SRS B1)` — "The host **and** visitor shall receive a confirmation notification" |
| **Dependencies** | US-17.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a credential is issued for a visitor with an assigned host **When** the
  `credential.issued` event is processed **Then** a confirmation notification is dispatched to the host
  in addition to the visitor, logged with `host_id` populated on `vms.notification_logs`.
- **AC-2 — Given** the host confirmation is rendered **When** the host receives it **Then** it contains
  the visitor name, appointment window and pass validity — but **not the QR payload or credential
  image**, since the credential belongs to the visitor and forwarding it would create a transferable
  pass.
- **AC-3 — Given** a group request under `FR-VMS-01 (SRS B1)` with multiple visitors on one
  `vms.visitor_requests` row **When** credentials are issued for several visitors **Then** the host
  receives notifications consistent with the documented batching rule rather than an unbounded burst of
  individual emails.
- **AC-4 (negative) — Given** the visitor request has no host (`vms.visitor_requests.host_id` is
  nullable) **When** the event is processed **Then** only the visitor notification is dispatched, no
  host notification is created, and no error is raised.
- **AC-5 (negative) — Given** the host record exists but `vms.hosts.email` is null **When** dispatch is
  attempted **Then** the host notification records unresolvable, and **the visitor's own confirmation
  is unaffected** — the two dispatches are independent.

**Development Tasks**

**T-17.1.2.1 — Register the host confirmation scenario** · `P1` · `1 pt` · deps: `T-17.1.1.1`
- **Description:** Register the host-recipient confirmation against the same `credential.issued` event
  as a second scenario, so the two dispatches are independently retryable, independently loggable and
  independently failable. Populate `host_id` on the log row.
- **Acceptance Criteria:** one event yields two independent dispatch requests with distinct dedupe
  keys; failure of one does not affect the other; the host row carries `host_id` and the visitor row
  carries `visitor_id`; a null `host_id` produces no host dispatch.
- **Dependencies:** T-17.1.1.1

**T-17.1.2.2 — Host confirmation template without credential material** · `P1` · `1 pt` · deps: `T-17.1.1.2`
- **Description:** Author the host confirmation template carrying visitor and appointment detail but no
  QR image, no `qr_payload` and no `acs_credential_id`. Add a test asserting the rendered host message
  contains no credential material.
- **Acceptance Criteria:** rendered host message contains no QR image, payload or ACS credential id,
  asserted by test; template passes CI smoke-render; plain-text alternative present; visitor PII in the
  host message is limited to name and company, the minimum the host needs.
- **Dependencies:** T-17.1.1.2

**T-17.1.2.3 — Group request batching rule** · `P1` · `1 pt` · deps: `T-17.1.2.1`
- **Description:** Implement the host notification batching rule for group requests from EPIC-07
  F-07.2 — a short collection window per `(request_id, host_id)` producing one summary notification —
  with the window configurable and the rule documented.
- **Acceptance Criteria:** a group of N visitors issued within the window produces one host
  notification listing all of them; visitors issued outside the window produce a further notification;
  the visitor confirmations are never batched, each visitor always gets their own; batching window is
  configurable.
- **Dependencies:** T-17.1.2.1, EPIC-07 F-07.2

---

### F-17.2 — Exit alerts to host & visitor ⚠️ ACS-gated

Automated exit alerts triggered by exit events received from the ACS API.

| | |
|---|---|
| **Provenance** | `SRS` `FR-NOT-02 (SRS B1)` |
| **Priority** | P1 · **Points** 10 · **Stories** 2 |
| **Depends on** | F-16.1, F-16.2; **EPIC-11** (ACS client), **EPIC-12 F-12.3** (access & exit event processing) |

> ⚠️ **ACS-gated.** `FR-NOT-02 (SRS B1)` reads: *"VMS shall receive exit events from the ACS API and
> trigger automated exit alerts."* The receiving half is Phase 3 work and depends on 🔴 **TODO-02**.
> Per **ADR-0002** these stories are fully implementable and testable against the ACS simulator, and
> they close at **"done against simulator"** — not "done" — until the real UAL contract lands. The
> alerting half, once an exit event exists in `vms.access_events`, carries no ACS dependency at all.

---

#### US-17.2.1 — Alert the host when their visitor exits

**As a** host **I want** an alert when my visitor leaves the building **so that** I know the visit has
ended and I am accountable for having received them.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-NOT-02 (SRS B1)` · **ACS-gated** (🔴 TODO-02, ADR-0002) |
| **Dependencies** | US-16.1.1, US-16.2.1; EPIC-11 F-11.8, EPIC-12 F-12.3 |
| **Blocked by** | — (implementable against the simulator; closes at "done against simulator" until TODO-02) |

**Acceptance Criteria**
- **AC-1 — Given** an exit event is persisted to `vms.access_events` with
  `direction = 'exit'` **When** the corresponding domain event is published **Then** a
  `notify_type = 'exit'` notification is dispatched to the visitor's host.
- **AC-2 — Given** the exit alert is rendered **When** the host receives it **Then** it names the
  visitor and shows the exit time from `vms.access_events.event_time` in the building's local timezone —
  **not** `received_at`, which may differ materially if ACS delivery was delayed.
- **AC-3 — Given** the exit event is processed **When** the visitor's lifecycle updates **Then** the
  alert dispatch is decoupled from the `vms.visitors.status` transition to `checked_out`, so a
  notification failure never blocks the visit lifecycle from completing.
- **AC-4 (negative) — Given** ACS redelivers the same exit event **When** it arrives again **Then** the
  `acs_event_id` unique constraint on `vms.access_events` plus the US-16.1.3 dedupe key together
  guarantee exactly one alert — the host is not told twice that one visitor left once.
- **AC-5 (negative) — Given** an exit event arrives with an `acs_credential_id` that matches no known
  credential, so `visitor_id` and `credential_id` are null (both are nullable in the schema) **When**
  the event is processed **Then** no exit alert is dispatched, the orphan event is recorded and
  counted, and an operator alert is raised for reconciliation — VMS does not guess a recipient.
- **AC-6 (negative) — Given** an exit event arrives hours late, after the visit already reached a
  terminal status **When** dispatch is evaluated **Then** the staleness policy from T-16.2.3.2
  suppresses the alert with a recorded reason rather than telling a host at 23:00 that their 10:00
  visitor has left.

**Development Tasks**

**T-17.2.1.1 — Register the exit alert scenario on the access event stream** · `P1` · `2 pts` · deps: `T-16.1.1.3`
- **Description:** Register the exit-direction access event → host alert scenario with
  `notify_type = 'exit'`, consuming the domain event published by EPIC-12 F-12.3 rather than reading
  `vms.access_events` directly. Filter strictly on `direction = 'exit'` so entry events never trigger
  it. Declare a staleness horizon so the T-16.2.3.2 suppression applies.
- **Acceptance Criteria:** an exit event produces one host alert; an entry event produces none; a
  fitness test confirms no direct read of `vms.access_events` from the notification service; the
  staleness horizon is configured and enforced; the scenario is exercised end-to-end against the ACS
  simulator.
- **Dependencies:** T-16.1.1.3, EPIC-12 F-12.3

**T-17.2.1.2 — Orphan and duplicate exit event handling** · `P1` · `2 pts` · deps: `T-17.2.1.1`
- **Description:** Handle exit events whose `visitor_id` is null (unmatched credential) by recording
  and alerting for reconciliation without dispatching, and confirm idempotency across the
  `acs_event_id` unique constraint and the notification dedupe key. Add contract tests against the ACS
  simulator covering redelivery, out-of-order arrival and unmatched credentials.
- **Acceptance Criteria:** an orphan exit event dispatches nothing and increments a reconciliation
  metric; a redelivered event yields exactly one alert; an exit event arriving before its entry event
  is handled without error; simulator contract tests cover all three cases; the ACS-gated status is
  recorded in the traceability matrix.
- **Dependencies:** T-17.2.1.1, EPIC-11 F-11.2

**T-17.2.1.3 — Exit alert template with accurate event time** · `P1` · `1 pt` · deps: `T-16.4.3.1`
- **Description:** Author the host exit alert template rendering `event_time` (not `received_at`) via
  the timezone helper, naming the visitor and the total visit duration where an entry event exists.
- **Acceptance Criteria:** rendered alert shows `event_time` in building-local time with the zone named;
  a delayed event still reports the true exit time; visit duration is omitted rather than shown as
  negative or absurd when no entry event exists; template passes CI smoke-render.
- **Dependencies:** T-16.4.3.1

---

#### US-17.2.2 — Alert the visitor on exit

**As a** visitor **I want** a courtesy notification when I exit the building **so that** I have a
record of my visit and confirmation that my pass is finished.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-NOT-02 (SRS B1)` — "exit alerts to the host **and the visitor**" · **ACS-gated** |
| **Dependencies** | US-17.2.1 |
| **Blocked by** | — (closes at "done against simulator" until 🔴 TODO-02) |

**Acceptance Criteria**
- **AC-1 — Given** an exit event is processed **When** the alert scenarios run **Then** the visitor
  receives an exit notification independently of the host's, logged with `visitor_id` on
  `vms.notification_logs`.
- **AC-2 — Given** the visitor's credential had `restriction = 'one_time'` **When** the exit alert is
  rendered **Then** it states the pass is now used and cannot be reused, so the visitor does not return
  to a barrier expecting entry.
- **AC-3 — Given** the visitor holds an RFID card recorded in `vms.card_issuances` with
  `returned_at IS NULL` at the time of exit **When** the alert is rendered **Then** it includes a card
  return reminder, connecting `FR-NOT-02 (SRS B1)` to the `FR-CRD-02 (SRS B1)` return path.
- **AC-4 (negative) — Given** the visitor exits and re-enters within their validity window on a
  `time_bound` credential **When** each exit event is processed **Then** each exit produces an alert,
  and the alerts must not contradict each other by declaring the pass finished on a still-valid
  time-bound credential.
- **AC-5 (negative) — Given** the visitor has no email address **When** dispatch is attempted **Then**
  the visitor alert records unresolvable and **the host alert still sends** — the visitor's missing
  contact detail must not suppress the host's notification, which is the accountability-relevant one.

**Development Tasks**

**T-17.2.2.1 — Register the visitor exit scenario** · `P1` · `2 pts` · deps: `T-17.2.1.1`
- **Description:** Register the visitor-recipient exit scenario as an independent dispatch on the same
  event with its own dedupe key, so host and visitor alerts succeed or fail separately.
- **Acceptance Criteria:** one exit event produces two independent dispatches; failure or
  unresolvability of one does not affect the other; both rows carry the correct
  `visitor_id`/`host_id`; repeated exit-and-re-entry produces one alert pair per exit.
- **Dependencies:** T-17.2.1.1

**T-17.2.2.2 — Restriction-aware and card-aware alert content** · `P1` · `2 pts` · deps: `T-17.2.2.1`
- **Description:** Vary alert content by `vms.credentials.restriction` — `one_time` states the pass is
  spent, `time_bound` states remaining validity — and query the Entry context for an open
  `vms.card_issuances` row (`returned_at IS NULL`) to conditionally include the card return reminder.
- **Acceptance Criteria:** a one-time credential alert states the pass is spent; a time-bound credential
  alert within its window does not; an open card issuance adds the return reminder; a returned card does
  not; card state is read through the Entry context's published data, not by direct table access.
- **Dependencies:** T-17.2.2.1, EPIC-15 F-15.2

**T-17.2.2.3 — Re-entry consistency and simulator coverage** · `P1` · `1 pt` · deps: `T-17.2.2.2`
- **Description:** Add integration coverage over the ACS simulator for exit-re-entry-exit sequences on
  a time-bound credential, asserting alert content stays consistent and no alert falsely declares a
  still-valid credential finished.
- **Acceptance Criteria:** exit-re-entry-exit produces two coherent visitor alerts; neither declares a
  still-valid time-bound pass unusable; simulator scenario is part of the standing integration suite;
  the result is recorded as "done against simulator" pending TODO-02.
- **Dependencies:** T-17.2.2.2, EPIC-11 F-11.2

---

### F-17.3 — Host notification — approved / in / out

Host lifecycle notifications at request approval and visitor arrival.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` `FR-NOT-03` — **cited by TDD §8; not defined in the attached SRS** |
| **Priority** | P2 · **Points** 6 · **Stories** 2 |
| **Depends on** | F-16.1, F-16.2; EPIC-07, EPIC-12 |

> ⚠️ **No SRS requirement backs this feature.** The "out" half is already delivered by F-17.2 under
> `FR-NOT-02 (SRS B1)`; only the "approved" and "in" notifications are new, and neither is required by
> any SRS statement. Backlog only pending TODO-01.

---

#### US-17.3.1 — Notify the host when a visitor request is approved or rejected

**As a** host **I want** to be told when a visitor request naming me is approved or rejected **so
that** I know whether to expect my visitor without chasing FM Admin.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` `FR-NOT-03` (TDD §8) — **undefined in SRS** |
| **Dependencies** | US-16.1.1, US-16.2.1; EPIC-07 F-07.4 |
| **Blocked by** | — (TODO-01 disposition required before sprint entry) |

**Acceptance Criteria**
- **AC-1 — Given** an FM Admin approves a request under `FR-VMS-02 (SRS B1)` and
  `vms.visitor_requests.status` becomes `approved` **When** the event is published **Then** a
  `notify_type = 'host'` notification is dispatched to the assigned host.
- **AC-2 — Given** a request is rejected **When** the event is processed **Then** the host is notified
  with the recorded rejection reason from `FR-VMS-02 (SRS B1)`, and the requesting tenant user is
  notified per the F-07.6 status-visibility path without duplicating that path here.
- **AC-3 (negative) — Given** a request is approved and then cancelled shortly after **When** both
  events are processed **Then** both notifications are sent in the correct order, and the cancellation
  notification is never overtaken by a delayed approval notification implying the visit is still on.
- **AC-4 (negative) — Given** a rejection reason contains internal commentary **When** the host
  notification is rendered **Then** only the reason text designated as host-visible is included — an
  internal note must not leak to a tenant-side recipient.

**Development Tasks**

**T-17.3.1.1 — Register approval and rejection host scenarios** · `P2` · `1 pt` · deps: `T-16.1.1.3`
- **Description:** Register `request.approved` and `request.rejected` host scenarios with
  `notify_type = 'host'`, sourcing state from the EPIC-07 F-07.5 domain events.
- **Acceptance Criteria:** approval and rejection each dispatch exactly one host notification; a
  request with no host dispatches none; scenario variables validate against their templates; no direct
  read of `vms.visitor_requests`.
- **Dependencies:** T-16.1.1.3, EPIC-07 F-07.5

**T-17.3.1.2 — Ordering guarantee across lifecycle transitions** · `P2` · `1 pt` · deps: `T-17.3.1.1`
- **Description:** Guarantee per-request notification ordering by partitioning the Kafka topic on
  request id and sequencing dispatch per `(request_id, host_id)`, so a delayed approval cannot arrive
  after a cancellation.
- **Acceptance Criteria:** out-of-order delivery test produces correctly ordered notifications; a later
  terminal state suppresses a pending earlier-state notification; ordering is asserted by integration
  test rather than assumed from Kafka defaults.
- **Dependencies:** T-17.3.1.1

**T-17.3.1.3 — Host-visible reason field separation** · `P2` · `1 pt` · deps: `T-17.3.1.1`
- **Description:** Ensure only the host-visible portion of an approval or rejection reason reaches the
  template, keeping any internal annotation out of the rendered message and out of `subject`.
- **Acceptance Criteria:** internal commentary never appears in a rendered host message, asserted by
  test; the host-visible field is explicit in the event contract rather than inferred; a null reason
  renders a neutral default rather than an empty section.
- **Dependencies:** T-17.3.1.1

---

#### US-17.3.2 — Notify the host when their visitor checks in

**As a** host **I want** to know the moment my visitor arrives **so that** I can meet them instead of
leaving them waiting in the lobby.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` `FR-NOT-03` (TDD §8) — **undefined in SRS** |
| **Dependencies** | US-17.3.1; EPIC-12 F-12.2 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor checks in and `vms.visitors.status` becomes `checked_in` with
  `checked_in_at` stamped **When** the event is published **Then** a `notify_type = 'host'` arrival
  notification is dispatched to the host.
- **AC-2 — Given** the arrival notification is rendered **When** the host receives it **Then** it names
  the visitor, the reception point where they checked in, and the check-in time in building-local time.
- **AC-3 (negative) — Given** a visitor's status oscillates due to a corrected check-in or an operator
  error **When** repeated `checked_in` transitions occur for one visitor on one appointment **Then** at
  most one arrival notification is sent per visitor per appointment, enforced by the dedupe key.
- **AC-4 (negative) — Given** a group arrival where several visitors on one request check in within
  moments **When** notifications are dispatched **Then** the T-17.1.2.3 batching rule applies, and the
  host receives a single arrival summary rather than one email per visitor.

**Development Tasks**

**T-17.3.2.1 — Register the arrival notification scenario** · `P2` · `2 pts` · deps: `T-17.3.1.1`
- **Description:** Register the `visitor.checked_in` host scenario, sourcing reception point and
  check-in time from the EPIC-12 F-12.2 event, with a dedupe key scoped to
  `(visitor_id, appointment)` so a repeated transition cannot re-notify.
- **Acceptance Criteria:** check-in dispatches one host arrival notification; a repeated `checked_in`
  transition dispatches none; reception point name resolves from master data via cache; a visitor with
  no host dispatches nothing.
- **Dependencies:** T-17.3.1.1, EPIC-12 F-12.2

**T-17.3.2.2 — Arrival template and group batching reuse** · `P2` · `1 pt` · deps: `T-17.3.2.1`
- **Description:** Author the arrival template and reuse the T-17.1.2.3 batching window for group
  arrivals, keeping visitor PII in the host message to name and company only.
- **Acceptance Criteria:** a group arrival within the window produces one summary listing all arrived
  visitors; a single arrival produces a single message; template passes CI smoke-render; no ID document
  reference or contact detail of the visitor appears in the host message.
- **Dependencies:** T-17.3.2.1, T-17.1.2.3

---

### F-17.4 — Appointment reminders

Scheduled reminders ahead of an appointment.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` `FR-NOT-04` — **cited by TDD §8; not defined in the attached SRS** |
| **Priority** | P2 · **Points** 8 · **Stories** 2 |
| **Depends on** | F-16.1, F-16.2; EPIC-08 F-08.3 |

> ⚠️ **No SRS requirement backs this feature.** No SRS statement anywhere asks for a reminder. Note
> also that the reminder lead time, quiet hours and whether hosts are reminded at all are undefined —
> these are open design questions inside an already-unrequirmented feature.

---

#### US-17.4.1 — Send appointment reminders on schedule

**As a** visitor **I want** a reminder before my appointment **so that** I arrive on time with my pass
to hand.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` `FR-NOT-04` (TDD §8) — **undefined in SRS**; appointment window from `FR-VMS-11 (SRS B1)` |
| **Dependencies** | US-16.1.2, US-16.2.1; EPIC-08 F-08.3 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor has an `appointment_from` in `vms.visitors` and an approved request
  **When** the scheduler runs and the configured lead time is reached **Then** a
  `notify_type = 'reminder'` notification is dispatched to the visitor.
- **AC-2 — Given** the scheduler runs across multiple service instances **When** reminders are
  selected **Then** exactly one instance dispatches each reminder, enforced by a scheduler lock, and no
  visitor receives duplicates.
- **AC-3 — Given** the reminder is rendered **When** the visitor receives it **Then** it repeats the
  appointment window in building-local time and the entry instructions, and re-includes the QR
  credential only if one is already active — a reminder must not imply a pass exists when it does not.
- **AC-4 (negative) — Given** the scheduler was down over the lead-time window **When** it restarts
  **Then** it does not fire a burst of reminders for appointments that have already started; reminders
  past their usefulness are suppressed with a recorded reason per the T-16.2.3.2 staleness policy.
- **AC-5 (negative) — Given** an appointment falls near a daylight-saving transition **When** the lead
  time is computed **Then** it is computed against the true instant, not a naive local-clock
  subtraction, and the reminder fires at the correct real-world time.

**Development Tasks**

**T-17.4.1.1 — Reminder scheduler with distributed lock** · `P2` · `2 pts` · deps: `T-16.1.2.1`
- **Description:** Implement the scheduled job in `interfaces` selecting due reminders by
  `appointment_from` minus the configured lead time, guarded by a Redis-based distributed lock
  (ShedLock or equivalent per TDD §3) so only one instance runs a given execution.
- **Acceptance Criteria:** exactly one instance executes each scheduled run under a multi-instance
  test; the selection query is indexed via `idx_visitors_appt` and verified by an execution-plan
  assertion; lead time is configurable in `vms.system_settings`; a missed window does not backfill
  stale reminders.
- **Dependencies:** T-16.1.2.1, EPIC-01 F-01.3

**T-17.4.1.2 — Timezone-correct lead time computation** · `P2` · `2 pts` · deps: `T-17.4.1.1`
- **Description:** Compute the reminder instant by instant arithmetic on the `timestamptz` value rather
  than local-clock arithmetic, with tests over spring-forward and autumn-back transitions in a
  DST-observing zone.
- **Acceptance Criteria:** reminders fire at the correct instant on both DST transitions; no reminder is
  skipped or doubled at the transition; the computation is unit-tested independently of the scheduler;
  the building timezone comes from a single configured source.
- **Dependencies:** T-17.4.1.1

**T-17.4.1.3 — Reminder template with conditional credential** · `P2` · `1 pt` · deps: `T-16.4.2.2`
- **Description:** Author the reminder template including the QR credential only when an active
  credential exists, and entry instructions otherwise, so a pre-approved visitor without an issued pass
  gets accurate guidance.
- **Acceptance Criteria:** an active credential renders the QR; no active credential renders
  instructions with no image placeholder; template passes CI smoke-render; the QR payload appears in no
  log or `body_ref`.
- **Dependencies:** T-16.4.2.2

---

#### US-17.4.2 — Suppress reminders that are no longer valid

**As an** FM Admin **I want** reminders suppressed when a visit is cancelled or already underway **so
that** we do not remind people to attend visits that are not happening.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` `FR-NOT-04` (TDD §8) — **undefined in SRS** |
| **Dependencies** | US-17.4.1 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** a visitor's status is `cancelled`, `checked_in`, `inside`, `checked_out` or
  `expired` **When** the reminder becomes due **Then** it is suppressed with a recorded reason and no
  message is sent.
- **AC-2 — Given** a reminder is already queued **When** the visit is cancelled before dispatch
  **Then** the queued reminder is suppressed at send time by a final state re-check — the state is
  re-evaluated immediately before dispatch, not only at scheduling time.
- **AC-3 (negative) — Given** an appointment is rescheduled to a later time after its reminder has been
  scheduled **When** the reminder becomes due **Then** the stale reminder is suppressed and a reminder
  for the new time is scheduled — the visitor is not reminded of the old slot.
- **AC-4 (negative) — Given** the underlying `vms.visitor_requests` row is `rejected` while the
  visitor row still reads `pending` **When** the reminder becomes due **Then** the request status takes
  precedence and the reminder is suppressed — the two status fields can disagree, and the request is
  authoritative.

**Development Tasks**

**T-17.4.2.1 — Pre-dispatch state re-check** · `P2` · `2 pts` · deps: `T-17.4.1.1`
- **Description:** Add a final suppression check immediately before dispatch, evaluating both
  `vms.visitors.status` and the parent `vms.visitor_requests.status` through the Visitor context's
  published read data, with the request status authoritative on disagreement.
- **Acceptance Criteria:** every terminal or in-progress visitor status suppresses the reminder; a
  rejected or cancelled parent request suppresses regardless of visitor status; the suppression reason
  is persisted and counted; no direct cross-context table read.
- **Dependencies:** T-17.4.1.1

**T-17.4.2.2 — Reschedule handling** · `P2` · `1 pt` · deps: `T-17.4.2.1`
- **Description:** Handle appointment reschedules by invalidating the scheduled reminder for the old
  instant and scheduling for the new one, keyed so a reschedule cannot leave two live reminders.
- **Acceptance Criteria:** a reschedule suppresses the old reminder and schedules exactly one new one;
  repeated reschedules never accumulate reminders; a reschedule into the past suppresses without
  scheduling; behaviour is covered by integration test.
- **Dependencies:** T-17.4.2.1

---

### F-17.5 — System alerts to administrators

Operational alerts to administrators when the system itself needs attention.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` `FR-NOT-05` — **cited by TDD §8; not defined in the attached SRS** |
| **Priority** | P2 · **Points** 8 · **Stories** 2 |
| **Depends on** | F-16.1, F-16.2; EPIC-11 F-11.4, EPIC-15 F-15.4 |

> ⚠️ **No SRS requirement backs this feature** — though note that `FR-API-01 (SRS B1)` does require
> notifying the receptionist of an ACS failure, and `FR-CRD-03 (SRS B1)` does require flagging card
> discrepancies. Those two notification obligations are owned by EPIC-11 and EPIC-15 respectively;
> what is unrequirmented here is the general administrator alerting channel that carries them.

---

#### US-17.5.1 — Alert administrators on integration and delivery failures

**As a** System Administrator **I want** an alert when ACS calls dead-letter or notifications stop
delivering **so that** I find out from the system rather than from a receptionist three hours later.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` `FR-NOT-05` (TDD §8) — **undefined in SRS**; carries the alerting obligation of `FR-API-01 (SRS B1)` |
| **Dependencies** | US-16.1.2, US-16.2.1; EPIC-11 F-11.4 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** an ACS request reaches `vms.acs_requests.status = 'dead_letter'` **When** the
  dead-letter event is published **Then** a `notify_type = 'system_alert'` notification is dispatched
  to the configured administrator recipients.
- **AC-2 — Given** repeated failures of the same kind **When** alerts are generated **Then** they are
  deduplicated and rate-limited into a digest over a configured window, so a sustained ACS outage
  produces a manageable alert stream rather than hundreds of emails.
- **AC-3 — Given** a system alert is rendered **When** an administrator receives it **Then** it carries
  the failure classification, affected operation, counts and correlation ids — and **no visitor PII**,
  since an operational alert has no need for a visitor's name, email or QR payload.
- **AC-4 (negative) — Given** the email channel itself is failing **When** a notification-delivery alert
  is generated **Then** the alert does not depend solely on the failing channel to be seen: it is
  emitted as a metric and log event that external monitoring can alert on independently, so the failure
  mode is not self-silencing.
- **AC-5 (negative) — Given** the alert recipient list is unconfigured or empty **When** an alert fires
  **Then** it is recorded and surfaced on the operator view with a startup warning about the missing
  configuration, rather than being silently discarded.

**Development Tasks**

**T-17.5.1.1 — System alert scenario and recipient configuration** · `P2` · `2 pts` · deps: `T-16.1.1.3`
- **Description:** Register the `system_alert` scenario consuming operational events — ACS dead-letter
  from EPIC-11 F-11.4, notification dead-letter from T-16.1.2.3, email circuit-breaker open from
  T-16.2.3.1 — with the administrator recipient list configured in `vms.system_settings` and validated
  at startup.
- **Acceptance Criteria:** each source event dispatches a system alert; an empty recipient list logs a
  startup warning and records undelivered alerts rather than discarding them; recipients are
  configurable without redeploy; changing the list is audited.
- **Dependencies:** T-16.1.1.3, EPIC-11 F-11.4

**T-17.5.1.2 — Alert deduplication, rate limiting and digest** · `P2` · `2 pts` · deps: `T-17.5.1.1`
- **Description:** Group alerts by `(alert_type, affected_operation)` over a configurable window,
  emitting one digest per window with an occurrence count instead of one message per failure. Use Redis
  for the window state per TDD §3.
- **Acceptance Criteria:** 500 dead-letters in one window produce one digest with a count of 500; a new
  alert type within the same window produces its own digest; window size is configurable; the digest
  names first and last occurrence times.
- **Dependencies:** T-17.5.1.1

**T-17.5.1.3 — PII exclusion and independent metric path** · `P2` · `1 pt` · deps: `T-17.5.1.1`
- **Description:** Assert system alert templates carry no visitor PII, and emit every alert
  additionally as a metric and structured log event so external monitoring can observe failures even
  when the email channel is the thing that is broken.
- **Acceptance Criteria:** an automated test asserts no visitor name, email, phone or QR payload can
  appear in a `system_alert` message; every alert increments a labelled metric with no PII in label
  values; a notification-channel failure is observable via metrics with the channel fully down.
- **Dependencies:** T-17.5.1.1, EPIC-01 F-01.7

---

#### US-17.5.2 — Alert administrators on card reconciliation discrepancies

**As a** Master Admin **I want** an alert when the daily card reconciliation finds a missing card **so
that** I can act on the same day rather than discovering it in a monthly report.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` `FR-NOT-05` (TDD §8) as the delivery channel; the discrepancy flag itself is `SRS` `FR-CRD-03 (SRS B1)` · ⚠️ recipients and channel undefined — 🟠 **TODO-11** |
| **Dependencies** | US-17.5.1; EPIC-15 F-15.3, F-15.4 |
| **Blocked by** | — (TODO-01 for the alert channel; TODO-11 must resolve before close) |

**Acceptance Criteria**
- **AC-1 — Given** the EPIC-15 daily reconciliation completes and flags discrepancies — issued cards in
  `vms.card_issuances` with `returned_at IS NULL` past the day cut-off **When** the discrepancy event
  is published **Then** a `system_alert` notification is dispatched summarising the count and the
  affected card identifiers.
- **AC-2 — Given** the alert is rendered **When** it is received **Then** it lists `acs_card_id`,
  issue time and issuing user for each unreturned card — operational identifiers, with visitor
  identification limited to the visitor id, not name or contact details.
- **AC-3 (negative) — Given** reconciliation finds no discrepancies **When** the run completes **Then**
  no alert is sent, but the successful run is recorded so a silent scheduler failure is distinguishable
  from a clean day — silence must not be ambiguous.
- **AC-4 (negative, blocked) — Given** 🟠 **TODO-11** leaves the day cut-off, timezone and recipient
  undefined **When** this story is implemented **Then** it consumes whatever discrepancy event EPIC-15
  publishes and **defines none of those parameters itself**; the alert recipient defaults to the
  US-17.5.1 administrator list and the open question is recorded against the story.

**Development Tasks**

**T-17.5.2.1 — Reconciliation discrepancy alert scenario** · `P2` · `2 pts` · deps: `T-17.5.1.1`
- **Description:** Register the reconciliation discrepancy scenario consuming the EPIC-15 F-15.4 event,
  with a template listing unreturned card identifiers and issue metadata, and a clean-run record when
  the count is zero.
- **Acceptance Criteria:** a discrepancy event dispatches one summary alert; a zero-discrepancy run
  dispatches none but records completion; the alert carries no visitor name or contact detail; the
  clean-run record is queryable so a missing run is detectable.
- **Dependencies:** T-17.5.1.1, EPIC-15 F-15.4

**T-17.5.2.2 — TODO-11 parameter pass-through** · `P2` · `1 pt` · deps: `T-17.5.2.1`
- **Description:** Ensure the notification side defines no reconciliation semantics — cut-off time,
  timezone, what constitutes a day — and consumes them from the event payload only. Record the TODO-11
  dependency in the story and the traceability matrix.
- **Acceptance Criteria:** no cut-off, timezone or day-boundary logic exists in the notification
  service, asserted by review checklist and code search; the alert renders whatever period the event
  declares; TODO-11 is linked from the traceability matrix row.
- **Dependencies:** T-17.5.2.1

---


## EPIC-18 — Reporting Suite

| | |
|---|---|
| **Provenance** | Mixed — `SRS` for F-18.1, F-18.2, F-18.3; `TDD-DERIVED` for F-18.4 |
| **Requirement IDs** | `FR-REP-01 (SRS B1)` — F-18.1, F-18.3 · `FR-REP-02 (SRS B1)` — F-18.2 · `FR-REP-06` (**TDD §4.5 only — undefined in SRS**) |
| **Priority** | P1 |
| **Stories / Tasks / Points** | 9 · 27 · **44** |
| **ACS-gated** | F-18.1 — `FR-REP-01 (SRS B1)` explicitly requires ACS access/exit events (🔴 TODO-02) |

**Goal.** Deliver the two SRS-required reports and the scheduling that generates them automatically.
`FR-REP-01 (SRS B1)` requires daily, weekly and monthly visitor activity reports that **combine VMS
pre-registration and approval data with access and exit events retrieved from the ACS API** — so the
report is only as complete as the ACS integration. `FR-REP-02 (SRS B1)` requires an on-demand
end-of-day report of credentials and cards issued and returned, reconciled per SRS §3.6. Report
generation is server-side per TDD §3, and the Reporting context is **read-model only** per
`05-workflow-and-branching.md` §8 — it projects from domain events and writes to no other context's
tables. Every report surfaces visitor PII, so authorization and role scoping are acceptance criteria,
not afterthoughts.

> ⚠️ **Transitive ambiguity on FR-REP-02.** `FR-REP-02 (SRS B1)` reports "end-of-day" figures
> reconciled via SRS §3.6 — that is `FR-CRD-03 (SRS B1)`, which 🟠 **TODO-11** flags as having no
> defined day cut-off or timezone. **The "day" in "end-of-day report" is therefore undefined too.**
> F-18.2 consumes EPIC-15's definition and defines nothing itself.

---

### F-18.1 — Daily / weekly / monthly visitor activity reports

Visitor activity reports over daily, weekly and monthly periods, combining VMS workflow data with ACS
access and exit events.

| | |
|---|---|
| **Provenance** | `SRS` `FR-REP-01 (SRS B1)` |
| **Priority** | P1 · **Points** 18 · **Stories** 3 |
| **Depends on** | EPIC-07, EPIC-08, EPIC-09; **EPIC-11, EPIC-12** (ACS events) |

> ⚠️ **ACS-gated.** The requirement text names ACS access and exit events as report inputs. Per
> **ADR-0002** these stories are built and tested against the simulator and close at **"done against
> simulator"** until 🔴 **TODO-02** lands.

---

#### US-18.1.1 — Build the reporting read model

**As a** System Administrator **I want** reporting to run off a dedicated read model **so that**
generating a monthly report does not slow down reception at the desk.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 8 |
| **Provenance** | `ENABLER` for `FR-REP-01 (SRS B1)`; read-model pattern from TDD §4.5 and `05-workflow-and-branching.md` §8 |
| **Dependencies** | EPIC-01 F-01.3, EPIC-07 F-07.5, EPIC-12 F-12.3 |
| **Blocked by** | — (ACS-gated for the access-event portion) |

**Acceptance Criteria**
- **AC-1 — Given** domain events are published by the Visitor, Credential and Entry contexts **When**
  the reporting projector consumes them **Then** a denormalised visit-activity read model is
  maintained containing visit, approval, credential, entry and exit facts keyed by visitor and period.
- **AC-2 — Given** the Reporting context is read-model only **When** an architecture fitness test runs
  **Then** it confirms the reporting service writes to **no other context's tables** and reads no other
  context's tables directly — it consumes events only.
- **AC-3 — Given** the read model is queried for a reporting period **When** the query executes **Then**
  it completes within the documented performance budget over a dataset at `NFR-SCL-01 (SRS B1)` scale
  (300–500 visitors/day over a 12-month span), verified by a performance test.
- **AC-4 — Given** the projector falls behind or is restarted **When** it resumes **Then** projection
  is idempotent and replayable from the event log, producing an identical read model — a replay must
  not double-count a visit.
- **AC-5 (negative) — Given** an ACS access event arrives whose `visitor_id` is null because the
  credential did not match (both `visitor_id` and `credential_id` are nullable on
  `vms.access_events`) **When** it is projected **Then** it is counted in an "unattributed events"
  bucket rather than being dropped or arbitrarily assigned — the report must not quietly under-report
  building traffic.
- **AC-6 (negative) — Given** the report needs to group activity by floor or tenant **When** the
  projection is built **Then** the floor and tenant association is captured **at projection time** from
  the visit's request, because `vms.access_events` has **no `floor_id` or `building_id` column** and a
  later join through `visitors → visitor_requests → tenants → floors` would reflect present-day master
  data rather than the state at the time of the visit.

**Development Tasks**

**T-18.1.1.1 — Reporting service module and read model schema** · `P0` · `3 pts` · deps: `—`
- **Description:** Create the `reporting` Spring Boot module per the Clean Architecture layering, and a
  reporting-owned read-model schema holding denormalised visit-activity rows with period keys, tenant
  and floor attribution, credential facts and entry/exit timestamps. Deliver as a migration per EPIC-01
  F-01.5.
- **Acceptance Criteria:** module builds and registers with the fitness suite; fitness test asserts the
  reporting service holds no write path to another context's tables; read-model migration applies and
  rolls back cleanly; indexes support period-range and tenant-scoped queries.
- **Dependencies:** EPIC-01 F-01.2, EPIC-01 F-01.5

**T-18.1.1.2 — Event projector with idempotent replay** · `P0` · `3 pts` · deps: `T-18.1.1.1`
- **Description:** Implement the Kafka projector consuming visitor, credential and entry domain events
  into the read model, keyed idempotently on event id so replay converges rather than double-counts.
  Track projection lag as a metric and expose it on the health endpoint.
- **Acceptance Criteria:** replaying the full event log twice yields an identical read model, asserted
  by checksum comparison; projection lag is exposed as a metric; the projector resumes from its last
  committed position after restart; an out-of-order entry/exit pair projects correctly.
- **Dependencies:** T-18.1.1.1

**T-18.1.1.3 — Point-in-time tenant and floor attribution** · `P0` · `1 pt` · deps: `T-18.1.1.2`
- **Description:** Capture tenant, floor and building attribution onto the read-model row at projection
  time from the visit's originating request, rather than resolving it at query time. Raise the missing
  `floor_id`/`building_id` on `vms.access_events` as a schema observation.
- **Acceptance Criteria:** a report over a historic period reflects the tenant and floor as at the
  visit, not as at query time, verified by a test that moves a tenant between floors after the visit;
  attribution is non-null for every attributable visit; schema observation filed.
- **Dependencies:** T-18.1.1.2

**T-18.1.1.4 — Unattributed access event handling** · `P0` · `1 pt` · deps: `T-18.1.1.2`
- **Description:** Project access events with a null `visitor_id` into an unattributed bucket carrying
  gate reference and event time, so building traffic totals stay honest and reconciliation has
  something to work from.
- **Acceptance Criteria:** unattributed events appear in report totals as a distinct line rather than
  being dropped; the count is exposed as a metric; an event later matched to a visitor is re-attributed
  idempotently on replay; unattributed events never inflate per-visitor counts.
- **Dependencies:** T-18.1.1.2

---

#### US-18.1.2 — Generate the daily visitor activity report

**As an** FM Admin **I want** a daily visitor activity report **so that** I have a defensible record of
who was in the building and when.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-REP-01 (SRS B1)` · **ACS-gated** (🔴 TODO-02, ADR-0002) |
| **Dependencies** | US-18.1.1 |
| **Blocked by** | — (closes at "done against simulator" until TODO-02) |

**Acceptance Criteria**
- **AC-1 — Given** a requested date **When** the daily report is generated **Then** it combines VMS
  pre-registration and approval data with ACS access and exit events per `FR-REP-01 (SRS B1)`, showing
  per visitor: name, company, tenant, host, approval outcome, credential type, entry time and exit time.
- **AC-2 — Given** the report is generated **When** totals are computed **Then** it summarises expected
  visitors, actual arrivals, no-shows (`vms.visitors.status = 'no_show'`), still-inside at period end,
  and unattributed access events.
- **AC-3 — Given** a report period **When** the period boundary is computed **Then** it uses the
  building's configured timezone, and a visit spanning midnight is attributed by a single documented
  rule (entry time) applied consistently across daily, weekly and monthly reports.
- **AC-4 (negative) — Given** a date on which there was no visitor activity — a weekend or a holiday
  from `vms.holiday_calendar` **When** the report is generated **Then** it returns a valid empty report
  with zero totals and a "no activity in period" indication, **not** an error and not a missing
  document.
- **AC-5 (negative) — Given** a visitor entered but no exit event was ever received **When** the report
  is generated **Then** the exit column reads "no exit recorded" rather than blank or a fabricated
  time, and the visitor is counted in the still-inside total.
- **AC-6 (negative) — Given** a date in the future or before the system's earliest data **When** the
  report is requested **Then** the request is rejected with a clear validation message rather than
  returning a misleading empty report.

**Development Tasks**

**T-18.1.2.1 — Daily report query and assembly** · `P1` · `2 pts` · deps: `T-18.1.1.2`
- **Description:** Implement the daily report use case in `application`, querying the read model over a
  timezone-correct day range and assembling the per-visitor detail rows and summary totals into a
  report model independent of any output format.
- **Acceptance Criteria:** report model is format-independent, with rendering and export layered above
  it; totals reconcile against detail rows in every test case; query uses the period index and is
  verified by an execution-plan assertion; an empty period returns an empty report object rather than
  null.
- **Dependencies:** T-18.1.1.2

**T-18.1.2.2 — Period boundary and midnight-spanning rules** · `P1` · `2 pts` · deps: `T-18.1.2.1`
- **Description:** Implement timezone-aware period boundary computation in `domain` and the documented
  attribution rule for visits spanning midnight and for DST transition days, shared by daily, weekly
  and monthly reports so the three can never disagree.
- **Acceptance Criteria:** a spring-forward day is 23 hours and an autumn-back day 25 hours in the
  boundary computation, covered by test; a visit spanning midnight appears in exactly one daily report;
  daily totals for a week sum to that week's weekly report total, asserted by test; the rule is
  documented in the module README.
- **Dependencies:** T-18.1.2.1

**T-18.1.2.3 — Missing-data and validation handling** · `P1` · `1 pt` · deps: `T-18.1.2.1`
- **Description:** Handle absent exit events, absent entry events and empty periods explicitly in the
  report model, and validate the requested date range at the boundary, rejecting future and
  out-of-range dates.
- **Acceptance Criteria:** missing exit renders an explicit "no exit recorded" marker, never a blank or
  fabricated value; an empty period yields a valid zero-total report; a future date is rejected with a
  400-class response and a clear message; validation is covered by boundary tests.
- **Dependencies:** T-18.1.2.1

---

#### US-18.1.3 — Generate weekly and monthly activity reports

**As an** FM Admin **I want** weekly and monthly activity reports **so that** I can see trends and
report building usage to the client.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-REP-01 (SRS B1)` — "daily, weekly, and monthly" · **ACS-gated** |
| **Dependencies** | US-18.1.2 |
| **Blocked by** | — (closes at "done against simulator" until 🔴 TODO-02) |

**Acceptance Criteria**
- **AC-1 — Given** a requested week or month **When** the report is generated **Then** it produces the
  same visitor-level facts as the daily report aggregated over the period, plus per-day breakdown,
  per-tenant totals and peak-day identification.
- **AC-2 — Given** weekly and monthly boundaries **When** they are computed **Then** the week start day
  and the month boundary use the same configured timezone as the daily report, and the week start is a
  documented configured value rather than an implicit locale default.
- **AC-3 — Given** the same underlying data **When** daily reports across a period are summed and
  compared against the corresponding weekly or monthly report **Then** the totals reconcile exactly —
  a discrepancy is a defect, and this reconciliation is an automated test.
- **AC-4 (negative) — Given** a monthly report over a month containing a DST transition **When**
  per-day breakdown is computed **Then** the 23-hour and 25-hour days are handled correctly and no
  visit is double-counted or lost at the transition.
- **AC-5 (negative) — Given** a monthly report over a period at `NFR-SCL-01 (SRS B1)` scale — roughly
  10,000 visitor records **When** it is generated **Then** generation streams results rather than
  materialising the full set in memory, and completes within the documented budget without exhausting
  heap.
- **AC-6 (negative) — Given** a period that is partially in the future — the current month **When** the
  report is generated **Then** it reports data to date with an explicit "period incomplete" indication
  rather than presenting a partial month as a complete one.

**Development Tasks**

**T-18.1.3.1 — Weekly and monthly aggregation** · `P1` · `2 pts` · deps: `T-18.1.2.2`
- **Description:** Implement weekly and monthly report use cases reusing the T-18.1.2.2 boundary logic,
  adding per-day breakdown, per-tenant totals and peak-day identification, with the week start day read
  from `vms.system_settings`.
- **Acceptance Criteria:** week start is configurable and defaults explicitly rather than by locale
  inference; per-tenant totals sum to the period total; peak day is deterministic under ties by a
  documented rule; a partially elapsed period is marked incomplete.
- **Dependencies:** T-18.1.2.2

**T-18.1.3.2 — Cross-period reconciliation tests** · `P1` · `2 pts` · deps: `T-18.1.3.1`
- **Description:** Build the automated reconciliation test suite asserting that summed daily reports
  equal the weekly report and summed weekly/daily reports equal the monthly report, including across
  month boundaries, DST transitions and periods containing holidays from `vms.holiday_calendar`.
- **Acceptance Criteria:** reconciliation holds across a synthetic year of generated data; DST months
  reconcile; a month boundary falling mid-week reconciles; the suite runs in CI and fails the build on
  any discrepancy.
- **Dependencies:** T-18.1.3.1

**T-18.1.3.3 — Streaming generation for large periods** · `P1` · `1 pt` · deps: `T-18.1.3.1`
- **Description:** Implement cursor-based streaming for large-period generation so detail rows are
  processed incrementally, with a configurable maximum period length rejected at validation rather than
  attempted and failed.
- **Acceptance Criteria:** a 12-month report at `NFR-SCL-01 (SRS B1)` scale generates within the
  documented budget with bounded heap, verified by a performance test; a period beyond the configured
  maximum is rejected with a clear message; streaming produces byte-identical output to non-streaming
  for a small period.
- **Dependencies:** T-18.1.3.1

---

### F-18.2 — End-of-day credentials & cards report

On-demand end-of-day report of credentials and cards issued and returned.

| | |
|---|---|
| **Provenance** | `SRS` `FR-REP-02 (SRS B1)` |
| **Priority** | P1 · **Points** 8 · **Stories** 2 |
| **Depends on** | US-18.1.1; EPIC-15 (card accountability) |

> ⚠️ **Transitively under-specified.** `FR-REP-02 (SRS B1)` reconciles "via SRS §3.6", i.e.
> `FR-CRD-03 (SRS B1)`, whose day cut-off and timezone 🟠 **TODO-11** flags as undefined. This feature
> consumes EPIC-15's definition and defines none of it.

---

#### US-18.2.1 — Pull the end-of-day credentials and cards report

**As a** Master Admin (central receptionist) **I want** to pull an end-of-day report of credentials and
cards issued and returned **so that** I can close the desk knowing what is outstanding.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-REP-02 (SRS B1)` · day boundary gated on 🟠 **TODO-11** |
| **Dependencies** | US-18.1.1; EPIC-15 F-15.1, F-15.2, F-15.3 |
| **Blocked by** | — (TODO-11 must resolve before close) |

**Acceptance Criteria**
- **AC-1 — Given** the central receptionist requests the end-of-day report **When** it is generated
  **Then** it shows total credentials issued, cards issued from `vms.card_issuances`, cards returned
  (`returned_at IS NOT NULL`) and cards outstanding (`returned_at IS NULL`) for the reporting day.
- **AC-2 — Given** cards are outstanding **When** the report is generated **Then** each is listed with
  `acs_card_id`, visitor name, host, issue time and issuing user from `vms.card_issuances.issued_by`,
  so the receptionist can chase it.
- **AC-3 — Given** the report reconciles against EPIC-15 **When** figures are computed **Then** they
  agree exactly with the `FR-CRD-03 (SRS B1)` daily reconciliation for the same day — the report and
  the reconciliation are two views of one truth, asserted by test.
- **AC-4 — Given** the report is requested **When** the day boundary is applied **Then** it uses the
  cut-off and timezone defined by EPIC-15 under TODO-11, obtained from that context rather than
  redefined here, and the report states the boundary it used.
- **AC-5 (negative) — Given** a day on which no credentials or cards were issued **When** the report is
  pulled **Then** it returns valid zero totals with a "no activity" indication rather than an error.
- **AC-6 (negative) — Given** a card issued on one day and returned on the next **When** both days'
  reports are generated **Then** it appears outstanding on the first and returned on the second, and
  the two reports do not double-count it as issued twice.

**Development Tasks**

**T-18.2.1.1 — End-of-day report query and assembly** · `P1` · `2 pts` · deps: `T-18.1.1.2`
- **Description:** Implement the end-of-day report use case over the read model's credential and card
  facts, producing issued, returned and outstanding totals plus the outstanding card detail list, with
  the day boundary supplied by the Entry context rather than computed locally.
- **Acceptance Criteria:** totals reconcile against detail rows; outstanding list uses the
  `idx_cards_open` partial-index equivalent in the read model; the day boundary is an input, not a
  constant; an empty day returns valid zero totals.
- **Dependencies:** T-18.1.1.2, EPIC-15 F-15.3

**T-18.2.1.2 — Reconciliation agreement test with EPIC-15** · `P1` · `2 pts` · deps: `T-18.2.1.1`
- **Description:** Build the integration test asserting the report's figures match the EPIC-15 daily
  reconciliation output for the same day across scenarios including cross-midnight returns, cards never
  returned, and cards returned before issue-day cut-off.
- **Acceptance Criteria:** report and reconciliation agree in every scenario; a cross-day return is
  counted once as issued and once as returned on the correct days; a discrepancy fails the build; the
  test covers the TODO-11 cut-off as a parameter rather than a fixed value.
- **Dependencies:** T-18.2.1.1

**T-18.2.1.3 — On-demand report endpoint for central reception** · `P1` · `1 pt` · deps: `T-18.2.1.1`
- **Description:** Expose the on-demand endpoint in `interfaces` for the central receptionist,
  permission-gated on `report.view`, returning the report with the applied day boundary stated in the
  response.
- **Acceptance Criteria:** endpoint denies by default without `report.view`; the response states the
  boundary and timezone used; response time meets the documented budget at full-day scale; requesting a
  future day is rejected with a clear message.
- **Dependencies:** T-18.2.1.1, EPIC-03 F-03.2

---

#### US-18.2.2 — Restrict report access and scope PII by role

**As a** System Administrator **I want** report access authorized and PII scoped by role **so that** a
floor receptionist cannot pull the whole building's visitor list.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS-NFR` `NFR-SEC-01 (SRS B1)` — "visitor personal data shall be access-controlled by user role" · scoping model gated on 🟠 **TODO-14** |
| **Dependencies** | US-18.1.2, US-18.2.1; EPIC-03 F-03.2 |
| **Blocked by** | — (TODO-14 must resolve before close for the tenant-scoping portion) |

**Acceptance Criteria**
- **AC-1 — Given** any report endpoint **When** it is called without the `report.view` permission
  **Then** it is denied, and the denial is the default for any endpoint missing an explicit permission
  declaration — deny-by-default, verified by an automated test enumerating every reporting endpoint.
- **AC-2 — Given** a report is generated **When** the response is assembled **Then** visitor PII
  included is limited to what the requesting role needs: full detail for Master Admin and FM Admin,
  and name and appointment only for roles with no operational need for contact details.
- **AC-3 — Given** a report is accessed **When** the request completes **Then** an entry is written to
  `vms.audit_logs` recording the user, report type, period and filters — because a report access is a
  bulk PII access and must be attributable.
- **AC-4 (negative, blocked) — Given** 🟠 **TODO-14** leaves tenant and floor data isolation undefined
  **When** scoping is implemented **Then** the **conservative** interpretation applies — a
  tenant-scoped user sees only their own tenant's visitors, a floor receptionist only their floor's —
  and this is recorded as a provisional decision pending TODO-14 rather than presented as settled.
- **AC-5 (negative) — Given** a user attempts to widen scope by manipulating a request parameter — a
  tenant id they do not own **When** the request is processed **Then** the scope is derived from the
  authenticated principal's own tenant and reception associations on `vms.users`, never from a
  client-supplied parameter.

**Development Tasks**

**T-18.2.2.1 — Deny-by-default authorization on reporting endpoints** · `P1` · `1 pt` · deps: `T-18.2.1.3`
- **Description:** Apply the EPIC-03 F-03.2 API-boundary enforcement to every reporting endpoint, with
  an automated test enumerating the reporting controllers and failing the build if any endpoint lacks
  an explicit permission declaration.
- **Acceptance Criteria:** every reporting endpoint declares a permission; a newly added endpoint
  without one fails the build; anonymous and insufficiently privileged requests receive the correct
  denial without leaking whether data exists.
- **Dependencies:** T-18.2.1.3, EPIC-03 F-03.2

**T-18.2.2.2 — Role-based PII projection and principal-derived scoping** · `P1` · `1 pt` · deps: `T-18.2.2.1`
- **Description:** Implement per-role PII projection of report rows and derive tenant/floor scope from
  the authenticated principal's `tenant_id` and `reception_id` on `vms.users`, ignoring any
  client-supplied scope parameter. Record the TODO-14 provisional decision.
- **Acceptance Criteria:** a restricted role's report response contains no visitor email, phone or
  `id_document_ref`, asserted by test; a forged tenant parameter does not widen results; scope
  derivation is covered per role; the TODO-14 provisional decision is recorded in the traceability
  matrix.
- **Dependencies:** T-18.2.2.1

**T-18.2.2.3 — Report access audit logging** · `P1` · `1 pt` · deps: `T-18.2.2.1`
- **Description:** Write a `vms.audit_logs` entry per report access capturing user, action, report
  type, period, filters and IP, without copying report content into `before_state` or `after_state`.
- **Acceptance Criteria:** every report access produces exactly one audit entry; the entry carries no
  visitor PII and no report payload; failed authorization attempts are also recorded; entries are
  queryable by user and time via `idx_audit_user_time`.
- **Dependencies:** T-18.2.2.1, EPIC-05 F-05.1

---

### F-18.3 — Report scheduling & automatic generation

Automatic generation of the `FR-REP-01 (SRS B1)` reports on a schedule.

| | |
|---|---|
| **Provenance** | `SRS` `FR-REP-01 (SRS B1)` — "VMS shall **automatically** generate daily, weekly, and monthly ... reports" |
| **Priority** | P1 · **Points** 10 · **Stories** 2 |
| **Depends on** | F-18.1; F-16.2 (for delivery) |

> The word "automatically" in `FR-REP-01 (SRS B1)` is what makes this feature SRS-backed rather than
> TDD-derived — the requirement does not merely ask for reports that can be run, it asks for reports
> that generate themselves.

---

#### US-18.3.1 — Generate reports automatically on schedule

**As an** FM Admin **I want** the daily, weekly and monthly reports generated automatically **so that**
they exist without anyone remembering to run them.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-REP-01 (SRS B1)` |
| **Dependencies** | US-18.1.2, US-18.1.3 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the configured schedules **When** each period closes **Then** the corresponding
  report is generated automatically, persisted with its period and generation timestamp, and made
  retrievable without regeneration.
- **AC-2 — Given** multiple service instances **When** a scheduled generation is due **Then** exactly
  one instance generates it, enforced by a distributed lock, and no duplicate report is stored for the
  same period and type.
- **AC-3 — Given** a scheduled generation runs **When** it completes or fails **Then** the outcome is
  recorded so a missing report is distinguishable from a report over an empty period — silence is never
  ambiguous.
- **AC-4 (negative) — Given** the scheduler was down when a period closed **When** it restarts **Then**
  it detects and generates the missed period rather than skipping it permanently, bounded by a
  configured catch-up window so a long outage does not trigger unbounded backfill.
- **AC-5 (negative) — Given** generation fails partway — a projector lag, a database timeout **When**
  the failure occurs **Then** no partial report is persisted or delivered, the failure is retried with
  backoff, and an operator alert is raised via F-17.5 after exhaustion.
- **AC-6 (negative) — Given** the reporting projector is materially behind when a scheduled generation
  fires **When** the run starts **Then** it defers rather than generating a report from incomplete data,
  and the deferral is recorded — a report that is wrong is worse than a report that is late.

**Development Tasks**

**T-18.3.1.1 — Scheduled generation jobs with distributed lock** · `P1` · `2 pts` · deps: `T-18.1.3.1`
- **Description:** Implement scheduled jobs for daily, weekly and monthly generation in `interfaces`,
  guarded by the same Redis distributed lock mechanism as T-17.4.1.1, with cron expressions and the
  execution timezone configured in `vms.system_settings`.
- **Acceptance Criteria:** exactly one instance runs each scheduled generation under a multi-instance
  test; schedules and timezone are configurable without redeploy; the daily job fires after the day
  boundary defined in T-18.1.2.2; job execution is observable as a metric.
- **Dependencies:** T-18.1.3.1

**T-18.3.1.2 — Report persistence, run ledger and catch-up** · `P1` · `2 pts` · deps: `T-18.3.1.1`
- **Description:** Persist generated reports with `(report_type, period_start, period_end)` uniqueness,
  and maintain a run ledger recording every attempt's outcome. On startup, detect missed periods within
  a configured catch-up window and generate them.
- **Acceptance Criteria:** a duplicate generation for the same period and type is prevented by
  constraint; the run ledger distinguishes success, failure, deferral and empty-period success; a
  missed period within the window is generated on restart; a period older than the window is recorded
  as permanently missed rather than backfilled.
- **Dependencies:** T-18.3.1.1

**T-18.3.1.3 — Projector lag guard and partial-failure safety** · `P1` · `1 pt` · deps: `T-18.3.1.2`
- **Description:** Check projection lag before generation and defer if it exceeds a configured
  threshold, and wrap generation so a partial failure persists nothing and retries with backoff before
  alerting through F-17.5.
- **Acceptance Criteria:** generation defers and records a deferral when lag exceeds the threshold; a
  simulated mid-generation failure leaves no persisted report; retry exhaustion raises a system alert;
  deferral automatically retries once lag recovers.
- **Dependencies:** T-18.3.1.2, T-17.5.1.1

---

#### US-18.3.2 — Administer schedules and deliver generated reports

**As an** FM Admin **I want** to configure who receives which scheduled report **so that** the people
who need the numbers get them without logging in to look.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `SRS` `FR-REP-01 (SRS B1)` for generation; delivery configuration is `TDD-DERIVED` (TDD §4.5) |
| **Dependencies** | US-18.3.1, US-16.2.1, US-18.2.2 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a scheduled report is generated **When** delivery is configured for it **Then** it
  is delivered to the configured recipients through the F-16.2 email channel, and the delivery is
  logged in `vms.notification_logs` like any other notification.
- **AC-2 — Given** a report is delivered by email **When** the message is composed **Then** it carries
  a **secure retrieval link, not the report content inline**, so bulk visitor PII is not scattered
  across mailboxes and retrieval remains subject to the US-18.2.2 authorization checks.
- **AC-3 — Given** an administrator configures a schedule **When** the change is saved **Then** it is
  permission-gated and written to `vms.audit_logs` with before and after state.
- **AC-4 (negative) — Given** a configured recipient is not authorized to view the report's scope
  **When** delivery is configured or executed **Then** the recipient is rejected at configuration time —
  a scheduled delivery must not become a route around the authorization model.
- **AC-5 (negative) — Given** the retrieval link is used after the report's retention period, or by an
  unauthenticated party **When** retrieval is attempted **Then** it is denied and the attempt is
  audited — the link is a pointer, never a bearer credential granting standalone access.
- **AC-6 (negative) — Given** email delivery fails for a scheduled report **When** the failure occurs
  **Then** the report itself remains generated and retrievable, and only the delivery is retried —
  a delivery failure must not invalidate the report.

**Development Tasks**

**T-18.3.2.1 — Schedule administration API and audit** · `P2` · `2 pts` · deps: `T-18.3.1.1`
- **Description:** Build the CRUD API for report schedule configuration — report type, cron, recipients,
  scope — permission-gated, validated, and audited to `vms.audit_logs` with before and after state.
- **Acceptance Criteria:** administration denies by default without the settings permission; invalid
  cron expressions are rejected at save time; every change writes an audit entry with before and after;
  a recipient lacking authorization for the configured scope is rejected at save time.
- **Dependencies:** T-18.3.1.1, EPIC-03 F-03.2, EPIC-05 F-05.1

**T-18.3.2.2 — Secure retrieval link generation** · `P2` · `2 pts` · deps: `T-18.3.1.2`
- **Description:** Generate a retrieval link per delivered report that resolves to the authenticated,
  authorization-checked retrieval endpoint — the link identifies the report, it does not authorize
  access. Enforce expiry aligned to report retention and audit every retrieval attempt.
- **Acceptance Criteria:** an unauthenticated retrieval is denied; an authenticated but unauthorized
  retrieval is denied and audited; an expired link is denied; the link contains no PII and no report
  content, and no report data appears in a URL query parameter.
- **Dependencies:** T-18.3.1.2, T-18.2.2.1

**T-18.3.2.3 — Scheduled delivery dispatch via the notification platform** · `P2` · `1 pt` · deps: `T-18.3.2.2`
- **Description:** Register report delivery as a notification scenario on the F-16.1 registry so it
  inherits retry, dead-letter and delivery-status logging, with delivery failure isolated from report
  generation.
- **Acceptance Criteria:** delivery is logged in `vms.notification_logs` with the appropriate
  `notify_type`; a delivery failure retries without regenerating the report; the report stays
  retrievable regardless of delivery outcome; the message body contains a link only, never report rows.
- **Dependencies:** T-18.3.2.2, T-16.1.1.3

---

### F-18.4 — Audit report

Reporting surface over the append-only audit trail.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` `FR-REP-06` — **cited by TDD §4.5 and by the schema comment on `vms.audit_logs`; not defined in the attached SRS** |
| **Priority** | P2 · **Points** 8 · **Stories** 2 |
| **Depends on** | EPIC-05 F-05.1 (append-only audit log) |

> ⚠️ **No SRS requirement backs this feature.** The schema comment on `vms.audit_logs` reads
> *"Append-only audit trail (FR-AUD-01); surfaced via FR-REP-06"* — but **neither `FR-AUD-01` nor
> `FR-REP-06` is defined in the attached SRS**. The audit log itself is EPIC-05 (also TDD-derived);
> this feature is only the reporting surface over it. Backlog only pending TODO-01.

---

#### US-18.4.1 — Query and report on the audit trail

**As a** System Administrator **I want** to query the audit trail over a period and entity **so that**
I can answer "who changed this, and when" during an investigation.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` `FR-REP-06` (TDD §4.5) — **undefined in SRS** |
| **Dependencies** | EPIC-05 F-05.1; US-18.2.2 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** entries exist in `vms.audit_logs` **When** an audit report is requested **Then** it
  returns entries filtered by date range, `user_id`, `action`, `entity_type` and `entity_id`, showing
  actor, action, entity, timestamp and `ip_address`.
- **AC-2 — Given** an entry has `before_state` and `after_state` JSONB **When** it is displayed
  **Then** the change is presented as a readable field-level diff rather than raw JSON dumps.
- **AC-3 — Given** the audit report is queried **When** the query executes **Then** it uses
  `idx_audit_user_time` or `idx_audit_entity` as appropriate, and a query over a wide date range
  completes within the documented budget rather than scanning the whole table.
- **AC-4 (negative) — Given** a period with no matching audit entries **When** the report is generated
  **Then** it returns an empty result set with a clear indication, not an error.
- **AC-5 (negative) — Given** `before_state` or `after_state` contains visitor PII captured from an
  entity change **When** the audit report is rendered **Then** PII fields are masked according to the
  requesting role, so the audit report does not become an unrestricted PII export route.
- **AC-6 (negative) — Given** an unbounded query — no filters, full date range **When** it is submitted
  **Then** it is rejected or forcibly bounded with an explicit maximum range, rather than attempting to
  return the entire audit history.

**Development Tasks**

**T-18.4.1.1 — Audit query API with bounded filters** · `P2` · `2 pts` · deps: `—`
- **Description:** Implement the paginated audit query endpoint over `vms.audit_logs` supporting the
  documented filter set, with a mandatory bounded date range and a configured maximum span, and keyset
  pagination stable against the append-only insert stream.
- **Acceptance Criteria:** queries use the intended indexes, verified by execution-plan assertion; an
  unbounded or over-wide range is rejected with a clear message; keyset pagination does not skip or
  repeat rows while new entries are appended; an empty result returns an empty page, not an error.
- **Dependencies:** EPIC-05 F-05.1

**T-18.4.1.2 — Field-level diff rendering** · `P2` · `2 pts` · deps: `T-18.4.1.1`
- **Description:** Implement a diff renderer over the `before_state`/`after_state` JSONB producing a
  field-level added/removed/changed view, handling nulls, nested objects and entries where one side is
  absent (creation or deletion).
- **Acceptance Criteria:** a creation shows all fields as added; a deletion shows all as removed; a
  nested change is located to its field path; malformed or unparseable JSONB renders a fallback notice
  rather than erroring the page; the renderer is unit-tested over each shape.
- **Dependencies:** T-18.4.1.1

**T-18.4.1.3 — PII masking in audit output** · `P2` · `1 pt` · deps: `T-18.4.1.2`
- **Description:** Apply role-based masking to PII fields appearing within audit state payloads —
  visitor email, phone, `id_document_ref` — reusing the T-18.2.2.2 projection rules so audit output
  cannot exceed the PII the role could see directly.
- **Acceptance Criteria:** a restricted role sees masked PII within `before_state`/`after_state`; the
  masking is applied on the server, never client-side; an unmasked field cannot be recovered from the
  response payload; masking is covered by test per role.
- **Dependencies:** T-18.4.1.2, T-18.2.2.2

---

#### US-18.4.2 — Restrict and protect the audit report

**As a** System Administrator **I want** the audit report tightly restricted and its own access audited
**so that** the audit trail is evidence rather than just another data view.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` `FR-REP-06` (TDD §4.5) — **undefined in SRS**; access control justified against `NFR-SEC-01 (SRS B1)` |
| **Dependencies** | US-18.4.1 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** the audit report **When** access is attempted **Then** it requires a dedicated
  audit-view permission distinct from the general `report.view`, so viewing visitor reports does not
  imply viewing the audit trail.
- **AC-2 — Given** the audit report is accessed **When** the request completes **Then** the access
  itself is written to `vms.audit_logs` — audit access is auditable, and the entry does not include the
  returned rows.
- **AC-3 — Given** the audit trail is append-only per EPIC-05 **When** the reporting surface is
  reviewed **Then** it exposes **read paths only**, with no update or delete route, verified by an
  architecture fitness test over the reporting module.
- **AC-4 (negative) — Given** a user attempts to view audit entries about their own privileged actions
  **When** the query runs **Then** no self-filtering or self-exclusion is possible from the client side —
  a user cannot construct a request that hides their own entries from another reviewer's view.
- **AC-5 (negative) — Given** audit access logging would itself generate an audit entry that is then
  reported **When** access is logged **Then** the recursion is bounded — audit-access entries are
  recorded but excluded by default from the audit report's own results unless explicitly requested, so
  the trail is not flooded with self-reference.

**Development Tasks**

**T-18.4.2.1 — Dedicated audit permission and read-only enforcement** · `P2` · `2 pts` · deps: `T-18.4.1.1`
- **Description:** Introduce a distinct audit-view permission, gate the audit endpoints on it, and add
  an architecture fitness test asserting the reporting module exposes no write, update or delete path
  to `vms.audit_logs`.
- **Acceptance Criteria:** `report.view` alone does not grant audit access; the fitness test fails the
  build if any write path to `vms.audit_logs` appears in the reporting module; the permission is
  seeded via migration and assignable through EPIC-03 administration.
- **Dependencies:** T-18.4.1.1, EPIC-03 F-03.2

**T-18.4.2.2 — Audit-access logging with bounded recursion** · `P2` · `1 pt` · deps: `T-18.4.2.1`
- **Description:** Record every audit report access as an audit entry carrying the filters used but not
  the returned rows, and exclude audit-access entries from the report's default result set with an
  explicit opt-in filter to include them.
- **Acceptance Criteria:** every audit access produces exactly one entry; the entry contains filters but
  no result payload; default queries exclude audit-access entries; the opt-in filter surfaces them; no
  client-side parameter can suppress an entry from another reviewer's results.
- **Dependencies:** T-18.4.2.1

---


## EPIC-19 — Analytics & Export

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` — **the entire epic. No SRS requirement backs any part of it.** |
| **Requirement IDs** | `FR-ANL-01` (TDD §4.5) · `FR-EXP-01` (TDD §3, §4.5) — **neither is defined in the attached SRS** |
| **Priority** | P3 |
| **Stories / Tasks / Points** | 4 · 12 · **20** |
| **Blocked** | F-19.2 — 🟡 **TODO-16** |

**Goal.** Provide the analytics dashboard and the Excel/PDF export the TDD assumes. **This epic is the
clearest case in the whole project of design outrunning requirements.** SRS §3.8 — the entire reporting
and analytics section of the attached SRS — contains exactly two requirements, `FR-REP-01 (SRS B1)` and
`FR-REP-02 (SRS B1)`. Neither mentions a dashboard, a chart, Excel, or PDF. The TDD nevertheless
specifies both, and the schema goes further still by seeding a `report.export` permission described as
*"Export reports to Excel/PDF"* — a permission for a capability no requirement asks for.

> ⚠️ **If TODO-01 confirms the attached 28-requirement SRS as the delivery baseline, this entire epic
> descopes** — all 4 stories and 20 points. It is carried here so that cost is visible and the client
> can make that call knowingly rather than discovering the gap during a demo. **Do not schedule
> EPIC-19 into a sprint before TODO-01 and TODO-16 are both dispositioned.**

---

### F-19.1 — Analytics dashboard

Visual dashboard over the reporting read model.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` `FR-ANL-01` (TDD §4.5) — **undefined in SRS** |
| **Priority** | P3 · **Points** 10 · **Stories** 2 |
| **Depends on** | F-18.1 (read model), EPIC-06 (portal shell) |

---

#### US-19.1.1 — Expose aggregated analytics data

**As an** FM Admin **I want** aggregated visitor metrics available through an API **so that** the
dashboard renders from pre-aggregated figures rather than recomputing them on every page load.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` `FR-ANL-01` (TDD §4.5) — **undefined in SRS** |
| **Dependencies** | US-18.1.1, US-18.2.2 |
| **Blocked by** | — (TODO-01 disposition required before sprint entry) |

**Acceptance Criteria**
- **AC-1 — Given** the reporting read model **When** the analytics endpoint is called for a period
  **Then** it returns aggregated metrics — visitor volume over time, arrivals versus no-shows, average
  visit duration, peak hours, per-tenant and per-floor distribution, credential type mix.
- **AC-2 — Given** analytics are aggregates **When** the response is assembled **Then** it contains
  **counts and distributions only, never individual visitor records** — the analytics surface is not a
  back door around the US-18.2.2 PII scoping.
- **AC-3 — Given** a repeated request for the same period **When** it is served **Then** aggregates are
  cached in Redis per TDD §3 with a bounded TTL, and the cache key includes the requesting principal's
  scope so one tenant's cached aggregate cannot be served to another.
- **AC-4 (negative) — Given** a period with no visitor activity **When** analytics are requested
  **Then** zero-valued series are returned with an explicit "no data" indication, so the dashboard
  renders an empty state rather than breaking on nulls.
- **AC-5 (negative) — Given** a small period and a narrow scope — one tenant, one day, two visitors
  **When** aggregates are returned **Then** the low cardinality is flagged, because an "average visit
  duration" over two visitors is effectively individual data wearing an aggregate's clothes.
- **AC-6 (negative) — Given** a very wide period is requested **When** the query is validated **Then**
  it is bounded to a configured maximum span, consistent with T-18.1.3.3, rather than attempting an
  unbounded aggregation.

**Development Tasks**

**T-19.1.1.1 — Aggregation queries over the read model** · `P3` · `2 pts` · deps: `T-18.1.1.2`
- **Description:** Implement the analytics aggregation use cases over the reporting read model — time
  series, distributions and averages — returning aggregate types only, with period bounds validated
  against the configured maximum span.
- **Acceptance Criteria:** returned types cannot carry an individual visitor record, enforced by the
  response model's shape; aggregation queries use period indexes, verified by execution-plan assertion;
  an over-wide period is rejected; an empty period yields explicit zero series.
- **Dependencies:** T-18.1.1.2

**T-19.1.1.2 — Scope-aware caching** · `P3` · `2 pts` · deps: `T-19.1.1.1`
- **Description:** Cache aggregates in Redis keyed on period, metric and the principal's derived scope,
  with a bounded TTL and invalidation when the projector advances materially.
- **Acceptance Criteria:** a cache key collision across differently scoped principals is impossible,
  proven by test; a repeated request within TTL does not re-query the database; the cache invalidates
  on projector advance; the cache key is environment-scoped.
- **Dependencies:** T-19.1.1.1, T-18.2.2.2

**T-19.1.1.3 — Low-cardinality flagging** · `P3` · `1 pt` · deps: `T-19.1.1.1`
- **Description:** Flag aggregate results computed over fewer than a configured minimum number of
  underlying records, so the dashboard can present them as indicative rather than statistical.
- **Acceptance Criteria:** an aggregate over fewer than the threshold carries a low-cardinality flag;
  the threshold is configurable; the flag propagates to the dashboard response; the behaviour is
  documented as an indicator, not an anonymisation guarantee.
- **Dependencies:** T-19.1.1.1

---

#### US-19.1.2 — Render the analytics dashboard

**As an** FM Admin **I want** a visual dashboard of visitor activity **so that** I can see patterns at a
glance instead of reading tables.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` `FR-ANL-01` (TDD §4.5) — **undefined in SRS** |
| **Dependencies** | US-19.1.1; EPIC-06 F-06.2, F-06.3, F-06.4 |
| **Blocked by** | — (TODO-01 disposition required) |

**Acceptance Criteria**
- **AC-1 — Given** an authorized user opens the dashboard **When** it loads **Then** it renders the
  US-19.1.1 metrics as charts with a period selector and tenant/floor filters constrained to the user's
  authorized scope.
- **AC-2 — Given** the design system from EPIC-06 **When** the dashboard renders **Then** it uses the
  shared component library and the client branding from `FR-ADM-03 (SRS B1)`, and meets the WCAG 2.1
  AA baseline from F-06.4 — including chart information conveyed by more than colour alone.
- **AC-3 — Given** a user without the analytics permission **When** they navigate to the dashboard
  **Then** the route is not presented in navigation and direct navigation is denied by the API
  regardless of what the client renders.
- **AC-4 (negative) — Given** the analytics API is slow or unavailable **When** the dashboard loads
  **Then** each panel degrades independently with a retry affordance, and one failing panel does not
  blank the page.
- **AC-5 (negative) — Given** a period with no data **When** the dashboard renders **Then** each chart
  shows an explicit empty state, not a broken axis, an infinite spinner, or a chart of zeros presented
  as though it were data.

**Development Tasks**

**T-19.1.2.1 — Dashboard shell, routing and permission gating** · `P3` · `2 pts` · deps: `T-19.1.1.1`
- **Description:** Build the dashboard route in the portal shell with period and scope selectors,
  permission-gated navigation, and per-panel loading and error boundaries.
- **Acceptance Criteria:** navigation hides the route without the permission and the API denies direct
  access regardless; scope selectors offer only authorized values; a panel failure is isolated to that
  panel; the period selector respects the configured maximum span.
- **Dependencies:** T-19.1.1.1, EPIC-06 F-06.3

**T-19.1.2.2 — Chart components with accessible empty states** · `P3` · `2 pts` · deps: `T-19.1.2.1`
- **Description:** Implement the chart components on the EPIC-06 shared component library with explicit
  empty, loading, error and low-cardinality states, and non-colour-dependent encoding.
- **Acceptance Criteria:** every chart has a distinct empty state, verified by test; chart data is
  available in an accessible tabular alternative; automated accessibility checks pass at WCAG 2.1 AA;
  the low-cardinality flag is surfaced visibly.
- **Dependencies:** T-19.1.2.1, EPIC-06 F-06.2, EPIC-06 F-06.4

**T-19.1.2.3 — Branding and responsive layout** · `P3` · `1 pt` · deps: `T-19.1.2.2`
- **Description:** Apply the design tokens and client branding from F-06.1 and verify the dashboard
  layout at reception-workstation and standard desktop resolutions.
- **Acceptance Criteria:** dashboard uses design tokens with no hard-coded colours; branding matches
  `FR-ADM-03 (SRS B1)`; layout is verified at target resolutions; no horizontal scroll at the minimum
  supported width.
- **Dependencies:** T-19.1.2.2, EPIC-06 F-06.1

---

### F-19.2 — Excel & PDF export ⛔ BLOCKED

Export of reports to Excel and PDF.

| | |
|---|---|
| **Provenance** | `BLOCKED` / `TDD-DERIVED` `FR-EXP-01` (TDD §3, §4.5) · 🟡 **TODO-16** |
| **Priority** | P3 · **Points** 10 · **Stories** 2 |
| **Depends on** | F-18.1, F-18.2 |

> ⛔ **Blocked on TODO-16, and TDD-only.** As TODO-16 states plainly: *"TDD §3 and §4.5 require Excel
> and PDF export (`FR-EXP-01`); the attached SRS §3.8 requires neither."* **Neither format is asked for
> by any requirement in the delivery baseline.** The seeded `report.export` permission in the schema is
> the design's assumption made durable, not evidence of a requirement.
>
> Export is also the single highest-risk PII egress path in the system: it produces an offline file of
> visitor personal data that leaves every access control behind the moment it is downloaded. That is
> reason enough not to build it speculatively.

---

#### US-19.2.1 — Export a report to Excel

**As an** FM Admin **I want** to export a report to Excel **so that** I can do my own analysis and share
figures with the client.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` / `TDD-DERIVED` `FR-EXP-01` (TDD §3, §4.5) — **undefined in SRS** · 🟡 TODO-16 |
| **Dependencies** | US-18.1.2, US-18.2.2 |
| **Blocked by** | 🟡 **TODO-16** — confirm export is in scope for release 1.0 |

**Acceptance Criteria**
- **AC-1 — Given** an authorized user with the `report.export` permission **When** they export a
  generated report **Then** an `.xlsx` file is produced server-side per TDD §3 containing the same rows
  and totals as the on-screen report, with typed cells rather than strings.
- **AC-2 — Given** export is a bulk PII egress **When** an export completes **Then** an entry is
  written to `vms.audit_logs` recording user, report type, period, filters and row count — an exported
  file is untrackable once downloaded, so the act of exporting must be recorded.
- **AC-3 — Given** the requesting role's PII scope **When** the file is generated **Then** it contains
  exactly the columns that role could see on screen — export must not widen access, and a masked field
  must not appear unmasked in the file.
- **AC-4 (negative) — Given** a very large result set — a 12-month report at `NFR-SCL-01 (SRS B1)`
  scale **When** the export runs **Then** it streams rows to the output rather than materialising the
  workbook in memory, is bounded by a configured maximum row count, and returns a clear message when
  the bound is exceeded rather than failing with an out-of-memory error.
- **AC-5 (negative) — Given** a cell value beginning with `=`, `+`, `-` or `@` — a company name entered
  as `=cmd|...` **When** it is written **Then** it is neutralised against CSV/formula injection, so
  opening the file cannot execute a payload on the recipient's machine.
- **AC-6 (negative) — Given** export generation fails partway **When** the failure occurs **Then** no
  partial file is delivered, the failure is reported clearly, and the audit entry records the attempt
  as failed rather than successful.

**Development Tasks**

**T-19.2.1.1 — Streaming Excel writer** · `P3` · `2 pts` · deps: `T-18.1.2.1`
- **Description:** Implement server-side `.xlsx` generation with a streaming writer (SXSSF or
  equivalent) consuming the T-18.1.3.3 cursor stream, with typed cells, a bounded maximum row count,
  and no full-workbook materialisation.
- **Acceptance Criteria:** a maximum-size export completes within bounded heap, verified by a
  performance test; exceeding the row bound returns a clear message before generation begins; cells
  carry correct types for dates and numbers; a partial failure delivers no file.
- **Dependencies:** T-18.1.3.3

**T-19.2.1.2 — Formula injection neutralisation and PII scoping** · `P3` · `2 pts` · deps: `T-19.2.1.1`
- **Description:** Neutralise leading `=`, `+`, `-` and `@` in text cells, and apply the T-18.2.2.2
  role-based column projection to the export path so the file cannot contain more than the screen view.
- **Acceptance Criteria:** an injection payload in a visitor or company name is neutralised, verified by
  test; a restricted role's export omits the same columns its screen view omits; a masked field is
  never unmasked in the file; the projection is applied server-side on the same code path as the screen
  view.
- **Dependencies:** T-19.2.1.1, T-18.2.2.2

**T-19.2.1.3 — Export audit logging** · `P3` · `1 pt` · deps: `T-19.2.1.1`
- **Description:** Write a `vms.audit_logs` entry for every export attempt capturing user, report type,
  period, filters, row count and outcome, without copying exported content into the audit state fields.
- **Acceptance Criteria:** every attempt is audited including failures; the entry contains no exported
  rows and no visitor PII; the entry is queryable by user and time; a denied export attempt is also
  recorded.
- **Dependencies:** T-19.2.1.1, EPIC-05 F-05.1

---

#### US-19.2.2 — Export a report to PDF

**As an** FM Admin **I want** to export a report to PDF **so that** I can circulate a fixed, presentable
version that will not be edited.

| | |
|---|---|
| **Priority** | P3 |
| **Story Points** | 5 |
| **Provenance** | `BLOCKED` / `TDD-DERIVED` `FR-EXP-01` (TDD §3, §4.5) — **undefined in SRS** · 🟡 TODO-16 |
| **Dependencies** | US-19.2.1 |
| **Blocked by** | 🟡 **TODO-16** |

**Acceptance Criteria**
- **AC-1 — Given** an authorized user exports to PDF **When** the file is generated server-side
  **Then** it contains the report content with client branding per `FR-ADM-03 (SRS B1)`, a header
  showing report type and period, and page numbering.
- **AC-2 — Given** the PDF is generated **When** it is produced **Then** generation happens server-side
  per TDD §3, with the same permission checks, PII projection and audit logging as the Excel path —
  the two formats share one authorization and audit code path, not two.
- **AC-3 — Given** a report containing many rows **When** it is rendered to PDF **Then** rows paginate
  cleanly with repeating table headers, and no row is split across a page boundary.
- **AC-4 (negative) — Given** a very large result set **When** a PDF export is requested **Then** it is
  bounded by a configured maximum page or row count and refused with a clear message suggesting the
  Excel format — a 4,000-page PDF is not a useful artifact, and generating it is a denial-of-service
  risk against the reporting service.
- **AC-5 (negative) — Given** report content containing untrusted text — a visitor or company name
  **When** it is rendered **Then** it is escaped for the rendering engine, and if an HTML-to-PDF
  pipeline is used the renderer is sandboxed with no network or filesystem access, so a payload in a
  name cannot trigger a server-side request.
- **AC-6 (negative) — Given** PDF generation exceeds its time budget **When** the timeout fires **Then**
  it is cancelled, resources are released, a clear error is returned, and a stuck render cannot
  accumulate and exhaust the service.

**Development Tasks**

**T-19.2.2.1 — Server-side PDF renderer with branding** · `P3` · `2 pts` · deps: `T-19.2.1.1`
- **Description:** Implement server-side PDF generation with client branding, headers, pagination and
  repeating table headers, sharing the report model and the authorization/audit path with the Excel
  exporter.
- **Acceptance Criteria:** branding matches the F-06.1 tokens; table headers repeat on every page; no
  row splits across pages; the permission check and audit entry come from the shared export path, not a
  duplicate implementation.
- **Dependencies:** T-19.2.1.1, EPIC-06 F-06.1

**T-19.2.2.2 — Renderer sandboxing and escaping** · `P3` · `2 pts` · deps: `T-19.2.2.1`
- **Description:** Escape all untrusted content for the rendering engine and, if an HTML-based
  pipeline is used, run the renderer with network access, filesystem access and external entity
  resolution disabled.
- **Acceptance Criteria:** an SSRF payload in a company name triggers no outbound request, verified by
  test; external entity resolution is disabled; the renderer cannot read local files; escaping is
  covered by test for each untrusted field.
- **Dependencies:** T-19.2.2.1

**T-19.2.2.3 — Size bounds and generation timeout** · `P3` · `1 pt` · deps: `T-19.2.2.1`
- **Description:** Enforce a configured maximum page or row count for PDF export, refusing oversized
  requests with a message pointing at Excel, and apply a generation timeout with resource cleanup and
  bounded concurrent renders.
- **Acceptance Criteria:** an oversized request is refused before generation with a clear alternative;
  a generation exceeding the timeout is cancelled and resources released; concurrent renders are
  bounded; repeated oversized requests cannot exhaust the service, verified by load test.
- **Dependencies:** T-19.2.2.1

---


## Phase 4 summary

### Per-epic totals

| Epic | Title | Features | Stories | Tasks | Points | Provenance |
|---|---|---|---|---|---|---|
| EPIC-16 | Notification Platform | 5 | 13 | 39 | 62 | Mixed — 1 `SRS`, 2 `TDD-DERIVED`, 2 `BLOCKED` |
| EPIC-17 | Notification Scenarios | 5 | 10 | 27 | 40 | Mixed — 2 `SRS`, 3 `TDD-DERIVED` |
| EPIC-18 | Reporting Suite | 4 | 9 | 27 | 44 | Mixed — 3 `SRS`, 1 `TDD-DERIVED` |
| EPIC-19 | Analytics & Export | 2 | 4 | 12 | 20 | `TDD-DERIVED` entirely; 1 also `BLOCKED` |
| **Total** | | **16** | **36** | **105** | **166** | |

### Per-feature totals

| Feature | Title | Provenance | Stories | Tasks | Points |
|---|---|---|---|---|---|
| F-16.1 | Notification service & domain event subscription | `TDD-DERIVED` | 3 | 10 | 18 |
| F-16.2 | Email channel & delivery status logging | `SRS` FR-NOT-01 | 3 | 9 | 15 |
| F-16.3 | WhatsApp channel | `BLOCKED` TODO-05 | 2 | 6 | 8 |
| F-16.4 | Notification templates & localisation | `TDD-DERIVED` | 3 | 8 | 13 |
| F-16.5 | Channel selection & recipient preferences | `BLOCKED` TODO-10 | 2 | 6 | 8 |
| F-17.1 | Credential confirmation to visitor & host | `SRS` FR-NOT-01 | 2 | 6 | 8 |
| F-17.2 | Exit alerts to host & visitor | `SRS` FR-NOT-02 ⚠️ ACS | 2 | 6 | 10 |
| F-17.3 | Host notification — approved / in / out | `TDD-DERIVED` FR-NOT-03 | 2 | 5 | 6 |
| F-17.4 | Appointment reminders | `TDD-DERIVED` FR-NOT-04 | 2 | 5 | 8 |
| F-17.5 | System alerts to administrators | `TDD-DERIVED` FR-NOT-05 | 2 | 5 | 8 |
| F-18.1 | Daily / weekly / monthly visitor activity reports | `SRS` FR-REP-01 ⚠️ ACS | 3 | 10 | 18 |
| F-18.2 | End-of-day credentials & cards report | `SRS` FR-REP-02 | 2 | 6 | 8 |
| F-18.3 | Report scheduling & automatic generation | `SRS` FR-REP-01 | 2 | 6 | 10 |
| F-18.4 | Audit report | `TDD-DERIVED` FR-REP-06 | 2 | 5 | 8 |
| F-19.1 | Analytics dashboard | `TDD-DERIVED` FR-ANL-01 | 2 | 6 | 10 |
| F-19.2 | Excel & PDF export | `TDD-DERIVED` FR-EXP-01 · `BLOCKED` TODO-16 | 2 | 6 | 10 |

### Provenance and priority distribution

| Provenance | Stories | Points | Authorized to build today? |
|---|---|---|---|
| `SRS` (FR-NOT-01, FR-NOT-02, FR-REP-01, FR-REP-02) | 14 | 68 | ✅ Yes — 2 stories ACS-gated to "done against simulator" |
| `TDD-DERIVED` (FR-NOT-03/04/05, FR-REP-06, FR-ANL-01, FR-EXP-01) | 18 | 74 | ⚠️ Backlog only — TODO-01 |
| `BLOCKED` (TODO-05, TODO-10) | 4 | 16 | ⛔ No |
| Also blocked within `TDD-DERIVED` | *(2 of the 18: F-19.2)* | *(10)* | ⛔ No — TODO-16 |

| Priority | Stories | Points |
|---|---|---|
| P0 | 5 | 31 |
| P1 | 15 | 71 |
| P2 | 10 | 38 |
| P3 | 6 | 26 |

### SRS requirement coverage for this phase

| Requirement | Feature(s) | Stories | Verifiable today? |
|---|---|---|---|
| `FR-NOT-01 (SRS B1)` | F-16.2, F-16.3 ⛔, F-17.1 | US-16.2.1/2/3, US-17.1.1/2 | ✅ Email path fully. WhatsApp half blocked on TODO-05 — the requirement says "and/or", so email alone satisfies it. |
| `FR-NOT-02 (SRS B1)` | F-17.2 | US-17.2.1, US-17.2.2 | ⚠️ Against simulator only — 🔴 TODO-02 |
| `FR-REP-01 (SRS B1)` | F-18.1, F-18.3 | US-18.1.1/2/3, US-18.3.1/2 | ⚠️ VMS data fully; ACS event portion against simulator — 🔴 TODO-02 |
| `FR-REP-02 (SRS B1)` | F-18.2 | US-18.2.1, US-18.2.2 | ⚠️ Day boundary undefined — 🟠 TODO-11 must resolve before close |

**Coverage: 4 / 4 SRS requirements allocated to Phase 4 (100%).** None is fully verifiable end-to-end
until TODO-02 and TODO-11 resolve.

### Open questions touching this phase

| TODO | Severity | Effect on Phase 4 |
|---|---|---|
| TODO-01 | 🔴 Blocking | 18 stories / 74 points are `TDD-DERIVED` and cannot enter a sprint |
| TODO-02 | 🔴 Blocking | F-17.2 and F-18.1 close at "done against simulator" only |
| TODO-05 | 🟠 Material | F-16.3 blocked — no WhatsApp wire adapter |
| TODO-10 | 🟠 Material | F-16.5 blocked — channel selection rule undefined |
| TODO-11 | 🟠 Material | F-18.2's "day" and US-17.5.2's discrepancy alert parameters undefined |
| TODO-12 | 🟠 Material | Report and export retention has no defined period; retrieval-link expiry (T-18.3.2.2) has nothing to align to |
| TODO-14 | 🟠 Material | US-18.2.2 tenant/floor report scoping applies a provisional conservative rule |
| TODO-16 | 🟡 Minor | F-19.2 blocked — Excel and PDF export unrequirmented |

---

## Dependency graph

```
PHASE 1 ─────────────────────────────────────────────────────────────────────
  F-01.3 Kafka/Redis/PostgreSQL ──┐
  F-01.5 migrations ──────────────┤
  F-01.7 observability ───────────┤
  F-03.2 API authorization ───────┤
  F-05.1 audit log ───────────────┤
  F-05.2 secrets ─────────────────┤
  F-06.1/2/3/4 portal shell ──────┤
  F-04.9 master data cache ───────┘
                                  │
PHASE 2 ──────────────────────────┼──────────────────────────────────────────
  F-07.5 domain events ───────────┤
  F-07.2 group requests ──────────┤
  F-08.3 appointment windows ─────┤
  F-09.1 credential issuance ─────┤
                                  │
PHASE 3 ──────────────────────────┼───────────────── ⚠️ gated on TODO-02 ────
  F-11.2 ACS simulator ───────────┤
  F-11.4 dead-letter alerting ────┤
  F-12.2 check-in/out tracking ───┤
  F-12.3 access/exit events ──────┤
  F-15.2 card return ─────────────┤
  F-15.3/4 reconciliation ────────┤
                                  ▼
PHASE 4 ─────────────────────────────────────────────────────────────────────

  ┌─ EPIC-16 ─ NOTIFICATION PLATFORM ────────────────────────────────────┐
  │                                                                       │
  │   US-16.1.1  event subscription  (P0, 8)                              │
  │       │                                                               │
  │       ├──► US-16.1.2  dispatch queue + retry  (P1, 5)                 │
  │       │        │                                                      │
  │       │        └──► US-16.1.3  idempotency + notification_logs (P1,5) │
  │       │                 │                                             │
  │       │                 ├──► US-16.4.1  template registry  (P1, 5)    │
  │       │                 │        └──► US-16.4.2  safe rendering (P1,5)│
  │       │                 │                 └──► US-16.4.3  tz/locale   │
  │       │                 │                          (P2, 3)            │
  │       └──► US-16.2.1  email channel  (P0, 5)                          │
  │                │                                                      │
  │                ├──► US-16.2.2  delivery status  (P1, 5)               │
  │                ├──► US-16.2.3  gateway outage  (P1, 5)                │
  │                │                                                      │
  │                ├──► US-16.3.1  WhatsApp port ⛔TODO-05  (P3, 3)       │
  │                │        └──► US-16.3.2  WA adapter ⛔ DO NOT BUILD    │
  │                │                                                      │
  │                └──► US-16.5.1  channel resolver ⛔TODO-10  (P2, 3)    │
  │                         └──► US-16.5.2  preferences ⛔ DO NOT BUILD   │
  └───────────────────────────────────────────────────────────────────────┘
        │                                    │
        │  (F-16.1 + F-16.2 = 33 pts         │
        │   PULL FORWARD TO PHASE 2 —        │
        │   F-09.6 pass-by-email needs it)   │
        ▼                                    ▼
  ┌─ EPIC-17 ─ NOTIFICATION SCENARIOS ───────────────────────────────────┐
  │                                                                       │
  │   US-17.1.1  visitor confirmation  (P0, 5)   ◄── FR-NOT-01            │
  │       └──► US-17.1.2  host confirmation  (P1, 3)                      │
  │                │                                                      │
  │   US-17.2.1  host exit alert  (P1, 5)  ⚠️ACS  ◄── FR-NOT-02          │
  │       └──► US-17.2.2  visitor exit alert  (P1, 5)  ⚠️ACS             │
  │                                                                       │
  │   US-17.3.1  host approved/rejected  (P2, 3)  ⚠️TDD-DERIVED          │
  │       └──► US-17.3.2  host arrival  (P2, 3)   ⚠️TDD-DERIVED          │
  │                                                                       │
  │   US-17.4.1  reminder scheduler  (P2, 5)      ⚠️TDD-DERIVED          │
  │       └──► US-17.4.2  reminder suppression  (P2, 3) ⚠️TDD-DERIVED    │
  │                                                                       │
  │   US-17.5.1  integration alerts  (P2, 5)      ⚠️TDD-DERIVED          │
  │       └──► US-17.5.2  card discrepancy alert  (P2, 3) ⚠️TDD + TODO-11│
  └───────────────────────────────────────────────────────────────────────┘

  ┌─ EPIC-18 ─ REPORTING SUITE ──────────────────────────────────────────┐
  │                                                                       │
  │   US-18.1.1  reporting read model  (P0, 8)  ⚠️ACS  ◄── FR-REP-01     │
  │       │                                                               │
  │       ├──► US-18.1.2  daily report  (P1, 5)  ⚠️ACS                   │
  │       │        └──► US-18.1.3  weekly + monthly  (P1, 5)  ⚠️ACS      │
  │       │                 │                                             │
  │       │                 └──► US-18.3.1  scheduled generation (P1, 5)  │
  │       │                          └──► US-18.3.2  schedule admin       │
  │       │                                   + delivery  (P2, 5)         │
  │       │                                   ▲                           │
  │       │                                   └── needs US-16.2.1 (email) │
  │       │                                                               │
  │       ├──► US-18.2.1  end-of-day report  (P1, 5)  ◄── FR-REP-02       │
  │       │        │              ▲                    (day = TODO-11)    │
  │       │        │              └── needs EPIC-15 F-15.3                │
  │       │        └──► US-18.2.2  report authz + PII scoping  (P1, 3)    │
  │       │                 │            (tenant scope = TODO-14)         │
  │       │                 │                                             │
  │       └── US-18.4.1  audit report query  (P2, 5)  ⚠️TDD-DERIVED      │
  │                └──► US-18.4.2  audit access restriction  (P2, 3)      │
  └───────────────────────────────────────────────────────────────────────┘
        │
        ▼
  ┌─ EPIC-19 ─ ANALYTICS & EXPORT ─── ⚠️ ENTIRE EPIC TDD-DERIVED ────────┐
  │                                                                       │
  │   US-19.1.1  analytics aggregates  (P3, 5)    ⚠️TDD-DERIVED          │
  │       └──► US-19.1.2  analytics dashboard  (P3, 5)  ⚠️TDD-DERIVED    │
  │                                                                       │
  │   US-19.2.1  Excel export  (P3, 5)   ⛔TODO-16 · TDD-only            │
  │       └──► US-19.2.2  PDF export  (P3, 5)  ⛔TODO-16 · TDD-only      │
  └───────────────────────────────────────────────────────────────────────┘


CRITICAL PATH (SRS-backed only, 68 pts):
  US-16.1.1 ─► US-16.1.2 ─► US-16.1.3 ─► US-16.2.1 ─► US-17.1.1 ─► US-17.1.2
                                              │
                                              └─► US-16.2.2 ─► US-16.2.3
  US-18.1.1 ─► US-18.1.2 ─► US-18.1.3 ─► US-18.3.1 ─► US-18.3.2
       └─────► US-18.2.1 ─► US-18.2.2

LEGEND
  ⚠️ACS           implementable against the ACS simulator (ADR-0002);
                  closes at "done against simulator" until TODO-02 resolves
  ⚠️TDD-DERIVED   no SRS requirement — must not enter a sprint until TODO-01
  ⛔              blocked on the named TODO — do not build
```

---

## Source document inconsistencies noted while writing this phase

Raised for the register, not resolved here.

1. **The TODO register's epic references use a superseded numbering scheme.** TODO-05 and TODO-10 both
   say *"Story: EPIC-10"*, but EPIC-10 in `00-epic-feature-index.md` is *Host Management* (Phase 2).
   Both actually belong to **EPIC-16**. The same drift affects TODO-02 (*"Blocks: EPIC-12, EPIC-05,
   EPIC-07, EPIC-09"*), TODO-04, TODO-08, TODO-09, TODO-11, TODO-17 and TODO-18 — they reference the
   older numbering in `03-epics-and-backlog.md`, not the index that the backlog files treat as the
   contract. **The open-questions register needs re-pointing to the index numbering**, or a reader
   following a TODO lands in the wrong epic.

2. **`FR-REP-02 (SRS B1)` is transitively under-specified and nothing says so.** It requires an
   "end-of-day" report reconciled via SRS §3.6, i.e. `FR-CRD-03 (SRS B1)`, whose day cut-off and
   timezone TODO-11 flags as undefined. So the *"day"* in "end-of-day report" is undefined too, but
   TODO-11 is filed only against the reconciliation, not against FR-REP-02. F-18.2 cannot be closed
   without TODO-11 either.

3. **`vms.notification_logs` cannot hold the state a retrying dispatcher needs.** It has no
   `attempt_count`, `last_error`, `next_retry_at` or idempotency-key column — yet `vms.acs_requests`,
   solving the structurally identical outbound-retry problem, has all four. Either the notification
   platform needs its own dispatch table (the approach taken in T-16.1.2.1) or the schema needs
   extending. As written, the schema implies notifications are fire-and-forget while
   `FR-API-01 (SRS B1)`-style reliability is expected of them.

4. **The `vms.notify_channel` enum includes `in_app`, which no document explains.** No SRS
   requirement, no TDD §8 scenario and no feature in the index describes an in-app notification.
   `FR-NOT-01 (SRS B1)` names only email and WhatsApp. It appears to be an unexplained schema
   artifact, and it quietly widens the TODO-10 channel-selection question by a third option.

5. **`vms.access_events` has no location column.** No `building_id`, `floor_id` or `reception_id` —
   only a free-text `gate_ref`. `FR-REP-01 (SRS B1)` reports on building activity, and any per-floor or
   per-tenant breakdown must join `access_events → visitors → visitor_requests → tenants → floors`,
   which resolves against *present-day* master data rather than the state at the time of the visit. A
   tenant relocating floors silently rewrites history in every historic report. Handled at projection
   time in T-18.1.1.3, but it is a schema gap.

6. **The schema seeds a permission for a capability no requirement asks for.** `report.export` is
   seeded with the description *"Export reports to Excel/PDF"* — but TODO-16 correctly notes SRS §3.8
   requires neither format. The schema has quietly adopted the TDD's assumption as fact. Same pattern
   as `credential.override` versus TODO-04.

7. **The index's own pull-forward note describes a Phase 2 blocker that Phase 2 does not own.**
   `00-epic-feature-index.md` says F-16.1/F-16.2 are "needed by F-09.6 pass delivery" — meaning Phase 2
   cannot complete `FR-VMS-07 (SRS B1)` without work scheduled in Phase 4. The note exists but the work
   still sits in the Phase 4 milestone. Either the milestone plan moves these 33 points to Phase 2, or
   Phase 2's exit criteria carry a dependency on a later phase, which is not a stable plan.

8. **The estimation scale differs between documents.** `00-epic-feature-index.md` lists Fibonacci
   `1, 2, 3, 5, 8, 13` with the rule that 13 must be split before sprint entry. This phase applies the
   stricter rule of no 13-point stories at all. Worth reconciling so the two documents agree, since
   "13 exists but is always illegal at sprint entry" is a confusing scale.

9. **TODO-05 states "Email proceeds regardless" but the SRS phrasing is not equally clear.**
   `FR-NOT-01 (SRS B1)` says "via email and/or WhatsApp". The "and/or" is what permits email alone to
   satisfy the requirement — a reading this backlog relies on for F-16.3 to be safely blocked without
   blocking `FR-NOT-01 (SRS B1)` itself. Worth confirming explicitly with the client, since a stricter
   reading of "and" would make TODO-05 blocking rather than material.

