/**
 * The API, stubbed at the browser's network boundary (UI-5).
 *
 * One handler for `**\/api/**` dispatching on path — rather than a stack of `page.route` calls,
 * whose precedence rules are easy to get subtly wrong — so what answers a given request is
 * readable in one place, and every request can be recorded for assertions.
 *
 * Two properties make the stub honest rather than convenient:
 *
 * - **Refresh succeeds.** A full page load wipes the in-memory access token, and the real portal
 *   recovers through the HttpOnly refresh cookie. Answering `/api/session/refresh` is what lets
 *   a test `goto` an admin route directly and land authenticated, exactly as a real reload does.
 * - **Authorization is enforced, not assumed.** Admin data endpoints answer 403 when the mocked
 *   profile does not hold the permission the real controller requires. Without that, a tamper
 *   test would prove nothing: the client would be refused by a stub that refuses nobody.
 */
import { type Page, type Request } from "@playwright/test";

export type MockProfile = {
  userId: string;
  username: string;
  displayName: string;
  role: string;
  permissions: string[];
};

export const SYSADMIN: MockProfile = {
  userId: "11111111-1111-1111-1111-111111111111",
  username: "sysadmin",
  displayName: "Sydney Admin",
  role: "SYSTEM_ADMIN",
  permissions: [
    "audit.view",
    "masterdata.edit",
    "masterdata.view",
    "settings.manage",
    "user.manage",
  ],
};

/**
 * A tenant user: signed in, entitled to nothing in the admin console, and entitled to its own
 * tenant's visit requests. `visitor.request` is the only permission the TENANT role holds.
 */
export const TENANT_USER: MockProfile = {
  userId: "22222222-2222-2222-2222-222222222222",
  username: "tenantuser",
  displayName: "Tara Tenant",
  role: "TENANT",
  permissions: ["visitor.request"],
};

/** An approver: FM_ADMIN holds visitor.approve and nothing else. */
export const FM_USER: MockProfile = {
  userId: "44444444-4444-4444-4444-444444444444",
  username: "fmadmin",
  displayName: "Fiona Manager",
  role: "FM_ADMIN",
  permissions: ["visitor.approve"],
};

/** Signed in and granted nothing at all — no area to enter. */
export const NO_ACCESS_USER: MockProfile = {
  userId: "33333333-3333-3333-3333-333333333333",
  username: "nobody",
  displayName: "Nadia Nobody",
  role: "TENANT",
  permissions: [],
};

const SESSION = {
  accessToken: "e2e-access-token",
  expiresAt: "2099-01-01T00:00:00Z",
  mustChangePassword: false,
};

const BUILDING = {
  id: "b1111111-1111-1111-1111-111111111111",
  code: "WGT",
  name: "Westgate Tower",
  address: "12 Westgate Avenue",
  active: true,
};

const FLOOR = {
  id: "f1111111-1111-1111-1111-111111111111",
  buildingId: BUILDING.id,
  code: "L01",
  name: "Level 1",
  levelNo: 1,
  active: true,
};

const RECEPTION = {
  id: "r1111111-1111-1111-1111-111111111111",
  floorId: FLOOR.id,
  code: "CTR-A",
  name: "Central Reception",
  central: true,
  active: true,
};

const TENANT = {
  id: "t1111111-1111-1111-1111-111111111111",
  code: "ACME",
  name: "Acme Corporation",
  floorId: FLOOR.id,
  contactEmail: "facilities@acme.example",
  contactPhone: "+8801700000000",
  active: true,
};

/** One request, decided — so the detail screen renders its outcome panel. */
const VISIT_REQUEST = {
  id: "91111111-1111-1111-1111-111111111111",
  status: "submitted",
  host: null,
  purpose: "Quarterly audit",
  scheduledFrom: "2099-03-01T09:00:00Z",
  scheduledTo: "2099-03-01T17:00:00Z",
  submittedAt: "2099-02-01T10:00:00Z",
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
  visitors: [
    { fullName: "Ada Lovelace", company: "Analytical Ltd", visitorType: "Contractor", status: "pending" },
  ],
};

const VISIT_ROW = {
  id: VISIT_REQUEST.id,
  status: "submitted",
  host: null,
  scheduledFrom: VISIT_REQUEST.scheduledFrom,
  scheduledTo: VISIT_REQUEST.scheduledTo,
  visitorCount: 1,
  submittedAt: VISIT_REQUEST.submittedAt,
  decidedAt: null,
  decisionReason: null,
};

/** The queue row carries a count and no visitor names — exactly as the store selects it. */
const PENDING_ROW = {
  id: VISIT_REQUEST.id,
  tenant: "Acme Corporation",
  host: null,
  scheduledFrom: VISIT_REQUEST.scheduledFrom,
  scheduledTo: VISIT_REQUEST.scheduledTo,
  visitorCount: 1,
  submittedAt: VISIT_REQUEST.submittedAt,
};

export const VISIT_REQUEST_ID = VISIT_REQUEST.id;

function page1<T>(items: T[]) {
  return { items, page: 0, size: 20, total: items.length };
}

const SETTINGS = [
  {
    key: "default_pass_valid_hours",
    value: "12",
    type: "INTEGER",
    description: "Fallback validity window when a pass type is not specified",
    updatedBy: null,
    updatedAt: null,
    usingDefault: true,
    secret: false,
  },
  {
    key: "notification.email.enabled",
    value: "true",
    type: "BOOLEAN",
    description: "Master switch for email notifications",
    updatedBy: SYSADMIN.userId,
    updatedAt: "2026-07-01T09:00:00Z",
    usingDefault: false,
    secret: false,
  },
];

const ROLES_OVERVIEW = {
  roles: [
    {
      code: "SYSTEM_ADMIN",
      name: "System Administrator",
      description: "Configures the system",
      permissions: ["masterdata.edit", "masterdata.view", "settings.manage", "user.manage"],
      version: "AbCdEfGhIjKl",
      activeUsers: 1,
    },
    {
      code: "TENANT",
      name: "Tenant",
      description: "Submits visitor requests",
      permissions: ["visitor.request"],
      version: "MnOpQrStUvWx",
      activeUsers: 4,
    },
  ],
  catalogue: [
    { code: "credential.override", description: "Perform manual credential override" },
    { code: "masterdata.edit", description: "Create/update master data" },
    { code: "masterdata.view", description: "View master data" },
    { code: "report.view", description: "View reports and analytics" },
    { code: "settings.manage", description: "Manage system settings" },
    { code: "user.manage", description: "Manage users, roles, permissions" },
    { code: "visitor.request", description: "Submit visitor requests" },
  ],
};

/**
 * What the real controller requires, mirroring its annotations. Matched in order, first hit wins,
 * so the specific visitor-request routes must precede the general one.
 *
 * The detail read is the one that catches people out: it declares **either** `visitor.request` or
 * `visitor.approve`, because a tenant reads their own request and an approver reads any. A stub
 * that demanded only the former would 403 the approval drawer and the test would "prove" a bug
 * that does not exist.
 */
const REQUIRED: { match: RegExp; anyOf: string[] }[] = [
  { match: /^\/api\/v1\/admin\/(settings|buildings|tenants|receptions|visitor-types|pass-types|holidays)/, anyOf: ["masterdata.view"] },
  { match: /^\/api\/v1\/admin\/(users|roles)/, anyOf: ["user.manage"] },
  { match: /^\/api\/v1\/visitor-requests\/pending$/, anyOf: ["visitor.approve"] },
  { match: /^\/api\/v1\/visitor-requests\/[^/]+\/(approve|reject)$/, anyOf: ["visitor.approve"] },
  { match: /^\/api\/v1\/visitor-requests\/[^/]+\/history$/, anyOf: ["audit.view"] },
  // Either permission opens the detail read (VisitorRequestController's any-of annotation).
  { match: /^\/api\/v1\/visitor-requests\/[^/]+$/, anyOf: ["visitor.request", "visitor.approve"] },
  { match: /^\/api\/v1\/visitor-requests$/, anyOf: ["visitor.request"] },
  { match: /^\/api\/v1\/pre-registrations/, anyOf: ["visitor.register"] },
];

/**
 * The CORS headers a cross-origin API response must carry for the portal to use it.
 *
 * `Access-Control-Expose-Headers` is the load-bearing one: the portal runs on :3000 and the API on
 * :8081, and without it the browser hides `ETag` from page JavaScript — `response.headers.get`
 * simply answers null. A stub that omitted it would make the conditional-GET test fail for a
 * reason that has nothing to do with the code under test, and (worse) a stub that faked the header
 * as readable would hide the real requirement. The live API exposes it; so does this.
 */
export const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "http://localhost:3000",
  "Access-Control-Expose-Headers": "ETag, X-Correlation-Id",
};

export type ApiMock = {
  /** Every API path the browser asked for, in order — for asserting what a screen did NOT call. */
  requests: string[];
  /** Paths the stub refused with 403, mirroring the real controller's guard. */
  forbidden: string[];
};

export async function mockApi(page: Page, profile: MockProfile = SYSADMIN): Promise<ApiMock> {
  const record: ApiMock = { requests: [], forbidden: [] };
  // Logout clears the refresh cookie server-side, so refresh must stop working afterwards.
  // Without modelling that, a reload would silently resurrect the session and the AC-4
  // back-navigation test would be asserting nothing.
  let signedOut = false;

  await page.route("**/api/**", async (route, request: Request) => {
    const path = new URL(request.url()).pathname;
    record.requests.push(path);

    const json = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
    const empty = (status: number) =>
      route.fulfill({ status, headers: { ...CORS_HEADERS }, body: "" });

    // ---- the BFF session routes (portal origin) ----
    if (path === "/api/session/login") {
      signedOut = false;
      return json(SESSION);
    }
    if (path === "/api/session/refresh") {
      return signedOut ? json({ error: "session_expired" }, 401) : json(SESSION);
    }
    if (path === "/api/session/logout") {
      signedOut = true;
      return empty(204);
    }

    // ---- identity ----
    if (path === "/api/v1/auth/me") {
      return json(profile);
    }
    if (path === "/api/v1/auth/logout") {
      return empty(204);
    }

    // ---- deny-by-default, like the real interceptor ----
    const guard = REQUIRED.find((r) => r.match.test(path));
    if (guard && !guard.anyOf.some((code) => profile.permissions.includes(code))) {
      record.forbidden.push(path);
      return json(
        {
          type: "https://pantropi.com/vms/problems/forbidden",
          title: "You do not have access to this resource.",
          status: 403,
          detail: "You do not have access to this resource.",
          correlationId: "e2e-correlation-id",
        },
        403,
      );
    }

    // ---- admin data ----
    if (path === "/api/v1/admin/settings") return json(SETTINGS);
    if (path.match(/^\/api\/v1\/admin\/buildings\/[^/]+\/floors$/)) return json(page1([FLOOR]));
    if (path.match(/^\/api\/v1\/admin\/buildings\/[^/]+$/)) return json(BUILDING);
    if (path === "/api/v1/admin/buildings") return json(page1([BUILDING]));
    if (path === "/api/v1/admin/tenants") return json(page1([TENANT]));
    if (path === "/api/v1/admin/receptions") return json(page1([RECEPTION]));
    if (path === "/api/v1/admin/visitor-types") {
      return json(
        page1([
          {
            id: "v1111111-1111-1111-1111-111111111111",
            code: "CONTRACTOR",
            name: "Contractor",
            description: "Service/maintenance contractor",
            active: true,
          },
        ]),
      );
    }
    if (path === "/api/v1/admin/pass-types") {
      return json(
        page1([
          {
            id: "p1111111-1111-1111-1111-111111111111",
            code: "DAY_QR",
            name: "Single-day QR pass",
            defaultCredential: "qr",
            defaultRestriction: "time_bound",
            defaultValidHours: 12,
            active: true,
          },
        ]),
      );
    }
    if (path === "/api/v1/admin/holidays") {
      return json([
        {
          id: "h1111111-1111-1111-1111-111111111111",
          date: `${new Date().getFullYear()}-12-25`,
          name: "Christmas Day",
          working: false,
        },
      ]);
    }
    if (path === "/api/v1/admin/users") {
      return json({
        content: [
          {
            id: "u1111111-1111-1111-1111-111111111111",
            username: "jdoe",
            email: "jdoe@example.test",
            fullName: "Jane Doe",
            roleCode: "FLOOR_RECEPTIONIST",
            receptionId: RECEPTION.id,
            tenantId: null,
            active: true,
          },
        ],
        totalElements: 1,
        page: 0,
        size: 20,
      });
    }
    if (path === "/api/v1/admin/roles") return json(ROLES_OVERVIEW);

    // ---- the FM approval queue ----
    if (path === "/api/v1/visitor-requests/pending") {
      return json({
        content: [PENDING_ROW],
        totalElements: 1,
        page: 0,
        size: 20,
        maxSize: 100,
      });
    }
    if (path.match(/^\/api\/v1\/visitor-requests\/[^/]+\/(approve|reject)$/)) {
      return json({
        id: VISIT_REQUEST.id,
        status: path.endsWith("approve") ? "approved" : "rejected",
        decidedBy: FM_USER.userId,
        decidedAt: "2099-02-02T10:00:00Z",
        note: null,
      });
    }

    // ---- the tenant visitor journey ----
    if (path === "/api/v1/visitor-requests" && request.method() === "POST") {
      return json({ id: VISIT_REQUEST.id, status: "submitted" }, 201);
    }
    if (path === "/api/v1/visitor-requests") {
      // The conditional GET: echo a validator so the client can send it back. A test drives the
      // 304 path by asserting on the header, not by the stub second-guessing it.
      return route.fulfill({
        status: 200,
        headers: {
          ...CORS_HEADERS,
          "Content-Type": "application/json",
          ETag: 'W/"0123456789abcdef"',
          "Cache-Control": "private, no-cache",
          Vary: "Authorization",
        },
        body: JSON.stringify({
          content: [VISIT_ROW],
          totalElements: 1,
          page: 0,
          size: 20,
          maxSize: 100,
        }),
      });
    }
    if (path.match(/^\/api\/v1\/visitor-requests\/[^/]+\/cancel$/)) {
      return json({ id: VISIT_REQUEST.id, status: "cancelled" });
    }
    if (path.match(/^\/api\/v1\/visitor-requests\/[^/]+$/)) {
      if (request.method() === "PATCH") {
        return json({ id: VISIT_REQUEST.id, status: "submitted" });
      }
      return json(VISIT_REQUEST);
    }

    // Anything unmapped is a test bug, not a 404 to be papered over.
    return json({ error: "unmocked", path }, 501);
  });

  return record;
}

/**
 * Sign in through the real login form, so the tested path is the one users take.
 *
 * `landsOn` is where the root router sends this principal — /admin for an administrator, /visits
 * for a tenant. Passing it makes the redirect itself part of what the test asserts.
 */
export async function signIn(page: Page, landsOn = "**/admin"): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Username").fill("sysadmin");
  await page.getByLabel("Password").fill("Password123!local");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(landsOn);
}
