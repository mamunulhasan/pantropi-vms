/**
 * The tenant's host directory (US-10.1.1), as the submit form needs it.
 *
 * Guarded by `visitor.request` server-side and scoped in the repository, so this list is always
 * the caller's own tenant's people — there is no tenant id to pass and none would be honoured.
 *
 * Only the read is here. Creating and retiring hosts is the directory page of T-10.1.1.3, which
 * has not shipped: until it does, a tenant with an empty directory can submit without a host
 * exactly as before, and the form says so rather than presenting an empty control with no
 * explanation.
 */
import { apiFetch } from "./api";

export type HostSummary = {
  id: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  active: boolean;
};

export type HostPage = {
  content: HostSummary[];
  totalElements: number;
  page: number;
  size: number;
  maxSize: number;
};

export const HostsApi = {
  /**
   * Active hosts, for a selector. `active: true` is not a default anyone should rely on being
   * applied elsewhere — a departed host must not be attachable to a new visit (US-10.1.1 AC-3).
   */
  listActive: () => apiFetch<HostPage>("/api/v1/hosts?active=true&size=100"),
};
