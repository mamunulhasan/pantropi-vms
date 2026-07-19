# VMS Requirements Catalogue — Baseline B1

**Project:** Project Pinnacle — Visitor Management System (VMS)
**Client:** CPG Corporation Pte Ltd — Westgate Tower
**Source of truth:** `docs/VMS_Only_SRS_Project_Pinnacle.docx` (SRS), `docs/VMS Technical Design Document.docx` (TDD), `docs/vms_schema_postgresql.sql` (Schema)
**Baseline:** B1 — 2026-07-19
**Status:** Provisional. See [08-source-document-discrepancies.md](08-source-document-discrepancies.md) and [07-open-questions.md](07-open-questions.md).

---

## How to read this catalogue

Every row is a requirement lifted **verbatim in intent** from a source document. Nothing here is invented.

Each requirement carries a **Provenance** marker:

| Marker | Meaning | May we build it? |
|---|---|---|
| `SRS` | Stated as a numbered FR in the attached SRS | Yes |
| `SRS-NFR` | Stated in SRS §5 (non-functional) | Yes |
| `SRS-CON` | Stated in SRS §2.4 (design constraint) | Yes |
| `TDD-ONLY` | Appears in the TDD but has **no** backing SRS FR | **No — blocked on TODO-01** |
| `SCHEMA-ONLY` | Implied by a schema table but has **no** backing SRS FR | **No — blocked on TODO-01** |

> **Rule:** No `TDD-ONLY` or `SCHEMA-ONLY` item may enter a sprint until it is traced to an
> approved SRS requirement. They are catalogued here for visibility, not for implementation.

---

## 1. Functional requirements (SRS-backed) — 28 total

### 1.1 Visitor pre-registration & approval — SRS §3.1

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-VMS-01 | A tenant shall be able to log into the VMS web portal to submit a visitor entry request. | R1 | `SRS` |
| FR-VMS-02 | An FM Admin shall be able to review and approve or reject a visitor request via the dashboard. | R1 | `SRS` |
| FR-VMS-03 | A floor receptionist shall be able to enter visitor details in VMS and submit them to the central server as a pre-registration record, ahead of the visitor's arrival. | R2 | `SRS` |

### 1.2 Arrival verification & credential request — SRS §3.2

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-VMS-04 | On visitor arrival, the central receptionist shall be able to check the visitor's pre-registered information in VMS to confirm whether they are appointed. | R2 | `SRS` |
| FR-VMS-05 | If appointed, VMS shall call the ACS API to request creation of a time-bound credential (QR code or RFID card) for the visitor. | R1, R2 | `SRS` |
| FR-VMS-06 | VMS shall receive and store the credential reference (e.g. QR payload or card ID) returned by ACS, for display and sharing with the visitor. | API | `SRS` |
| FR-VMS-07 | The credential shall be shareable with the visitor via email, on-screen image (reception display), or printed copy. | R2 | `SRS` |

### 1.3 Walk-in visitor flow — SRS §3.3

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-VMS-08 | An unscheduled (walk-in) visitor shall report to reception, where approval is confirmed from the host or FM Admin before VMS requests a credential. | R1 | `SRS` |
| FR-VMS-09 | Reception shall be able to request a temporary QR or RFID credential for a walk-in visitor through the same ACS API used for pre-scheduled visitors. | R1, R2 | `SRS` |
| FR-VMS-10 | Pre-scheduled visitors shall be able to scan directly at the flap barrier and bypass reception (express entry). | R1 | `SRS` ⚠️ **Gated by TODO-03** |

### 1.4 Credential validity & lifecycle — SRS §3.4

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-VMS-11 | VMS shall specify a validity window (date and time slot) when requesting credential creation from ACS. | R1, R2 | `SRS` |
| FR-VMS-12 | VMS shall call the ACS API to deactivate a credential at the end of its assigned validity window, unless ACS performs this automatically and reports status back to VMS. | R1, R2 | `SRS` ⚠️ **Ambiguous — TODO-09** |
| FR-VMS-13 | VMS shall support requesting either time-bound or one-time-use restriction types when creating a credential, per what the ACS API allows. | R1 | `SRS` |
| FR-VMS-14 | VMS shall be able to query the ACS API for a credential's current status (active, expired, revoked) on demand. | API | `SRS` |

### 1.5 Visitor-facing display — SRS §3.5

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-VMS-15 | A visitor-facing ("slave") display shall be provided at the reception desk, showing the visitor's QR code and current status as returned by VMS. | R2 | `SRS` ⚠️ **Under-specified — TODO-17** |

### 1.6 Card / credential accountability — SRS §3.6

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-CRD-01 | VMS shall log each RFID card issued to a visitor, including the card identifier returned by ACS at issuance. | R2 | `SRS` |
| FR-CRD-02 | VMS shall log each RFID card's return, using return status reported by ACS (e.g. from a card-issuer swipe event). | R2 | `SRS` |
| FR-CRD-03 | VMS shall reconcile issued-vs-returned card counts daily, using data retrieved from the ACS API, and flag discrepancies indicating a missing card. | R2 | `SRS` ⚠️ **Under-specified — TODO-11** |

### 1.7 Notifications & alerts — SRS §3.7

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-NOT-01 | The host and visitor shall receive a confirmation notification via email and/or WhatsApp, including the QR code and a welcome message, once VMS receives the credential from ACS. | R1 | `SRS` ⚠️ **Ambiguous — TODO-10** |
| FR-NOT-02 | VMS shall receive exit events from the ACS API and trigger automated exit alerts to the host and the visitor. | R1 | `SRS` |

### 1.8 Reporting & analytics — SRS §3.8

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-REP-01 | VMS shall automatically generate daily, weekly, and monthly visitor activity reports, combining its own pre-registration/approval data with access and exit events retrieved from the ACS API. | R1, R2 | `SRS` |
| FR-REP-02 | The central receptionist shall be able to pull an end-of-day report showing total credentials/cards issued and returned, reconciled via SRS §3.6. | R2 | `SRS` |

### 1.9 API reliability & error handling — SRS §3.9

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-API-01 | If the ACS API is unreachable when a credential request is made, VMS shall queue the request, notify the receptionist of the failure, and retry automatically. | Design | `SRS` |
| FR-API-02 | VMS shall log all API requests to and responses from ACS for audit and troubleshooting purposes. | Design | `SRS` |
| FR-API-03 | VMS shall authenticate to the ACS API using a credential mechanism (e.g. API key or OAuth2) rather than an open endpoint. | Design | `SRS` |

> ⚠️ **ID collision.** The TDD cites `FR-API-02`, `FR-API-03`, `FR-API-04` with meanings that do
> not match the SRS definitions above. See discrepancy **D-04**. This catalogue uses the **SRS**
> definitions as authoritative.

### 1.10 Administration & user roles — SRS §3.10

| ID | Requirement | Src | Provenance |
|---|---|---|---|
| FR-ADM-01 | There shall be one Master Admin at central reception with authority to approve visitor access and initiate credential requests. | R1 | `SRS` |
| FR-ADM-02 | VMS shall support 120 floor-reception user logins plus 1 central admin login, each authenticating from their respective floor's reception PC. | R2 | `SRS` ⚠️ **Ambiguous — TODO-08** |
| FR-ADM-03 | The VMS portal shall present a branded interface (logo, colors, theme) consistent with the client's identity. | R1 | `SRS` |

---

## 2. Non-functional requirements — SRS §5

| ID | Category | Requirement | Provenance |
|---|---|---|---|
| NFR-PRF-01 | Performance | A credential request to ACS shall return a confirmation (success or failure) within a time acceptable for a receptionist to remain waiting at the desk; **exact threshold to be agreed with the ACS vendor**. | `SRS-NFR` ⚠️ **No target — TODO-07** |
| NFR-REL-01 | Reliability | VMS shall handle ACS API unavailability gracefully per FR-API-01, without losing a visitor's pre-registration or approval data. | `SRS-NFR` |
| NFR-AVL-01 | Availability | The VMS application and database shall be available during all building operating hours, independent of ACS's own uptime. | `SRS-NFR` ⚠️ Operating hours undefined |
| NFR-SEC-01 | Security | All VMS-to-ACS API calls shall be authenticated (FR-API-03); visitor personal data shall be access-controlled by user role within VMS. | `SRS-NFR` |
| NFR-SCL-01 | Scalability | VMS shall support approximately 300–500 visitors per day and 120 concurrent reception users. | `SRS-NFR` |
| NFR-USA-01 | Usability | Reception-side workflows shall require minimal training for floor receptionists and the central receptionist. | `SRS-NFR` |
| NFR-MNT-01 | Maintainability | Changes to the ACS API contract shall be isolated to the integration layer, without requiring changes to core VMS workflow logic. | `SRS-NFR` |
| NFR-CMP-01 | Compliance | Data residency and applicable data protection requirements for visitor personal data shall be confirmed before any cloud-hosted component is finalized. | `SRS-NFR` ⚠️ **TODO-06, TODO-12** |

## 3. Design constraints — SRS §2.4

| ID | Constraint | Provenance |
|---|---|---|
| CON-01 | VMS shall not assume direct control of, or embed logic specific to, ACS hardware (barrier brand/model, reader frequency, power design). | `SRS-CON` |
| CON-02 | All credential lifecycle actions (create, activate, deactivate, query) shall go through the ACS API rather than a shared database or direct hardware call. | `SRS-CON` |
| CON-03 | The VMS portal shall present a branded interface consistent with the client's identity. *(Duplicates FR-ADM-03.)* | `SRS-CON` |

## 4. Explicit out-of-scope — SRS §1.2

These shall **never** appear in a VMS user story. Any issue proposing them is closed as out-of-scope.

- Flap barrier gate hardware, lane configuration, throughput.
- RFID/QR reader hardware, mounting, dual-frequency credential verification logic.
- Power infrastructure (PSU, UPS) for access control hardware.
- **Physical grant/deny decisions at the gate** — ACS enforces these using credential data VMS provisions via API.

> ⚠️ TDD §9.1 proposes an edge gateway that *"performs real-time verification at the gates."*
> This contradicts the SRS out-of-scope list above. See discrepancy **D-08**.

---

## 5. Requirements referenced by TDD/Schema with NO SRS backing

Catalogued for visibility. **Not implementable** until TODO-01 resolves.

| Referenced ID range | Referenced in | Subject |
|---|---|---|
| `FR-CFG-01` … `FR-CFG-08` | TDD §4.1, Schema §3 | Master data: Building, Floor, Tenant, Reception, Visitor Type, Pass Type, Holiday Calendar |
| `FR-VMS-16` | TDD §4.3 | Unknown — SRS FR-VMS series ends at 15 |
| `FR-ENT-01` … `FR-ENT-10` | TDD §4.4, §11; Schema §5, §8 | Entry lifecycle, express entry, ACS sync, visitor status tracking |
| `FR-USR-01` … `FR-USR-04` | TDD §4.6, §7; Schema §4 | User management, RBAC role/permission model |
| `FR-AUTH-01`, `FR-AUTH-02` | TDD §7; Schema §4 | JWT session handling, authorization |
| `FR-AUD-01`, `FR-AUD-02` | TDD §4.6, §7; Schema §11 | Append-only audit log |
| `FR-SET-01` | Schema §11 | System settings |
| `FR-REP-03` … `FR-REP-07` | TDD §4.5, §7 | Extended reporting suite, audit report |
| `FR-ANL-01` | TDD §4.5 | Analytics dashboard |
| `FR-EXP-01` | TDD §3, §4.5 | Excel / PDF export |
| `FR-NOT-03`, `FR-NOT-04`, `FR-NOT-05` | TDD §8 | Host notification, reminder, system alert |
| `FR-NTF-01`, `FR-NTF-02` | TDD §4.6 | Unknown — distinct from FR-NOT series |
| `FR-API-04`, `FR-API-05` | TDD §4.6, §6.3 | Extended integration requirements |

**Count:** ~50 additional requirement IDs referenced but not defined in the attached SRS. Combined
with the 28 defined here, this is consistent with the TDD's claim of a 96-FR SRS v2.

---

## Coverage summary

| Metric | Value |
|---|---|
| SRS functional requirements catalogued | **28 / 28 (100%)** |
| SRS non-functional requirements catalogued | **8 / 8 (100%)** |
| SRS design constraints catalogued | **3 / 3 (100%)** |
| SRS FRs mapped to an Epic | **28 / 28 (100%)** — see [02-traceability-matrix.md](02-traceability-matrix.md) |
| SRS FRs blocked by an open question | **9** |
| Undefined FR IDs referenced by TDD/Schema | **~50** |
