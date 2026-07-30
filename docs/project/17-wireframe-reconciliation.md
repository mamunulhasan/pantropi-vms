# Wireframe deck ↔ requirements ↔ built

**Source:** *VMS Wireframes Deck* (Claude Design project `303275d9-d5da-4793-921e-0700d6c884f0`),
14 slides, 12 screens, low-fidelity, dated Jul 2026.

The deck is a **design proposal**, not a requirement. Where it agrees with the SRS/TDD it is a
welcome specification of *how* a requirement should look. Where it goes beyond them it is a change
request, and where it contradicts a shipped decision it is a conflict someone has to resolve. This
file is the reconciliation, so that neither document silently overrides the other.

Its own closing slide says the layouts are **not yet chosen** ("Confirm the preferred layout for
each screen — pick from the lettered options, e.g. 4b for the central desk"), so every lettered
variant below is still open.

---

## 1. Screen-by-screen status

| # | Deck screen | API | UI | Status |
|---|---|---|---|---|
| 01 | Sign-in | ✅ | ✅ | **Built.** Deck adds three elements we do not have — see §3.1 |
| 02 | Role landing pages (9a–9d) | ⚠️ partial | ⚠️ admin only | Stat tiles need counts no endpoint provides — §3.2 |
| 03 | Tenant portal (1a mobile / 1b desktop) | ✅ | ✅ VJ-1 | **Built** as 1b. Deck's credential picker has no API field — §3.3 |
| 04 | FM Admin approvals (2a/2b/2c) | ✅ | ➡️ **VJ-2** | Backend complete since US-07.3.x/07.4.x; built to 2a + 2c |
| 05 | Floor reception — pre-registration (3a) | ✅ | ⬜ VJ-3 | Backend complete (US-08.1.1/08.1.3); deck adds ID capture + sync column — §3.4 |
| 06 | Central reception — verify & issue (4a/4b/4c) | ❌ | ⬜ | **No backend.** Needs EPIC-09 + the ACS integration — §2.1 |
| 07 | Visitor display (5a/5b) | ❌ | ⬜ | **No backend.** Depends on credential issuance — §2.1 |
| 08 | Reporting (6a/6b) | ❌ | ⬜ | **No backend**, and `report.view` is granted to nobody — §3.5 |
| 09 | Admin — master data (7a tabs, 7b role matrix) | ✅ | ✅ UI-4a/4b | Built as separate pages, not tabs — §3.6. Branding tab not built — §3.7 |
| 10 | Master data — entry (10a/10b/10c) | ⚠️ | ⚠️ | Built except **tenant hosts**, which have no API at all — §2.2 |
| 11 | System configuration (11a/11b/11c) | ⚠️ | ⚠️ | Settings screen exists; ACS/branding/notification config does not — §3.7 |

Legend: ✅ exists · ⚠️ partial · ❌ absent · ➡️ in progress · ⬜ not started

---

## 2. Blocking gaps — the deck needs API that does not exist

### 2.1 Credential issuance, entry lifecycle and the ACS integration

Screens 06 and 07, and the "On-site"/"Checked out"/"Cards returned" states threaded through 02, 03
and 08, all depend on machinery that is **entirely unbuilt**:

- no check-in / check-out endpoints — `vms.visitors.checked_in_at`, `checked_out_at` and the
  statuses `checked_in`/`inside`/`checked_out`/`expired`/`no_show` exist in the schema and in
  `VisitorStatus`, and **nothing in the API can ever set them**;
- no credential issuance — `IssuedCredentials.anyLiveFor` answers against an empty table today;
- no ACS client of any kind. The deck specifies base URL, OAuth2, retries, backoff, DLQ, queue
  depth and a "test connection" action, none of which exists.

The deck's own next-steps slide acknowledges this: *"Fold in the ACS API contract once finalised by
Universal Automations."* Until that contract lands, screens 06, 07 and 08 cannot be built against
anything real, and building them against invented shapes would violate project rule 2.

### 2.2 Hosts

Slide 10b gives the tenant editor a **Hosts** sub-table with "+ Add host". `vms.hosts` exists in
the schema, and `TenantDirectory.hostBelongsToTenant` validates against it — but **no controller
exposes it**: no list, no create, no read, anywhere in `vms-interfaces`.

Consequence today: a tenant submitting a request cannot name a host, because there is no way to
discover a valid `hostId`. The tenant form therefore submits `hostId: null` and says so on screen
(VJ-1). The deck assumes hosts exist and are selectable — 1b shows "Host J. Tan (18-04)" on every
request, and 2a/2c show the host on every approval row.

**This is the single highest-value gap.** A small `HostController` (list/create/deactivate under
`masterdata.*`, scoped to the tenant) unblocks the host field on three separate screens.

---

## 3. Conflicts and deltas

### 3.1 Sign-in (screen 01)

| Deck | Built | Disposition |
|---|---|---|
| Building photograph panel, "Dynamically set by application settings" | Brand mark only | **New requirement.** Needs a settings key + an asset path. Not in the SRS |
| "Keep me signed in" checkbox | Absent | **New requirement**, and a security decision: it means a longer-lived refresh token. Refresh TTL is currently 24h local |
| "Forgot password?" link | Absent | **New requirement.** No self-service reset exists — recovery today is an admin issuing an activation token (US-02.2.2). A self-service flow needs an email channel, which is itself unbuilt |
| "No role picker — the account's role routes the user" | ✅ matches | `homeFor(permissions)` does exactly this (VJ-1) |
| "Reception PCs inherit their floor from the machine" | ✗ conflicts | We derive floor from the **user's** reception assignment. Machine identity is a different model with different security properties — see §3.4 |

### 3.2 Role landing pages (screen 02)

Each landing page carries stat tiles — Pending / Today / On-site, Approved today, Expected / Sent /
Issued / Cards. **No endpoint returns any of these counts.** Two are derivable today from
`totalElements` on an existing list call (Pending, and a date-filtered Today); the rest depend on
the unbuilt entry lifecycle (§2.1).

The deck also merges floor and central reception into **one** reception home that switches scope
("My floor" / "Central desk"). Our authorization model separates them by permission —
`visitor.register` for the floor desk, `credential.issue` for central — so one screen serving both
is a UI decision, not an authorization one, and is compatible.

### 3.3 Tenant portal (screen 03)

| Deck | Built | Disposition |
|---|---|---|
| Desktop form + "My requests" (1b) | ✅ VJ-1 | Matches |
| Mobile 3-step stepped form (1a) | ✗ | Open layout choice; responsive single-page form ships today |
| **Credential picker (QR / RFID) on the request form** | ✗ | **Conflicts with the API.** `SubmitRequest` has no credential field; the credential comes from the pass type, which is master data. Either the API gains the field, or the deck drops the control |
| Status lifecycle incl. "Checked out" | ⚠️ | Approved/Rejected/Cancelled ship; "Checked out" needs §2.1 |
| "Tenant notified on approve & on exit" | ✗ | Notification channel unbuilt; `notification.email.enabled` is a setting with no sender behind it |

### 3.4 Floor reception (screen 05)

| Deck | Built | Disposition |
|---|---|---|
| Pre-register form | ✅ API | UI is VJ-3 |
| **"ID / NRIC (masked)"** capture | ✗ | **Conflicts with TODO-13.** The schema has `id_document_ref` with a comment warning against storing document images; the API deliberately exposes no ID field, and the read path omits visitor email/phone entirely. Capturing national ID numbers is a data-protection decision that needs client sign-off, not a UI addition |
| "Sync to central" column with sending/synced states | ✗ | Our pre-registration is a single synchronous transaction against one database — there is no floor→central replication to be in-flight. The column presumes an architecture we do not have (and the TDD does not describe) |
| Floor auto-set from the machine (`PC-1804`) | ✗ | We derive it from the user's reception assignment. Machine-bound identity would let anyone at that PC act as that floor; the current model ties it to the authenticated user. **Recommend keeping the user-based model** and treating the PC label as cosmetic |

### 3.5 Reporting (screen 08) and the role matrix (7b)

The deck's permission matrix conflicts with the shipped, migrated one in three places:

| Deck says | Shipped (V9) | Note |
|---|---|---|
| Submit request: Tenant ✓ **Floor ✓ Central ✓** | `visitor.request` → TENANT only | Floor/central *pre-register* (`visitor.register`); that is a different permission with a different audit action |
| **Reconcile cards: Central ✓ FM ✓** | no such permission exists | Would need a new permission code and the unbuilt reconciliation feature |
| **View reports: all five roles** | `report.view` granted to **nobody** | Deliberate: `Permissions.UNBACKED`, held open by TODO-16 because no role description mentions reporting. The API *refuses* to grant it |
| "+ Add role" | no create-role endpoint | Deliberate — TODO-01 leaves FR-USR-02 undefined |

Also worth noting: this local development database currently holds grants that **no migration
creates** (TENANT has `masterdata.view`; FM_ADMIN and MASTER_ADMIN have `report.view`). V9 is
additive — `INSERT … ON CONFLICT DO NOTHING`, never `DELETE` — so it cannot converge a database
that has extra rows. That is tracked separately from this reconciliation.

### 3.6 Master data layout (screen 09)

The deck shows **one screen with tabs**: Floors & Recep. | Tenants | Visitor types | Pass types |
Holidays | Branding | Users & roles. We ship **separate nav destinations** per entity.

Both satisfy the requirement; the deck's version is a genuine usability improvement (fewer clicks
between related tables) and is a contained refactor — the screens already exist and would move
under a tabbed shell. It also drops **Buildings** as a top-level concept, folding building into a
dropdown on the floor form. We support multiple buildings as first-class records, which the SRS's
single-site scope (Westgate Tower) does not require but does not forbid.

**Recommendation:** adopt the tabs, keep Buildings as a tab rather than deleting the concept.

### 3.7 System configuration (screen 11)

The settings screen ships against a **closed catalogue of five keys**. The deck specifies a much
larger surface: ACS integration (base URL, auth method, client id/secret, retries, timeout, DLQ,
queue depth, last-call timestamp, test-connection), an email gateway, WhatsApp status, portal
branding (logo upload, theme colours, display name) and a notification template matrix.

Two points where the deck and the shipped system **agree** and should stay that way:

- *"secret held in a secrets manager, never in the database"* — the settings API already refuses
  credential-shaped values with exactly that reasoning, and audits the attempt.
- *"WhatsApp stays off until Business API approved"* — the settings API refuses enabling it, citing
  TODO-05.

Branding is currently environment configuration (`NEXT_PUBLIC_BRAND_NAME`, `NEXT_PUBLIC_BRAND_LOGO`),
not database-backed; the deck's admin-editable branding is a new requirement.

---

## 4. Disposition

**Adopt now (no new API):**
1. FM Admin approvals — deck 2a + 2c — as **VJ-2**. Backend complete.
2. Reception pre-registration — deck 3a, minus ID capture and the sync column — as **VJ-3**.
3. ~~Tabbed master-data shell — deck 7a~~ — **done in UI-6**.

**Needs a small backend story first:**
4. Hosts (§2.2) — unblocks the host field on the tenant form, the approval queue and the tenant editor.
5. Landing-page counts (§3.2) — or derive the two that existing list endpoints already return.

**Deferred — not to be built (owner decision, 31 Jul 2026):**

Every item where the deck contradicts a shipped decision stays as it is. The deck does not
override the SRS/TDD, and none of these will be implemented on the strength of a wireframe:

| Deck element | Stays as | Because |
|---|---|---|
| ID/NRIC capture (§3.4) | not captured | TODO-13 keeps document identifiers off the API by design |
| `report.view` for five roles (§3.5) | granted to nobody | `Permissions.UNBACKED`, TODO-16 — the API refuses the grant |
| "+ Add role" (§3.5) | no endpoint | TODO-01 leaves FR-USR-02 undefined |
| "Reconcile cards" permission (§3.5) | does not exist | no reconciliation feature to guard |
| Submit request for floor/central (§3.5) | `visitor.register` | pre-registering is a different action with its own audit trail |
| "Keep me signed in" (§3.1) | absent | changes refresh-token lifetime — a security decision |
| "Forgot password?" (§3.1) | absent | needs a self-service reset and an email channel, neither built |
| Building photograph on sign-in (§3.1) | brand mark only | new requirement, not in the SRS |
| Credential picker on the tenant form (§3.3) | absent | no such field on the submit API |
| Floor from machine identity (§3.4) | from the user's assignment | machine-bound identity is weaker and unrequested |
| Floor→central "sync" column (§3.4) | absent | presumes a replication architecture we do not have |

If any of these is genuinely wanted, it re-enters through the backlog as a story with a
requirement behind it — which is the same rule every other feature followed.

**Blocked on the ACS contract:** screens 06, 07, 08 and every on-site/checked-out state (§2.1).
Not a decision to make yet — there is nothing to build against until Universal Automations
finalises the contract, as the deck's own closing slide says.

**Still open, and cheap when wanted:**
- ~~Hosts (§2.2)~~ — **done**: US-10.1.1 ships the directory (`/api/v1/hosts`). The remaining
  work is on the portal side: the tenant submit form still says a host cannot be named, and the
  picker is not wired yet.
- ~~The tabbed master-data shell (§3.6)~~ — **done in UI-6.** Route group, so the URLs did not
  move; Buildings kept as a tab rather than dropped; the sidebar collapsed from ten entries to
  three.
- Which lettered layout wins for each screen — the deck itself leaves this open.
