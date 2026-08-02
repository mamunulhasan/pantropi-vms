/**
 * Typed client for the admin/master-data API (UI-4a; consumes US-04.x endpoints).
 *
 * Shapes are transcribed from the controllers, and two of their properties matter everywhere:
 *
 * - The list envelope here is `{items, page, size, total}` — deliberately not shared with the
 *   visitor endpoints' `{content, totalElements}` envelope. Different API, different type.
 * - Business errors arrive as ad-hoc JSON (`{error, field, message}` from master data,
 *   `{error, key, message}` from settings), while auth/routing/500 arrive as RFC 7807
 *   problem+json, and an unknown id is a 404 with an **empty** body. {@link errorMessage} and
 *   {@link fieldError} absorb all three so screens never parse a payload themselves.
 *
 * The server silently clamps `size` (≤200) and silently ignores an unknown `sort` — so screens
 * should render the `page`/`size` echoed on the response, not what they asked for.
 */
import { apiFetch, ApiError } from "./api";

export type Page<T> = { items: T[]; page: number; size: number; total: number };

export type ListParams = {
  search?: string;
  active?: boolean;
  page?: number;
  size?: number;
  sort?: string;
};

export function listQuery(params: ListParams): string {
  const q = new URLSearchParams();
  if (params.search) q.set("search", params.search);
  if (params.active !== undefined) q.set("active", String(params.active));
  if (params.page) q.set("page", String(params.page));
  if (params.size !== undefined) q.set("size", String(params.size));
  if (params.sort) q.set("sort", params.sort);
  const s = q.toString();
  return s ? `?${s}` : "";
}

// ---- settings (US-04.8.1/2) ----

export type SettingType = "INTEGER" | "BOOLEAN" | "STRING";

export type Setting = {
  key: string;
  /** Always a JSON string, even for INTEGER/BOOLEAN — round-trip it as one. */
  value: string;
  type: SettingType;
  description: string;
  updatedBy: string | null;
  updatedAt: string | null;
  /** True when no override row exists and `value` is the catalogue default. */
  usingDefault: boolean;
  /** True means `value` is a redaction marker, not data — render "set, not shown". */
  secret: boolean;
};

export const SettingsApi = {
  list: () => apiFetch<Setting[]>("/api/v1/admin/settings"),
  update: (key: string, value: string) =>
    apiFetch<Setting>(`/api/v1/admin/settings/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    }),
};

// ---- buildings and floors (US-04.1.1 / US-04.2.1) ----

export type Building = {
  id: string;
  code: string;
  name: string;
  address: string | null;
  active: boolean;
};

export type BuildingRequest = { code: string; name: string; address?: string | null };

export const BuildingsApi = {
  list: (params: ListParams) =>
    apiFetch<Page<Building>>(`/api/v1/admin/buildings${listQuery(params)}`),
  get: (id: string) => apiFetch<Building>(`/api/v1/admin/buildings/${id}`),
  create: (req: BuildingRequest) =>
    apiFetch<{ id: string }>("/api/v1/admin/buildings", {
      method: "POST",
      body: JSON.stringify(req),
    }),
  update: (id: string, req: BuildingRequest) =>
    apiFetch<null>(`/api/v1/admin/buildings/${id}`, { method: "PUT", body: JSON.stringify(req) }),
  deactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/buildings/${id}/deactivate`, { method: "POST" }),
  reactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/buildings/${id}/reactivate`, { method: "POST" }),
};

export type Floor = {
  id: string;
  buildingId: string;
  code: string;
  name: string;
  levelNo: number | null;
  active: boolean;
};

export type FloorRequest = { code: string; name: string; levelNo?: number | null };

export const FloorsApi = {
  list: (buildingId: string, params: ListParams) =>
    apiFetch<Page<Floor>>(`/api/v1/admin/buildings/${buildingId}/floors${listQuery(params)}`),
  create: (buildingId: string, req: FloorRequest) =>
    apiFetch<{ id: string }>(`/api/v1/admin/buildings/${buildingId}/floors`, {
      method: "POST",
      body: JSON.stringify(req),
    }),
  update: (buildingId: string, id: string, req: FloorRequest) =>
    apiFetch<null>(`/api/v1/admin/buildings/${buildingId}/floors/${id}`, {
      method: "PUT",
      body: JSON.stringify(req),
    }),
  deactivate: (buildingId: string, id: string) =>
    apiFetch<null>(`/api/v1/admin/buildings/${buildingId}/floors/${id}/deactivate`, {
      method: "POST",
    }),
  reactivate: (buildingId: string, id: string) =>
    apiFetch<null>(`/api/v1/admin/buildings/${buildingId}/floors/${id}/reactivate`, {
      method: "POST",
    }),
};

// ---- visitor types (US-04.5.1) ----

export type VisitorType = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
};

export type VisitorTypeRequest = { code: string; name: string; description?: string | null };

export const VisitorTypesApi = {
  list: (params: ListParams) =>
    apiFetch<Page<VisitorType>>(`/api/v1/admin/visitor-types${listQuery(params)}`),
  create: (req: VisitorTypeRequest) =>
    apiFetch<{ id: string }>("/api/v1/admin/visitor-types", {
      method: "POST",
      body: JSON.stringify(req),
    }),
  update: (id: string, req: VisitorTypeRequest) =>
    apiFetch<null>(`/api/v1/admin/visitor-types/${id}`, {
      method: "PUT",
      body: JSON.stringify(req),
    }),
  deactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/visitor-types/${id}/deactivate`, { method: "POST" }),
  reactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/visitor-types/${id}/reactivate`, { method: "POST" }),
};

// ---- pass types (US-04.6.1) ----

/** Rendered as the database spells them — lowercase, not the Java constant names. */
export const CREDENTIAL_TYPES = ["qr", "rfid"] as const;
export type CredentialType = (typeof CREDENTIAL_TYPES)[number];

export const RESTRICTION_TYPES = ["time_bound", "one_time"] as const;
export type RestrictionType = (typeof RESTRICTION_TYPES)[number];

export type PassType = {
  id: string;
  code: string;
  name: string;
  defaultCredential: CredentialType;
  defaultRestriction: RestrictionType;
  defaultValidHours: number;
  active: boolean;
};

export type PassTypeRequest = {
  code: string;
  name: string;
  defaultCredential: CredentialType;
  defaultRestriction: RestrictionType;
  /** 1..744 (a full month) — the server refuses zero and above-a-month. */
  defaultValidHours: number;
};

export const PassTypesApi = {
  list: (params: ListParams) =>
    apiFetch<Page<PassType>>(`/api/v1/admin/pass-types${listQuery(params)}`),
  create: (req: PassTypeRequest) =>
    apiFetch<{ id: string }>("/api/v1/admin/pass-types", {
      method: "POST",
      body: JSON.stringify(req),
    }),
  update: (id: string, req: PassTypeRequest) =>
    apiFetch<null>(`/api/v1/admin/pass-types/${id}`, { method: "PUT", body: JSON.stringify(req) }),
  deactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/pass-types/${id}/deactivate`, { method: "POST" }),
  reactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/pass-types/${id}/reactivate`, { method: "POST" }),
};

// ---- tenants (US-04.3.1) ----

export type Tenant = {
  id: string;
  code: string;
  name: string;
  floorId: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  active: boolean;
};

export type TenantRequest = {
  code: string;
  name: string;
  floorId?: string | null;
  contactEmail?: string | null;
  contactPhone?: string | null;
};

export const TenantsApi = {
  list: (params: ListParams) => apiFetch<Page<Tenant>>(`/api/v1/admin/tenants${listQuery(params)}`),
  get: (id: string) => apiFetch<Tenant>(`/api/v1/admin/tenants/${id}`),
  create: (req: TenantRequest) =>
    apiFetch<{ id: string }>("/api/v1/admin/tenants", { method: "POST", body: JSON.stringify(req) }),
  update: (id: string, req: TenantRequest) =>
    apiFetch<null>(`/api/v1/admin/tenants/${id}`, { method: "PUT", body: JSON.stringify(req) }),
  /** How many active users are assigned — shown before deactivation; it informs, never blocks. */
  dependents: (id: string) =>
    apiFetch<{ activeUsers: number }>(`/api/v1/admin/tenants/${id}/dependents`),
  deactivate: (id: string) =>
    apiFetch<{ activeUsers: number }>(`/api/v1/admin/tenants/${id}/deactivate`, { method: "POST" }),
  reactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/tenants/${id}/reactivate`, { method: "POST" }),
};

// ---- receptions (US-04.4.1) ----

export type Reception = {
  id: string;
  floorId: string;
  code: string;
  name: string;
  central: boolean;
  active: boolean;
};

export const ReceptionsApi = {
  list: (params: ListParams) =>
    apiFetch<Page<Reception>>(`/api/v1/admin/receptions${listQuery(params)}`),
  create: (req: { floorId: string; code: string; name: string }) =>
    apiFetch<{ id: string }>("/api/v1/admin/receptions", { method: "POST", body: JSON.stringify(req) }),
  /** Only code and name are updatable; the floor and the central flag are preserved. */
  update: (id: string, req: { code: string; name: string }) =>
    apiFetch<null>(`/api/v1/admin/receptions/${id}`, { method: "PUT", body: JSON.stringify(req) }),
  /**
   * The two-step transfer: without confirm, a held designation answers 409
   * `confirmation_required` naming the current holder; retry with confirm=true to move it.
   */
  designateCentral: (id: string, confirm: boolean) =>
    apiFetch<null>(
      `/api/v1/admin/receptions/${id}/designate-central${confirm ? "?confirm=true" : ""}`,
      { method: "POST" },
    ),
  dependents: (id: string) =>
    apiFetch<{ activeUsers: number }>(`/api/v1/admin/receptions/${id}/dependents`),
  deactivate: (id: string) =>
    apiFetch<{ activeUsers: number }>(`/api/v1/admin/receptions/${id}/deactivate`, { method: "POST" }),
  reactivate: (id: string) =>
    apiFetch<null>(`/api/v1/admin/receptions/${id}/reactivate`, { method: "POST" }),
};

// ---- holidays (US-04.7.x) ----

export type Holiday = {
  id: string;
  date: string;
  name: string;
  /** true = a working-day exception (compensating Saturday); false = a non-working holiday. */
  working: boolean;
};

export type HolidayRequest = { date: string; name: string; working: boolean };

export type HolidayImportReport = {
  accepted: boolean;
  applied: number;
  rows: { date: string; status: "applied" | "rejected" | "not_applied"; reason: string | null }[];
};

export const HolidaysApi = {
  /** A bare array, ordered by date — the year is the envelope. */
  listYear: (year: number) => apiFetch<Holiday[]>(`/api/v1/admin/holidays?year=${year}`),
  create: (req: HolidayRequest) =>
    apiFetch<{ id: string }>("/api/v1/admin/holidays", { method: "POST", body: JSON.stringify(req) }),
  update: (id: string, req: HolidayRequest) =>
    apiFetch<null>(`/api/v1/admin/holidays/${id}`, { method: "PUT", body: JSON.stringify(req) }),
  remove: (id: string) => apiFetch<null>(`/api/v1/admin/holidays/${id}`, { method: "DELETE" }),
  /**
   * All-or-nothing: 200 = every row applied; a 409 carries the same report shape with per-row
   * reasons and nothing written — which makes the 409 the dry run.
   */
  importAll: (rows: HolidayRequest[]) =>
    apiFetch<HolidayImportReport>("/api/v1/admin/holidays/import", {
      method: "POST",
      body: JSON.stringify(rows),
    }),
};

// ---- users (US-02.2.1/2) ----

/** The users API predates the master-data envelope and kept Spring's names. */
export type UserPage<T> = { content: T[]; totalElements: number; page: number; size: number };

export type AdminUser = {
  id: string;
  username: string;
  email: string | null;
  fullName: string;
  roleCode: string;
  receptionId: string | null;
  tenantId: string | null;
  active: boolean;
};

export type UserListParams = {
  role?: string;
  receptionId?: string;
  /** Narrows to one tenant organisation's accounts. */
  tenantId?: string;
  active?: boolean;
  page?: number;
  size?: number;
  /** Raw column names: username | email | full_name | created_at. */
  sort?: string;
};

export function userListQuery(params: UserListParams): string {
  const q = new URLSearchParams();
  if (params.role) q.set("role", params.role);
  if (params.receptionId) q.set("receptionId", params.receptionId);
  if (params.tenantId) q.set("tenantId", params.tenantId);
  if (params.active !== undefined) q.set("active", String(params.active));
  if (params.page) q.set("page", String(params.page));
  if (params.size !== undefined) q.set("size", String(params.size));
  if (params.sort) q.set("sort", params.sort);
  const s = q.toString();
  return s ? `?${s}` : "";
}

export type ImportPreview = {
  rows: {
    line: number;
    username: string | null;
    status: "CREATABLE" | "CONFLICT" | "REJECTED";
    reason: string | null;
  }[];
  creatable: number;
  conflicts: number;
  rejected: number;
};

export type ImportResult = {
  created: { id: string; username: string; activationToken: string }[];
  skipped: number;
  rejected: number;
};

export const UsersApi = {
  list: (params: UserListParams) =>
    apiFetch<UserPage<AdminUser>>(`/api/v1/admin/users${userListQuery(params)}`),
  create: (req: {
    username: string;
    email?: string | null;
    fullName: string;
    roleCode: string;
    receptionId?: string | null;
    tenantId?: string | null;
  }) => apiFetch<{ id: string }>("/api/v1/admin/users", { method: "POST", body: JSON.stringify(req) }),
  /** Assignment only — username/email/fullName are immutable through the API. */
  updateAssignment: (
    id: string,
    req: { roleCode: string; receptionId?: string | null; tenantId?: string | null },
  ) => apiFetch<null>(`/api/v1/admin/users/${id}`, { method: "PUT", body: JSON.stringify(req) }),
  deactivate: (id: string) => apiFetch<null>(`/api/v1/admin/users/${id}/deactivate`, { method: "POST" }),
  reactivate: (id: string) => apiFetch<null>(`/api/v1/admin/users/${id}/reactivate`, { method: "POST" }),
  unlock: (id: string) => apiFetch<null>(`/api/v1/admin/users/${id}/unlock`, { method: "POST" }),
  /** Issues a fresh single-use activation token and revokes every live session. */
  resetPassword: (id: string) =>
    apiFetch<{ activationToken: string }>(`/api/v1/admin/users/${id}/reset-password`, {
      method: "POST",
    }),
  /** Raw CSV body, text/csv — not multipart. Writes nothing. */
  importPreview: (csv: string) =>
    apiFetch<ImportPreview>("/api/v1/admin/users/import/preview", {
      method: "POST",
      headers: { "Content-Type": "text/csv" },
      body: csv,
    }),
  importExecute: (csv: string, fileName: string, confirmSkipConflicts: boolean) =>
    apiFetch<ImportResult>(
      `/api/v1/admin/users/import?fileName=${encodeURIComponent(fileName)}${
        confirmSkipConflicts ? "&confirmSkipConflicts=true" : ""
      }`,
      { method: "POST", headers: { "Content-Type": "text/csv" }, body: csv },
    ),
};

// ---- roles & grants (US-03.1.1 / US-03.3.1) ----

export type RoleView = {
  code: string;
  name: string | null;
  description: string | null;
  /** Sorted; [] when none. */
  permissions: string[];
  /** Content-derived token over the sorted permission set — the PUT's precondition. */
  version: string;
  activeUsers: number;
};

export type RolesOverview = {
  roles: RoleView[];
  catalogue: { code: string; description: string }[];
};

export type StaleVersionConflict = {
  error: "stale_version";
  roleCode: string;
  currentVersion: string;
  currentPermissions: string[];
  message: string;
};

export const RolesApi = {
  overview: () => apiFetch<RolesOverview>("/api/v1/admin/roles"),
  /**
   * Full replacement of a role's grants, gated on the version read with the matrix. A stale
   * version answers 409 `stale_version` carrying the current set, so the screen re-renders and
   * re-asks — it never silently retries.
   */
  putGrants: (roleCode: string, version: string, permissions: string[]) =>
    apiFetch<RoleView>(`/api/v1/admin/roles/${encodeURIComponent(roleCode)}/grants`, {
      method: "PUT",
      body: JSON.stringify({ version, permissions }),
    }),
};

export function staleVersionOf(error: unknown): StaleVersionConflict | null {
  if (error instanceof ApiError && error.problem?.["error"] === "stale_version") {
    return error.problem as unknown as StaleVersionConflict;
  }
  return null;
}

// ---- error absorption ----

/**
 * The user-facing sentence for any failure from these endpoints.
 *
 * Preference order: the ad-hoc body's `message` (written per field/key by the controller), then
 * problem+json's `detail` (the fixed non-disclosing sentence), then a generic fallback covering
 * empty-body 404s and network failures. Never the raw payload, never `Error#message`.
 */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const message = error.problem?.["message"];
    if (typeof message === "string") {
      return message;
    }
    // The users API's last_master_admin refusal ships its sentence in the `field` slot — a wire
    // quirk this absorbs so the admin reads the refusal, not a generic shrug.
    if (error.problem?.["error"] === "last_master_admin") {
      const field = error.problem?.["field"];
      if (typeof field === "string") {
        return field;
      }
    }
    if (error.detail) {
      return error.detail;
    }
    if (error.status === 404) {
      return "This record no longer exists. It may have been changed by someone else.";
    }
  }
  return "Something went wrong. Try again.";
}

/**
 * The field a business error names (`field` from master data, `key` from settings), so a form
 * can attach the message to the control it belongs to instead of a page-level banner.
 */
export function fieldError(error: unknown): { field: string; message: string } | null {
  if (!(error instanceof ApiError)) {
    return null;
  }
  const field = error.problem?.["field"] ?? error.problem?.["key"];
  const message = error.problem?.["message"];
  if (typeof field === "string" && typeof message === "string") {
    return { field, message };
  }
  return null;
}

// ---- audit trail (US-02.5.1) ----

export type AuditEntry = {
  id: string;
  at: string;
  action: string;
  entityType: string | null;
  entityId: string | null;
  actorId: string | null;
  /** A display name, or null once the account is removed — the entry outlives it. */
  actor: string | null;
  beforeState: string | null;
  afterState: string | null;
  ipAddress: string | null;
};

export type AuditPage = {
  content: AuditEntry[];
  totalElements: number;
  page: number;
  size: number;
};

export type AuditVocabulary = { actions: string[]; entityTypes: string[] };

export type AuditFilters = {
  action?: string;
  entityType?: string;
  actorId?: string;
  from?: string;
  /** Exclusive, so a single day is [00:00, next 00:00) and nothing lands in two days. */
  to?: string;
  page?: number;
  size?: number;
};

export function auditQuery(filters: AuditFilters): string {
  const q = new URLSearchParams();
  if (filters.action) q.set("action", filters.action);
  if (filters.entityType) q.set("entityType", filters.entityType);
  if (filters.actorId) q.set("actorId", filters.actorId);
  if (filters.from) q.set("from", filters.from);
  if (filters.to) q.set("to", filters.to);
  if (filters.page) q.set("page", String(filters.page));
  if (filters.size !== undefined) q.set("size", String(filters.size));
  const s = q.toString();
  return s ? `?${s}` : "";
}

export const AuditApi = {
  list: (filters: AuditFilters) =>
    apiFetch<AuditPage>(`/api/v1/admin/audit${auditQuery(filters)}`),
  vocabulary: () => apiFetch<AuditVocabulary>("/api/v1/admin/audit/vocabulary"),
};
