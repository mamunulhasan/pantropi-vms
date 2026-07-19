/**
 * Bounded context: {@code masterdata} — Building, Tenant, PassType. Owns reference data: buildings, floors, tenants, receptions, visitor types, pass types, holiday calendar. (TDD §4.1 — EPIC-04, TDD-DERIVED, held pending TODO-01.)
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.infrastructure.masterdata;
