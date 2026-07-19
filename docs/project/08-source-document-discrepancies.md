# Source Document Discrepancies

**Raised:** 2026-07-19 · **Owner:** Lead Architect · **Audience:** CPG Corporation, Pantropi delivery team, UAL

The three source documents do not agree with one another. This register records every conflict
found during requirements analysis. Each must be dispositioned by the client/authoring team before
the affected scope can be built.

**Severity key:** 🔴 Blocking · 🟠 Material · 🟡 Minor

---

## D-01 🔴 The attached SRS is not the SRS the TDD was written against

**Evidence.** TDD document control states:

> Basis: VMS SRS v2 (96 functional requirements across four phases); phased implementation plan Revision 2

The attached SRS (`VMS_Only_SRS_Project_Pinnacle.docx`) contains **28** functional requirements and
describes **no phasing**. Its own status line reads *"Draft — VMS-only scope"*, with no version
number.

**Impact.** The TDD and the database schema are downstream of a requirements document we do not
have. Roughly **50 requirement IDs** referenced across the TDD and schema have no definition
available to us (see catalogue §5). We cannot claim requirement→code traceability for the majority
of the designed system.

**Disposition needed.** Either (a) supply SRS v2 and the phased implementation plan Rev 2, or
(b) confirm in writing that the attached SRS supersedes v2 and that the TDD/schema are to be
reduced to its 28 requirements.

**Tracked as:** TODO-01.

---

## D-02 🔴 Functional requirement IDs have been renumbered between versions

The same ID denotes different requirements in different documents.

| ID | Meaning in attached SRS | Meaning implied by TDD |
|---|---|---|
| `FR-VMS-07` | Credential shareable via email / on-screen / print (§3.2) | First requirement of the Pass & Credential Service (§4.3) |
| `FR-VMS-10` | Express entry — bypass reception (§3.3) | Express entry is called `FR-ENT-03` (§11, dep. 2) |
| `FR-VMS-16` | *Does not exist* — series ends at 15 | Cited as the last Pass & Credential requirement (§4.3) |

**Impact.** Any traceability link written as a bare FR ID is ambiguous across documents. A commit
message reading `refs FR-VMS-10` could mean two different features.

**Mitigation in force.** This project treats the **attached SRS** as the definitive meaning of every
ID, and requires all traceability references to be qualified — `FR-VMS-10 (SRS B1)`.

---

## D-03 🟠 Whole requirement families exist only in the TDD and schema

`FR-CFG-*`, `FR-ENT-*`, `FR-USR-*`, `FR-AUTH-*`, `FR-AUD-*`, `FR-SET-*`, `FR-ANL-*`, `FR-EXP-*`,
`FR-NTF-*`, `FR-REP-03..07`, `FR-NOT-03..05`, `FR-API-04..05` are referenced but never defined.

**Impact.** These cover master data management, the entry lifecycle, user administration, RBAC,
audit logging, and most of the reporting suite — a large fraction of the delivered system. Building
them means inventing requirements, which project rule 2 forbids.

**Mitigation in force.** Epics EPIC-03 and EPIC-14 are created but held in `status/blocked` until
D-01 resolves.

---

## D-04 🟠 `FR-API-*` numbering conflicts between SRS and TDD

| ID | SRS §3.9 definition | TDD usage |
|---|---|---|
| `FR-API-01` | Queue, notify, retry on ACS unreachable | *(not cited)* |
| `FR-API-02` | Log all ACS API requests/responses | §6.3 — *secrets not in source or database* |
| `FR-API-03` | Authenticate to ACS API | §6.2 — *retry queue + notify receptionist* (= SRS FR-API-01) |
| `FR-API-04` | *Does not exist* | §6.3 — *log all requests and responses* (= SRS FR-API-02) |
| `FR-API-05` | *Does not exist* | §4.6 — cited as end of the integration range |

The TDD appears to use a numbering shifted by one, with an extra secrets-management requirement
inserted. **The SRS definitions are authoritative for this project.**

---

## D-05 🟠 Schema tables have no requirement backing in the attached SRS

The following tables in `vms_schema_postgresql.sql` implement functionality the attached SRS never
specifies:

`buildings`, `floors`, `tenants`, `receptions`, `visitor_types`, `pass_types`, `holiday_calendar`,
`roles`, `permissions`, `role_permissions`, `users`, `hosts`, `system_settings`, `audit_logs`

The schema's own inline comments attribute them to `FR-CFG-*`, `FR-USR-*`, `FR-AUD-*`, `FR-SET-*` —
all undefined per D-03. Master data and RBAC are *implied* by SRS §2.2 (user classes) and NFR-SEC-01
(role-based access control), but implication is not specification.

**Impact.** ~14 of 20 tables are unbacked. The schema is a strong hint about SRS v2's contents, and
should not be discarded — but it cannot substitute for requirements.

---

## D-06 🟠 SRS Appendix B has four items; the TDD cites a fifth

TDD §11, dependency 3:

> ACS support for Manual Override — administrative forced state change (**SRS Appendix B, Item 5**)

The attached SRS Appendix B contains **items 1–4 only**. There is no Item 5, and manual override
appears nowhere in the attached SRS — yet it is a named ACS operation in TDD §6.1 and an enum value
(`manual_override`) in the schema's `vms.acs_op` type.

**Impact.** Manual credential override is designed and schema-supported but unrequirmented. It is
also a security-sensitive operation. Held out of scope pending disposition.

**Tracked as:** TODO-04.

---

## D-07 🟡 Schema enum values imply unrequirmented behaviour

| Enum | Value | Issue |
|---|---|---|
| `visitor_status` | `no_show` | No SRS requirement defines no-show detection or its trigger |
| `notify_type` | `reminder` | Maps to TDD `FR-NOT-04`; absent from attached SRS |
| `notify_type` | `system_alert` | Maps to TDD `FR-NOT-05`; absent from attached SRS |
| `notify_channel` | `in_app` | SRS §3.7 names only email and WhatsApp |
| `credential_state` | `cancelled`, `failed` | No SRS requirement defines these transitions |

---

## D-08 🔴 TDD edge-gateway design contradicts the SRS scope boundary

**SRS §1.2, out of scope:**

> Physical grant/deny decisions at the gate — ACS enforces these using the credential data VMS provisions via API.

**TDD §9.1:**

> A local edge gateway at Westgate Tower holds a synced set of currently valid credentials and **performs real-time verification at the gates**, so physical access continues during a brief loss of connectivity.

**TDD §2** repeats this: the edge band *"can verify access at the gates even if connectivity to the cloud-hosted application is briefly lost."*

**Impact.** These cannot both be true. If the VMS edge gateway verifies credentials at the gate, VMS
is making physical access decisions — squarely inside the SRS out-of-scope list, and in violation of
CON-01 and CON-02. It would also make VMS safety-critical, materially changing its liability,
testing, and certification posture.

**Most likely reading:** the edge gateway is an *ACS-side* component, or it caches for *display and
status* only while ACS retains the grant/deny decision. But this must be stated, not assumed.

**Disposition needed.** Confirm the gate decision authority in the hybrid deployment model.
This is a scope-and-liability question, not a technical one.

**Tracked as:** TODO-19.

---

## D-09 🟡 Reference date anomalies

SRS §1.5 lists R3 as *"UAL Financial Proposal, Ref: UAL/SHL/VMS/**26112025**_Rev6"* dated
**19 February 2026** — the reference embeds a November 2025 date while the document is dated three
months later. Likely benign (revision of an older proposal), but flagged for document control.

---

## Disposition log

| ID | Severity | Raised | Owner | Status | Resolution |
|---|---|---|---|---|---|
| D-01 | 🔴 | 2026-07-19 | Client / Pantropi doc control | **Open** | — |
| D-02 | 🔴 | 2026-07-19 | Pantropi doc control | **Open** | — |
| D-03 | 🟠 | 2026-07-19 | Client / Pantropi doc control | **Open** | — |
| D-04 | 🟠 | 2026-07-19 | Pantropi doc control | **Open** | — |
| D-05 | 🟠 | 2026-07-19 | Pantropi doc control | **Open** | — |
| D-06 | 🟠 | 2026-07-19 | Client | **Open** | — |
| D-07 | 🟡 | 2026-07-19 | Pantropi doc control | **Open** | — |
| D-08 | 🔴 | 2026-07-19 | Client / UAL | **Open** | — |
| D-09 | 🟡 | 2026-07-19 | Pantropi doc control | **Open** | — |
