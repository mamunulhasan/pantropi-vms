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
