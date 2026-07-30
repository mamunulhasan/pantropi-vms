# Open Questions & Clarification TODO List

**Raised:** 2026-07-19 · **Owner:** Lead Architect / Technical PM

Project rule 3: *"If requirements are ambiguous, create a TODO list instead of guessing."*
This is that list. Every item below is a place where the source documents do not tell us enough to
implement without inventing something.

**Blocking:** work cannot start on the linked epic until resolved.
**Non-blocking:** work can start; the item must resolve before the linked story is *closed*.

---

## Blocking

### TODO-01 ✅ DISPOSITIONED (2026-07-28) — attached SRS is the baseline
**Refs:** D-01, D-03, D-05 · **Blocks:** EPIC-04 (all), EPIC-10, EPIC-19, F-05.1, F-03.1/F-03.3, F-07.5, F-08.4, F-09.7, F-17.3/4/5, F-18.4 — and full backlog completeness

The TDD is written against a 96-requirement, four-phase SRS v2 that we do not have. ~50 referenced
requirement IDs are undefined. Master data, RBAC, audit, user administration, and most of the
reporting suite are designed and schema-supported but unrequirmented.

**Dispositioned by the repository owner on 2026-07-28** ([ADR-0004](../adr/0004-master-data-and-rbac-as-approved-enablers.md)):
the attached SRS is the delivery baseline; SRS v2 is not awaited. Master data (EPIC-04) and RBAC
administration (EPIC-03) are **approved enablers**, bounded by the entities the published schema
already defines. Analytics/export (EPIC-19) and the TDD-only notification scenarios remain blocked —
they add user-facing capability rather than enabling an existing requirement.

**Until resolved:** the backlog covers 28 requirements. It is not the whole system.

---

### TODO-02 🔴 Detailed ACS API contract from UAL
**Refs:** SRS App. B item 4, TDD §11 dep. 1 · **Blocks:** F-11.7 (wire adapter) outright; verification-only for EPIC-09, EPIC-12, EPIC-13, EPIC-14, EPIC-15, F-17.2, F-18.1

Needed: endpoints, authentication method, payload schemas, error codes, event delivery mechanism
(push/webhook vs poll), idempotency semantics, rate limits, sandbox endpoint.

**We need:** the published contract, plus a non-production ACS test endpoint (TDD §9.3).

**Until resolved:** the ACS Integration Client can be scaffolded against an interface we define, and
tested against a simulator we build — but no credential operation can be verified end-to-end. Every
ACS-dependent story stays at "done against simulator", not "done".

---

### TODO-03 🔴 Is Express Entry achievable? (FR-VMS-10)
**Refs:** SRS App. B item 1, TDD §11 dep. 2 · **Blocks:** F-13.4 (EPIC-13) — descope candidate

FR-VMS-10 requires ACS to recognise VMS-issued credentials with no reception step. The SRS itself
notes the vendor's current submission describes a *fully reception-mediated* flow.

**We need:** explicit vendor confirmation, or a decision to descope FR-VMS-10.

---

### TODO-19 🔴 Who makes the grant/deny decision at the gate in hybrid deployment?
**Refs:** D-08 · **Blocks:** deployment architecture sign-off, EPIC-01 (F-01.6)

SRS §1.2 places gate decisions out of VMS scope. TDD §9.1 has the VMS edge gateway performing
real-time verification at the gates. These conflict, and the resolution changes VMS's liability and
certification posture.

**We need:** a written statement of decision authority. Our recommendation is that ACS retains it
unconditionally and the edge component is display/status only.

---

## Non-blocking — must resolve before the linked story closes

### TODO-04 🟠 Is manual credential override in scope?
**Refs:** D-06 · **Feature:** F-14.4 (EPIC-14) — **do not build**
Designed (TDD §6.1) and schema-supported (`acs_op.manual_override`), but absent from the attached
SRS. Security-sensitive. **We need:** an SRS requirement, or explicit descope.

**Enforced in the grant matrix since US-03.1.1 (2026-07-29).** `V9__role_permission_grants.sql`
grants `credential.override` to **no role**, and `RoleGrantMatrixIT` asserts zero grants of it.
Worth knowing: `MASTER_ADMIN`'s own seeded description says *"approves access, issues credentials,
overrides"* — this is the one place the matrix contradicts a role description on purpose, because
nobody has written the rules for when an override is legitimate. `report.view` and `report.export`
are ungranted on the same basis, pending TODO-16. **When this question closes, the grant is one line
in a new migration** — the constant already exists in `Permissions.UNBACKED`.

### TODO-05 🟠 WhatsApp Business API approval status
**Refs:** SRS App. B item 2, TDD §11 dep. 4 · **Feature:** F-16.3 (EPIC-16)
Schema already defaults `notification.whatsapp.enabled` to `false`. **We need:** approval timeline,
or a decision to descope WhatsApp for release 1.0. Email proceeds regardless.

**Enforced in code since US-04.8.1 (2026-07-28).** `SystemSettings` refuses any request setting
`notification.whatsapp.enabled` to true, returning 409 with this TODO named in the message, and
audits the attempt as `settings.change_refused`. Disabling is unaffected. **When this question
closes, delete that guard** — it is the only thing standing between the setting and an operator who
wants it on.

### TODO-06 🟠 Deployment target + hosting region + data residency
**Refs:** SRS App. B item 3, NFR-CMP-01, TDD §11 dep. 5 · **Feature:** F-01.6 (EPIC-01)
Hybrid cloud-and-edge vs fully on-premise. **We need:** target model, hosting region, and the
applicable data-protection regime for visitor personal data.

### TODO-07 🟠 NFR-PRF-01 has no numeric target
The SRS says credential-request confirmation must return *"within a time acceptable for a
receptionist to remain waiting"* — **exact threshold to be agreed with the ACS vendor**. An
unmeasurable NFR cannot be tested. **We need:** a number. Our proposal, pending agreement:
**p95 ≤ 3 s, p99 ≤ 5 s** end-to-end at the reception UI.

### TODO-08 🟠 What does "120 floor-reception logins" mean? (FR-ADM-02)
**Feature:** F-02.2 (EPIC-02)
Three readings: (a) 120 named user accounts, (b) 120 shared per-floor accounts, (c) 120 concurrent
sessions as a licence ceiling. NFR-SCL-01's *"120 concurrent reception users"* suggests (c), but
"each authenticating from their respective floor's reception PC" suggests (b). These have different
audit consequences — a shared account cannot attribute an action to a person, which weakens
FR-API-02 and the audit trail. **We need:** the intended model. Our recommendation: named accounts.

### TODO-09 🟠 Who expires a credential — VMS or ACS? (FR-VMS-12)
**Feature:** F-14.1 (EPIC-14)
The requirement says VMS shall deactivate at end of validity *"unless ACS performs this
automatically and reports status back."* Both branches are stated as acceptable; the choice
determines whether we build a scheduler and how we reconcile. **We need:** the ACS behaviour
(depends on TODO-02). Design must tolerate both until known.

### TODO-10 🟠 "email and/or WhatsApp" — who chooses? (FR-NOT-01)
**Feature:** F-16.5 (EPIC-16)
Per-recipient preference, per-tenant policy, or global system setting? Different data models.
**We need:** the selection rule.

### TODO-11 🟠 Daily card reconciliation specifics (FR-CRD-03)
**Feature:** F-15.3 (EPIC-15)
Undefined: run time and timezone, the cut-off defining a "day", who receives the discrepancy flag
and through which channel, and what happens to an unreturned card at day boundary.

### TODO-12 🟠 Visitor personal-data retention period
**Refs:** NFR-CMP-01 · **Features:** F-05.4 (EPIC-05), F-01.7 (EPIC-01)
No retention or purge requirement exists in any document, yet the system stores names, emails,
phones, and an `id_document_ref`. **We need:** retention period and purge/anonymisation rule.

### TODO-13 🟠 Is visitor ID document capture in scope?
The schema has `visitors.id_document_ref` with the comment *"avoid storing raw ID images here."*
No SRS requirement mentions ID capture. **We need:** confirm in or out of scope. If in, it needs a
requirement and a privacy assessment.

### TODO-14 🟠 Tenant data isolation rules — *interim strict default in force*
No document states whether Tenant A may see Tenant B's visitor data, or whether a floor receptionist
sees only their floor. NFR-SEC-01 requires role-based access control but not tenant scoping.
**We need:** the isolation model. This is a design-time decision — retrofitting it is expensive.

**Interim (ADR-0004):** the scoping seam is built with a **strict default** — a tenant sees only its
own data, a floor receptionist only their own floor. Fails safe: relaxing is configuration, whereas
a permissive default would have leaked tenant data while we waited. The policy is swappable; this
question stays open.

**Built in US-03.4.1 (2026-07-29), recorded in [ADR-0005](../adr/0005-tenant-and-floor-scoping-seam.md).**
Answering this question is now a change to `ScopePolicy` and nothing else. Two things are worth
knowing before answering it:

- **`ScopeFilter` is sealed.** An answer that needs a new shape — a tenant group, a delegation —
  makes every place that handles a filter a compile error until updated. That is intended: a new
  isolation shape must not be quietly ignorable by an adapter written before it existed.
- **The bypass is guarded, not just the policy.** `ScopingRulesTest` fails the build naming any
  adapter that reads scope-sensitive data without consulting the policy. So whatever is decided here
  cannot be silently sidestepped by the next query someone writes.

### TODO-15 🟠 VMS user authentication mechanism
**Feature:** F-02.1 (EPIC-02)
The schema stores `password_hash`, implying local accounts. TDD §7 specifies JWT. Neither states
whether client SSO / Active Directory federation is required. **We need:** confirm local accounts
are acceptable for 121 users.

### TODO-16 🟡 Report export formats
TDD §3 and §4.5 require Excel and PDF export (`FR-EXP-01`); the attached SRS §3.8 requires neither.
**We need:** confirm export is in scope for release 1.0.

### TODO-17 🟡 Visitor-facing display — delivery mechanism (FR-VMS-15)
**Feature:** F-12.4 (EPIC-12)
Undefined: dedicated hardware or second monitor; browser-based or native; how it is paired to a
reception workstation; what it shows when idle; how it is secured against a visitor interacting with
it. **We need:** the physical and interaction model.

### TODO-18 🟡 Walk-in approval channel (FR-VMS-08)
**Feature:** F-13.2 (EPIC-13)
"Approval is confirmed from the host or FM Admin" — is this an in-app approval action, a phone call
the receptionist records, or either? Determines whether we build an approval flow or a checkbox.

---

### TODO-20 🟠 Which tenant is a pre-registration for, on a floor with several? — *interim rule in force*
**Feature:** F-08.1 (EPIC-08), US-08.1.1 AC-2

US-08.1.1 AC-2 describes the tenant as derived server-side through
`users.reception_id → receptions.floor_id → tenant association`, and says it is **"never from the
submitted payload"**. That chain only identifies one tenant if a floor hosts exactly one.

**It does not.** `vms.tenants.floor_id` is a nullable foreign key with a *non-unique* index
(`idx_tenants_floor`), so a floor hosts as many tenants as it hosts — normally several in a
commercial tower. Westgate Tower with up to 120 floor reception users (NFR-SCL-01) is not a
one-tenant-per-floor building.

So AC-2 as written cannot be implemented for the common case. Three ways out:

| Option | Consequence |
|---|---|
| Pick the first tenant on the floor | **Rejected.** Silently files a visit against the wrong company. Nothing downstream would notice, and it surfaces at an incident review. |
| Refuse when the floor has several | Faithful to the AC's wording and fails closed, but makes the feature unusable wherever a floor is shared — which is most of them. |
| **Let the receptionist name a tenant, validated against their own floor** | In force. Preserves what AC-2 protects and keeps the feature usable. |

**Interim rule shipped in US-08.1.1:**

- Exactly one active tenant on the floor → **derived**, exactly as AC-2 describes.
- Several → the caller must name one, and it is checked against the floor they are stationed on. A
  tenant off that floor is **403** and audited (AC-6).
- None → refused; there is nothing to file the visit against.

This deviates from AC-2's literal wording while preserving the property that wording protects: the
tenant is server-authoritative, and a receptionist cannot reach past their own floor. A named tenant
is a *selection from* what the station allows, never an assertion the server accepts. The audit entry
records `tenantDerived: true|false`, so a reader can always tell which happened.

**What we need from the client:** on a shared floor, should the receptionist choose the tenant, or
should something else disambiguate — a reception point dedicated per tenant, a default tenant per
reception, or the host determining it? If the answer is "the receptionist must not choose", the
change is confined to one method (`PreRegisterVisitor.resolveTenant`).

---

## Summary

| Severity | Count | Effect |
|---|---|---|
| 🔴 Blocking | 4 | TODO-01, TODO-02, TODO-03, TODO-19 |
| 🟠 Material | 13 | Must resolve before affected stories close |
| 🟡 Minor | 3 | Clarify during sprint planning |
| **Total** | **20** | |

**Bottom line for the client:** TODO-01 and TODO-02 gate the majority of the system. Requirements
analysis and project scaffolding can proceed now; sustained implementation cannot start until at
least those two are resolved.
