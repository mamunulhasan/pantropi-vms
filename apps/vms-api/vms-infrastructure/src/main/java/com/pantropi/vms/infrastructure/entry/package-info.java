/**
 * Bounded context: {@code entry} — Visit, CardIssuance. Owns the live visit lifecycle, access events and card accountability. (TDD §4.4 — EPIC-12/13/15, Phase 3.)
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.infrastructure.entry;
