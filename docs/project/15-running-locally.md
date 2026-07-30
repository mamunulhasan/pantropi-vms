# Running VMS Locally

**Story:** US-01.3.1 (adapted) · **No Docker required**

One command gives you a complete, seeded VMS with a real PostgreSQL and a user for every role.

---

## Start it

**Once**, create the database:

```sql
CREATE DATABASE vms;
```

Then:

```bash
cd apps/vms-api
./gradlew bootRun
```

`bootRun` activates the `local` profile automatically. Flyway applies every migration on first
start. Takes ~15s.

| | |
|---|---|
| API | http://localhost:8081 |
| Health | http://localhost:8081/actuator/health |
| Database | `jdbc:postgresql://localhost:5432/vms` — user `postgres`, password `postgres` |
| Reset | `DROP DATABASE vms; CREATE DATABASE vms;` then restart |

Port **8081**, because 8080 is commonly already in use. Your own PostgreSQL holds the data, so it
persists across restarts and you can inspect it with pgAdmin or psql:

```bash
psql -h localhost -U postgres -d vms -c "SELECT * FROM vms.visitor_requests"
```

## Log in as any role

Every account uses the same development password:

```
Password123!local
```

| Username | Role | Permissions |
|---|---|---|
| `sysadmin` | SYSTEM_ADMIN | `user.manage`, `masterdata.view`, `masterdata.edit`, `settings.manage`, `audit.view` |
| `masteradmin` | MASTER_ADMIN | `visitor.approve`, `credential.issue` |
| `fmadmin` | FM_ADMIN | `visitor.approve` |
| `receptionist` | FLOOR_RECEPTIONIST | `visitor.register`, `masterdata.view` |
| `tenantuser` | TENANT | `visitor.request` |

These come from `V9__role_permission_grants.sql` and nowhere else — the seeder no longer grants
anything. If a role here surprises you, the migration is the place to argue with.

Seeded master data: building **WGT** (Westgate Tower) → floor **L01** → central reception **RC01**,
tenant **ACME** (Acme Corporation) with host *Alice Host*. `tenantuser` belongs to ACME.

## Try it

```bash
# 1. log in
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"tenantuser","password":"Password123!local"}'

# 2. use the accessToken from the response
TOKEN=<paste>

# 3. who am I?
curl -s http://localhost:8081/api/v1/auth/me -H "Authorization: Bearer $TOKEN"

# 4. submit a visitor request (FR-VMS-01)
curl -s -X POST http://localhost:8081/api/v1/visitor-requests \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"scheduledFrom":"2026-09-01T09:00:00Z","scheduledTo":"2026-09-01T11:00:00Z",
       "purpose":"Quarterly review",
       "visitors":[{"fullName":"Ada Lovelace","email":"ada@example.test"}]}'

# 5. approve it as an FM Admin (FR-VMS-02) — log in as fmadmin first for FM_TOKEN
curl -s -X POST http://localhost:8081/api/v1/visitor-requests/<id>/approve \
  -H "Authorization: Bearer $FM_TOKEN" -H 'Content-Type: application/json' \
  -d '{"note":"Cleared with building security"}'
```

`GET /api/v1/visitor-requests/{id}` is the review read, and it is the one route in the system
reachable by **either** of two permissions. A tenant opens it with `visitor.request` and sees
only their own; an FM Admin opens it with `visitor.approve` and sees any request in the
building. Same handler — the difference is the scoping policy at the query, not a branch in the
controller. It returns each visitor's name, company and visitor type, and still no email, phone
or document reference.

> **Opening a request is itself audited.** This is the read that names people, so it writes a
> `visitor_request.view` entry recording who looked and at what. The entry carries no state and
> no personal data — it says access happened, and the request row still says the rest.

Every decision on a request is recorded in `vms.audit_logs` and readable at
`GET /api/v1/visitor-requests/{id}/history`, chronologically, under **`audit.view`** — which
only `SYSTEM_ADMIN` holds. Not the FM Admins who take the decisions: reading back who decided
what is oversight, not part of deciding.

> **The audit table is append-only in the database.** `V12` installs a trigger that raises on
> `UPDATE` or `DELETE`, so tampering fails loudly even for a superuser — which is what the
> local datasource connects as. A `REVOKE` backs it up for ordinary roles. `INSERT` is
> untouched, and `TRUNCATE` is left to the table owner so a future retention policy has
> somewhere to stand.

```bash
curl -s http://localhost:8081/api/v1/visitor-requests/<id>/history \
  -H "Authorization: Bearer $SYSADMIN_TOKEN"
```

A tenant can amend its own request while it is still `submitted` — `PATCH` with only the fields
it wants changed; anything absent is left alone. Once a decision has been taken the request is
closed to amendment (**409**), because altering what somebody decided on would make their
decision a record of something that no longer exists.

Cancelling is legal from `submitted` **and** from `approved` — a plan changes after approval
more often than before it, and the alternative is a visitor nobody expects arriving at the gate.
Cancelling an approved request emits `CredentialRevocationRequested` to the outbox for EPIC-09.
If an FM Admin approves at the same moment, exactly one of the two succeeds and the other gets
**409**; the request is never both.

```bash
curl -s -X POST http://localhost:8081/api/v1/visitor-requests/<id>/cancel \
  -H "Authorization: Bearer $TOKEN"
```

Each visitor on a request may carry a name, email, phone, company and a `visitorTypeId` drawn
from `vms.visitor_types`. Email and phone are normalised on the way in — trimmed, lower-cased,
punctuation stripped from the number — so the same person entered twice is one person. An
unknown or deactivated visitor type is **400**, and a malformed email or an oversized name is a
400 naming the field without ever echoing what was typed.

> **Case-insensitive columns need the extension in `public`.** `citext` types compare
> case-insensitively only if the `=` operator is resolvable from the connection's `search_path`.
> Created inside `vms` it is not, and PostgreSQL silently falls back to case-sensitive `text`
> equality — usernames, emails and lockout keys all stop matching across capitalisation, while
> the unique indexes keep behaving case-insensitively because they record their operator class
> by OID. `V11` moves the extension to `public`. If you restore a database from elsewhere, check
> `SELECT 'A'::citext = 'a';` returns true before trusting a login.

A tenant sees its own requests at `GET /api/v1/visitor-requests`, filterable by `status` and by
visit-date range (`from`/`to`, ISO-8601). The filters narrow within the tenant's own set and
cannot widen it — the scope predicate is applied first, in the repository, never in the
controller. `GET /api/v1/visitor-requests/{id}` adds the purpose, the guests by name, and — for a
rejected request — the reason and when it was decided, with the approver identified by display
name only.

The list is a **conditional GET**. It returns a weak `ETag`; send it back as `If-None-Match`
and an unchanged list answers **304** with no body, which is what makes polling for status
changes cheap. The validator folds in the caller's scope as well as the filter, so two tenants
polling the same URL never share one — and the response is `Cache-Control: private` with
`Vary: Authorization`, so no shared cache can serve one tenant's list to another.

```bash
curl -si "http://localhost:8081/api/v1/visitor-requests" \
  -H "Authorization: Bearer $TOKEN" -H 'If-None-Match: W/"<etag>"'
```

> Two deviations worth knowing. There is **no Redis cache**, so an unchanged list still costs
> one cheap count query rather than none. And a host renamed in master data does not touch any
> request row, so a poll can show a stale host name until that request next changes.

Another tenant's request is **404**, byte-identical to an id that never existed, so the endpoint
cannot be used to find out what else is in the building. A tenant user with no tenant assigned
sees nothing at all rather than everything.

```bash
curl -s "http://localhost:8081/api/v1/visitor-requests?status=rejected" \
  -H "Authorization: Bearer $TOKEN"
```

The queue an approver works from is `GET /api/v1/visitor-requests/pending`.
It filters by `tenantId`, by a visit-date range (`from`/`to`), by `status`, and by a
`search` substring matched case-insensitively against **visitor and host names**. Filters combine
conjunctively and are applied after the scope predicate, so they narrow what an approver may
already see and never widen it.

`status` defaults to `submitted` — loading the queue never shows a decided request. Asking for
`?status=approved` is a different question, and the filter answers it. An unrecognised status,
an inverted date range, or a search longer than 100 characters is **400**; the message names the
valid values but never repeats what you sent, so a filter value cannot reach an access log.

A visitor's name is searchable and is still never returned. The queue lists counts, not people.
 It lists only
`submitted` requests, newest first, with tenant, host, window and **visitor count** — never a
visitor's email or phone, which are not selected at all. Page size is capped at **100**; asking for
more is clamped rather than refused, and the response reports the `size` actually applied alongside
`maxSize`.

Which requests an approver sees is decided by `vms.scoping.posture`:

| Value | Effect |
|---|---|
| `building-wide` *(default)* | MASTER_ADMIN, FM_ADMIN and SYSTEM_ADMIN see every tenant |
| `own-scope` | every principal is confined to their own tenant or reception, approvers included — one with neither assigned sees nothing |

The active posture is logged at startup, so what a deployment is actually enforcing is visible in its
logs. This is provisional pending TODO-14; see [ADR-0005](../adr/0005-tenant-and-floor-scoping-seam.md).

Rejecting instead takes a **required** reason — `POST …/{id}/reject` with `{"reason":"Host is on
leave"}`. An empty, whitespace-only or absent reason is **400**, enforced server-side so a modified
client cannot omit it. The reason is stored exactly as typed, markup included; encoding it belongs at
render time, and mangling it here would corrupt legitimate text like `declined, 5 < 10 people`.

Approving twice gives **409**; approving a visit whose window has already passed gives **422**, since
that would mint a credential that is expired the moment it exists. Two FM Admins approving at the
same moment produce exactly one success and one 409 — the decision is a compare-and-set on the
status, so the second write matches no row and its outbox event rolls back with it.

### Roles now hold what the matrix says (US-03.1.1)

`V9__role_permission_grants.sql` is the authoritative role-to-permission matrix. The seeder no longer
hands out development-only grants — two sources of truth that agree today drift tomorrow, and a
developer exercising a role locally should be exercising what the deployed system actually gives it.

| Role | Permissions |
|---|---|
| `SYSTEM_ADMIN` | `user.manage`, `masterdata.view`, `masterdata.edit`, `settings.manage`, `audit.view` |
| `MASTER_ADMIN` | `visitor.approve`, `credential.issue` |
| `FM_ADMIN` | `visitor.approve` |
| `FLOOR_RECEPTIONIST` | `visitor.register`, `masterdata.view` |
| `TENANT` | `visitor.request` |

`credential.override`, `report.view` and `report.export` are granted to **nobody**, pending TODO-04
and TODO-16.

One consequence worth knowing locally: `fmadmin` can no longer read master data. The role that can
view but not edit is now `receptionist` — which is what the matrix says, and what the integration
tests assert against.

Grants can be adjusted at runtime (US-03.3.1), and the change applies to a **live session on its next
request** — no re-login:

```bash
# read the role, note its version
curl -s http://localhost:8081/api/v1/admin/roles/FM_ADMIN -H "Authorization: Bearer $SYSADMIN_TOKEN"

# give it masterdata.view — the whole set is submitted, not a delta
curl -s -X PUT http://localhost:8081/api/v1/admin/roles/FM_ADMIN/grants \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"version":"<from the read>","permissions":["visitor.approve","masterdata.view"]}'
```

Sending a stale `version` gets **409** with the current grants in the body, so you can see what the
other administrator changed. Revoking the last `user.manage` gets **409** too — that one would lock
everyone out of undoing it. And `credential.override` is refused with a message naming TODO-04,
because the migration withholding it would be decorative if the API handed it out.

### Seeing authorization work

```bash
# sysadmin can administer users; tenantuser cannot
curl -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/v1/admin/users -H "Authorization: Bearer $SYSADMIN_TOKEN"   # 200
curl -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/v1/admin/users -H "Authorization: Bearer $TENANT_TOKEN"     # 403
curl -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/v1/admin/users                                              # 401
```

Conversely `sysadmin` gets **403** on `POST /api/v1/visitor-requests` — it holds `user.manage` but
not `visitor.request`. Roles genuinely differ.

### Seeing lockout and password policy work (US-02.3.1)

```bash
# five wrong passwords lock the account — then the RIGHT password fails too, identically
for i in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8081/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"tenantuser","password":"nope"}' -o /dev/null -w '%{http_code} '
done; echo
curl -s -X POST http://localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"tenantuser","password":"Password123!local"}'      # still 401 — locked
```

The body is byte-identical whether the account is locked, the password is wrong, or the username
does not exist. That is the point: none of the three can be told apart.

An administrator clears it — and can never set a password, only issue an activation token:

```bash
USER_ID=$(psql -h localhost -U postgres -d vms -tAc "SELECT id FROM vms.users WHERE username='tenantuser'")
curl -s -X POST http://localhost:8081/api/v1/admin/users/$USER_ID/unlock \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -o /dev/null -w '%{http_code}\n'   # 204
```

Changing your own password requires the current one, and signs out every other device:

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/password -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"Password123!local","newPassword":"a much longer passphrase"}'
```

A weak choice comes back **422** with guidance that never repeats what you typed:

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/password -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"Password123!local","newPassword":"password1234"}'
```

Lockout is tunable — `vms.security.lockout.threshold` (default 5) and
`vms.security.lockout.window-minutes` (default 15).

### Seeing system settings work (US-04.8.1)

```bash
curl -s http://localhost:8081/api/v1/admin/settings -H "Authorization: Bearer $SYSADMIN_TOKEN"
```

Reading needs only `masterdata.view`; **changing** needs `settings.manage`, which is deliberately a
different permission — altering `acs.retry.max_attempts` changes how the whole installation behaves,
which is a different kind of act from renaming a floor.

```bash
# change one
curl -s -X PUT http://localhost:8081/api/v1/admin/settings/acs.retry.max_attempts \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"8"}'

# a key that is not in the catalogue is 404, not silently created
curl -s -X PUT http://localhost:8081/api/v1/admin/settings/notification.sms.enabled \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"true"}'

# enabling WhatsApp is refused 409 while TODO-05 is open — and the attempt is audited
curl -s -X PUT http://localhost:8081/api/v1/admin/settings/notification.whatsapp.enabled \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"true"}'
```

Every change and every refusal leaves a row with both states:

```bash
psql -h localhost -U postgres -d vms -c "SELECT action, entity_id, before_state, after_state FROM vms.audit_logs WHERE action LIKE 'settings.%' ORDER BY created_at DESC"
```

These endpoints sit behind `vms.masterdata.enabled`, which the `local` profile sets. With the flag
off the routes are unmapped rather than present-and-refusing.

### Seeing the master data pattern work (US-04.1.1)

```bash
# create a building — the code is upper-cased on the way in
curl -s -X POST http://localhost:8081/api/v1/admin/buildings \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"wgt","name":"Westgate Tower","address":"Westgate, Dhaka"}'

# list: paginated, filterable, searchable by code OR name
curl -s "http://localhost:8081/api/v1/admin/buildings?search=west&active=true&size=10" \
  -H "Authorization: Bearer $SYSADMIN_TOKEN"
```

There is **no DELETE route** — retirement is deactivation, because `vms.floors.building_id` is
`ON DELETE RESTRICT` and a visitor record from last year must still resolve the building it names:

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/buildings/$ID/deactivate \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -o /dev/null -w '%{http_code}\n'   # 204
curl -s -X DELETE http://localhost:8081/api/v1/admin/buildings/$ID \
  -H "Authorization: Bearer $SYSADMIN_TOKEN" -o /dev/null -w '%{http_code}\n'   # 405 — no such verb
```

Reading needs `masterdata.view`, every mutation needs `masterdata.edit`. **`receptionist`** holds the
first and not the second, so it can list buildings and gets **403** on a create. (`fmadmin` holds
neither — under the real matrix it approves visitor requests and nothing else, so it gets 403 on
both.)

### A deployment note about logging and personal data (US-04.3.1 AC-6)

Tenant contact details are personal data. Nothing this application logs contains them — asserted by
`TenantAdminIT.contactDetailsNeverReachTheLog`, which captures every line the `com.pantropi.vms`
loggers emit at **all** levels during create, read, update, list and a rejected write.

**That guarantee stops at the application boundary.** Tomcat's `Http11InputBuffer` logs the raw HTTP
request — headers and body — at `FINER`. So enabling FINEST-level container logging in a real
deployment puts tenant contact details, bearer tokens, and every other request body into the log
file, regardless of what the application does. Container log levels are a deployment decision and
should stay at `INFO` outside of a debugging session.

Settings are cached in memory and the cache is dropped on every write, so a change is visible on the
next read with no restart. **The cache is per-process**: if you ever run two instances against one
database, a change made on one is not seen by the other until it reloads. Cross-instance
invalidation is US-04.9.2 — see the Redis note in
[14-api-authorization.md](14-api-authorization.md#deviations) for why that story is still open.

## Endpoints available today

| Method | Path | Requires |
|---|---|---|
| POST | `/api/v1/auth/login` | — public |
| POST | `/api/v1/auth/refresh` | — public |
| POST | `/api/v1/auth/activate` | — public (activation token) |
| GET | `/api/v1/auth/me` | authenticated |
| POST | `/api/v1/auth/logout` | authenticated |
| POST | `/api/v1/auth/password` | authenticated (+ current password) |
| GET/POST | `/api/v1/admin/users` | `user.manage` |
| PUT | `/api/v1/admin/users/{id}` | `user.manage` |
| POST | `/api/v1/admin/users/{id}/deactivate`, `/reactivate` | `user.manage` |
| POST | `/api/v1/admin/users/{id}/unlock`, `/reset-password` | `user.manage` |
| GET | `/api/v1/admin/roles`, `/roles/{code}` | `user.manage` |
| PUT | `/api/v1/admin/roles/{code}/grants` | `user.manage` |
| GET | `/api/v1/admin/settings`, `/settings/{key}` | `masterdata.view` |
| PUT | `/api/v1/admin/settings/{key}` | `settings.manage` |
| GET | `/api/v1/admin/buildings`, `/buildings/{id}` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/buildings`, `/buildings/{id}` | `masterdata.edit` |
| POST | `/api/v1/admin/buildings/{id}/deactivate`, `/reactivate` | `masterdata.edit` |
| GET | `/api/v1/admin/buildings/{bid}/floors`, `/floors/{id}` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/buildings/{bid}/floors`, `/floors/{id}` | `masterdata.edit` |
| POST | `…/floors/{id}/deactivate`, `/reactivate` | `masterdata.edit` |
| GET | `/api/v1/admin/visitor-types`, `/visitor-types/{id}` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/visitor-types`, `/visitor-types/{id}` | `masterdata.edit` |
| POST | `…/visitor-types/{id}/deactivate`, `/reactivate` | `masterdata.edit` |
| GET | `/api/v1/admin/pass-types`, `/pass-types/{id}` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/pass-types`, `/pass-types/{id}` | `masterdata.edit` |
| POST | `…/pass-types/{id}/deactivate`, `/reactivate` | `masterdata.edit` |
| GET | `/api/v1/admin/tenants`, `/tenants/{id}`, `/tenants/{id}/dependents` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/tenants`, `/tenants/{id}` | `masterdata.edit` |
| POST | `…/tenants/{id}/deactivate`, `/reactivate` | `masterdata.edit` |
| GET | `/api/v1/admin/receptions`, `/receptions/{id}`, `/receptions/{id}/dependents` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/receptions`, `/receptions/{id}` | `masterdata.edit` |
| POST | `…/receptions/{id}/designate-central?confirm=`, `/deactivate`, `/reactivate` | `masterdata.edit` |
| GET | `/api/v1/admin/holidays?year=` or `?from=&to=` | `masterdata.view` |
| POST/PUT | `/api/v1/admin/holidays`, `/holidays/{id}` | `masterdata.edit` |
| POST | `/api/v1/admin/holidays/import` | `masterdata.edit` |
| DELETE | `/api/v1/admin/holidays/{id}` — *the only delete in master data* | `masterdata.edit` |
| POST | `/api/v1/admin/users/import`, `/import/preview` | `user.manage` |
| POST | `/api/v1/visitor-requests` | `visitor.request` |
| POST | `/api/v1/visitor-requests/{id}/approve` — optional `{"note":"…"}` | `visitor.approve` |
| POST | `/api/v1/visitor-requests/{id}/reject` — **required** `{"reason":"…"}`, ≤1000 chars | `visitor.approve` |
| GET | `/api/v1/visitor-requests/pending?status=&tenantId=&from=&to=&search=&page=&size=` — **max size 100**, clamped not refused | `visitor.approve` |
| GET | `/api/v1/visitor-requests?status=&from=&to=&page=&size=` — own tenant only | `visitor.request` |
| GET | `/api/v1/visitor-requests/{id}` — tenant sees its own; approver sees any. 404 otherwise | `visitor.request` **or** `visitor.approve` |
| PATCH | `/api/v1/visitor-requests/{id}` — partial edit, `submitted` only | `visitor.request` |
| POST | `/api/v1/visitor-requests/{id}/cancel` — legal from `submitted` **and** `approved` | `visitor.request` |
| GET | `/api/v1/visitor-requests/{id}/history` — chronological decision trail | `audit.view` |

Anything else is denied by default (US-03.2.1).

## Notes on safety

- **Local profile only.** The datasource settings and the seeder are both scoped to the `local`
  profile — the seeder is `@Profile("local")` — so neither can reach a staging or production
  artifact. There is no default profile: `local` is opt-in, exactly like staging and prod.
- **The dev password is worthless elsewhere.** It is fixed and published here so you can log in.
  Staging and production take `VMS_SECURITY_JWT_SECRET` from the environment and provision their
  first account through the bootstrap command (US-02.4.1).
- **The seeded role→permission grants are a development convenience**, inferred from the role
  descriptions so each role can do something observable. They are deliberately not a migration: the
  production grant matrix is authorization policy still awaiting client sign-off (D-15). Migrations
  V3/V5/V7 grant only what an SRS requirement names.

## Deviation from US-01.3.1 as written

The story specified `docker compose` with PostgreSQL, Redis and Kafka. Docker is unavailable in this
environment, so the local profile connects to a PostgreSQL installed directly on the machine
(verified against 18.4). That is arguably better for development: the data is inspectable with the
developer's own tools and survives independently of the application.

Redis and Kafka are not required — nothing in the system uses them yet: sessions live in PostgreSQL
(US-02.1.2) and domain events in the outbox table (US-07.1.1). Integration tests continue to use
embedded PostgreSQL so they stay self-contained and need no installed server. When Docker becomes
available, compose supersedes this without touching application code.
