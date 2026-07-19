# Release Plan

**Versioning:** [Semantic Versioning](https://semver.org). Tags on `main` only.
**Cadence:** one release per phase, plus a hardening release for 1.0.0.

> **No calendar dates are committed.** Two blocking dependencies (TODO-01, TODO-02) sit with the
> client and the ACS vendor. Publishing dates before they resolve would be fiction. Sprint counts
> below are *sizing estimates from story points*, not commitments; dates attach to Phase 1 at
> kickoff and to Phase 3 once the ACS contract lands.

---

## Release map

| Release | Phase | Contents | Deployable | ACS needed |
|---|---|---|---|---|
| **0.1.0** | Phase 1 | Platform Foundation | Staging | No |
| **0.2.0** | Phase 2 | Visitor Registration & Pass Generation | Staging — **client demo** | Simulator |
| **0.3.0** | Phase 3A | ACS integration layer + entry, against the simulator | Staging | Simulator |
| **0.4.0** | Phase 3B | Live ACS integration | Staging → Production candidate | **Real contract** |
| **0.5.0** | Phase 4 | Reporting & Notification | Staging | Real contract |
| **1.0.0** | Hardening | NFR verification, security, DPIA, sign-off | **Production** | Real contract |

---

## 0.1.0 — Platform Foundation

**Milestone:** Phase 1 · **Epics:** EPIC-01 … EPIC-06

A deployable, secured, observable, empty application. No visitor workflow — this release exists so
everything after it can be built safely and traceably.

**Delivers:** FR-ADM-01, FR-ADM-02, FR-ADM-03, CON-03 (SRS B1) · supports NFR-SEC-01, NFR-MNT-01,
NFR-SCL-01, NFR-AVL-01

**Exit criteria**
- [ ] CI green end to end: build, unit, integration, coverage gate, SAST, dependency scan
- [ ] **Architecture fitness tests enforce layer boundaries and the ACS-boundary rule**
- [ ] Migrations run cleanly from empty to the baseline schema
- [ ] A user logs in, receives a session, and is denied an unauthorised endpoint
- [ ] Audit log records authentication and configuration changes
- [ ] Portal shell renders under client branding
- [ ] Deployed to staging from a merged commit
- [ ] Secrets held in a secrets manager — none in source, config, or logs

**Risk — EPIC-04 (Master Data) is `TDD-DERIVED` and blocked on TODO-01.** Phase 2 needs Tenant, Host
and Reception references. If TODO-01 has not resolved by Phase 1 exit, we build a **minimal
master-data slice** — only the entities Phase 2 requires — and record it as a documented deviation
with client sign-off. It is not taken unilaterally.

**Pulled forward:** F-11.1 (ACS port) and F-11.2 (ACS simulator) start here, not in Phase 3. Phase 2
credential generation cannot be built or tested without them. This is the single most important
sequencing decision in the plan — see [ADR-0002](../adr/0002-acs-anti-corruption-layer.md).

---

## 0.2.0 — Visitor Registration & Pass Generation ⭐

**Milestone:** Phase 2 · **Epics:** EPIC-07 … EPIC-10

**The first release with real client value, and the first credible demo.** The complete pre-arrival
journey plus pass generation, running against the ACS simulator.

**Delivers:** FR-VMS-01, FR-VMS-02, FR-VMS-03, FR-VMS-05, FR-VMS-06, FR-VMS-07, FR-VMS-11,
FR-VMS-13 (SRS B1)

**Exit criteria**
- [ ] Tenant submits a request → FM Admin approves/rejects → floor receptionist pre-registers, end to end
- [ ] Credential creation requested through the ACS port; reference stored and rendered as a QR pass
- [ ] Pass delivered by email, on-screen and print
- [ ] Validity windows and restriction types (time-bound / one-time) specified on creation
- [ ] Approval emits a domain event consumed by a downstream service
- [ ] Full journey demonstrated to the client
- [ ] Tenant data isolation verified *(or TODO-14 explicitly deferred with sign-off)*

**⚠️ Verification limit.** Everything credential-related passes **against the simulator only**. Per
ADR-0002 these stories are *done against simulator* — not verified. Nothing ACS-dependent can be
called production-ready until 0.4.0. This distinction must be stated plainly in the demo, or the
client will reasonably assume passes work at the gate.

**Pull-forward opportunity:** F-16.1 and F-16.2 (notification service + email channel) depend only
on VMS domain events. If Phase 3 is delayed by TODO-02, pull them here — it converts idle time into
shipped requirement coverage and gets FR-NOT-01's email path done early.

---

## 0.3.0 — ACS Integration Layer & Entry (Stage A)

**Milestone:** Phase 3, Stage A · **Epics:** EPIC-11 (except F-11.7), EPIC-12, EPIC-13, EPIC-14, EPIC-15

Everything in Phase 3 that does **not** require the real ACS contract. No user-visible change from
0.2.0 in some areas — but this is where the system's reliability is actually built.

**Delivers (against simulator):** FR-VMS-04, FR-VMS-08, FR-VMS-09, FR-VMS-12, FR-VMS-14, FR-VMS-15,
FR-CRD-01, FR-CRD-02, FR-CRD-03, FR-API-01, FR-API-02, FR-API-03 (SRS B1)

**Exit criteria**
- [ ] ACS port complete; **no ACS type exists outside the integration module** (fitness test proves it)
- [ ] ACS simulator supports configurable latency, failure injection and event emission
- [ ] Retry with exponential backoff, durable outbox, dead-letter handling, receptionist notification
- [ ] Every ACS request and response logged with correlation ids (FR-API-02)
- [ ] Inbound events processed idempotently, keyed on `acs_event_id`; redelivery does not double-update
- [ ] Arrival verification, check-in/out, walk-in registration and card reconciliation all work against the simulator
- [ ] Visitor-facing display operational *(pending TODO-17 on the physical model)*
- [ ] Chaos test: ACS unavailable mid-issuance → no data loss, request queued, operator informed

**Excluded and blocked:** F-11.7 (wire adapter, TODO-02) · F-13.4 (express entry, TODO-03 — may be
descoped) · F-14.4 (manual override, TODO-04 — **must not be built**, no SRS requirement).

---

## 0.4.0 — Live ACS Integration (Stage B) 🔴 GATED

**Milestone:** Phase 3, Stage B · **Feature:** F-11.7 + verification of all Stage A work

**Gate: TODO-02.** Cannot start without the published UAL contract and a non-production ACS endpoint.

**Exit criteria**
- [ ] Wire-level adapter implemented against the published contract
- [ ] All Phase 2 and Stage A credential workflows re-verified against the real ACS test endpoint
- [ ] NFR-PRF-01 measured *(needs a numeric target — TODO-07)*
- [ ] Failure modes verified against real ACS behaviour, not simulated behaviour
- [ ] Every ACS-dependent story promoted from *done against simulator* to **verified**

**This is the release that converts the project from plausible to proven.** If the adapter swap
turns out to be more than an adapter swap, the anti-corruption layer failed and that is worth
knowing loudly.

---

## 0.5.0 — Reporting & Notification

**Milestone:** Phase 4 · **Epics:** EPIC-16 … EPIC-19

**Delivers:** FR-NOT-01, FR-NOT-02, FR-REP-01, FR-REP-02 (SRS B1)

**Exit criteria**
- [ ] Notification service dispatches on domain events; delivery status logged
- [ ] Confirmation to visitor and host on credential issuance, including QR and welcome message
- [ ] Exit alerts to host and visitor on ACS exit events
- [ ] Daily, weekly and monthly visitor activity reports generated automatically
- [ ] End-of-day credentials-and-cards report reconciled against Phase 3 card data
- [ ] No message bodies or visitor PII in logs — `notification_logs.body_ref` holds a template ref only

**Largely TDD-derived.** Only 4 of this phase's requirements are SRS-backed. Host notifications
(FR-NOT-03), reminders (FR-NOT-04), system alerts (FR-NOT-05), the audit report (FR-REP-06),
analytics (FR-ANL-01) and Excel/PDF export (FR-EXP-01) all come from the TDD with no SRS backing.
They are planned and estimated, but **need TODO-01 disposition before implementation**.

**Blocked within the phase:** F-16.3 WhatsApp (TODO-05 — build behind a disabled flag) ·
F-16.5 channel selection (TODO-10) · F-19.2 export (TODO-16).

---

## 1.0.0 — Production Release

**Milestone:** Hardening

**Exit criteria**
- [ ] **Performance** — NFR-PRF-01 verified against an agreed threshold (TODO-07)
- [ ] **Load** — NFR-SCL-01: 500 visitors/day, 120 concurrent reception users
- [ ] **Availability** — NFR-AVL-01: VMS usable for registration and approval while ACS is down
- [ ] **Security** — OWASP ASVS review passed; penetration test findings remediated
- [ ] **Compliance** — data protection impact assessment signed off (NFR-CMP-01, TODO-06, TODO-12)
- [ ] **Traceability** — matrix 100% populated through PR and Release columns; reverse trace clean
- [ ] **Operations** — runbook, deployment guide, monitoring and alerting in place
- [ ] **Training** — reception and admin user material delivered (NFR-USA-01)
- [ ] **Contractual** — VMS warranty and maintenance terms defined separately from ACS hardware (SRS §8)
- [ ] **All 19 open questions dispositioned** — resolved, descoped, or accepted as a known limitation

---

## Critical path

```
TODO-01 (SRS v2) ─────────► EPIC-04 master data ─────► 0.1.0 ──► 0.2.0 ⭐ client demo
                                                          │
                            F-11.1 port + F-11.2 sim ─────┤ (pulled forward into Phase 1)
                                                          │
                                                          ▼
                                                        0.3.0 Stage A
                                                          │
TODO-02 (ACS contract) ───────────────────────────────────┤
                                                          ▼
                                                        0.4.0 Stage B ──► 0.5.0 ──► 1.0.0
TODO-19 (gate authority) ─► deployment sign-off ──────────────────────────────────────┘
TODO-03 (express entry) ──► F-13.4 scope decision ──► 0.3.0
```

**Both critical-path blockers are external.** TODO-01 sits with the client and document control;
TODO-02 sits with UAL. Everything buildable without them is front-loaded into 0.1.0 and 0.2.0 —
which is why the phase ordering puts a client-demonstrable release before the ACS gate rather than
after it.

---

## Release process

1. Cut `release/<version>` from `develop` once the phase's exit criteria are met.
2. Version bump; changelog generated from Conventional Commit history.
3. Deploy to staging; run the full regression, performance and security suites.
4. Product Owner acceptance against the phase exit criteria.
5. Traceability audit — every requirement in the release traced to a merged PR and a passing test.
6. Merge to `main` with `--no-ff`; tag `v<version>`.
7. Merge back to `develop`.
8. Deploy to production (1.0.0 onward) behind a manual approval gate.

**No release ships with an incomplete traceability matrix.** That is the audit artefact the whole
governance model exists to produce.

## Rollback

Every feature ships behind a flag and is independently deployable.

| Scenario | Response |
|---|---|
| Defect in one feature | Disable the flag — no redeploy |
| Broad regression | Redeploy the previous tag |
| Bad migration | Apply the documented reverse migration; forward-only migrations must state their reversal plan |
| ACS integration failure | ACS is external — VMS degrades to queued issuance per FR-API-01 rather than failing |

That last row is the point of NFR-REL-01: an ACS outage must never take VMS down with it.
