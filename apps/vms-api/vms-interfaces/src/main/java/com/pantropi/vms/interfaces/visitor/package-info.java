/**
 * Bounded context: {@code visitor} — VisitorRequest (root), Visitor, Host. Owns the pre-arrival journey: request, approval, pre-registration. (TDD §4.2 — EPIC-07/08, Phase 2.)
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.interfaces.visitor;
