/**
 * Bounded context: {@code reporting} — No aggregate roots — read models and projections only; never writes to another context. (TDD §4.5 — EPIC-18/19, Phase 4.)
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.interfaces.reporting;
