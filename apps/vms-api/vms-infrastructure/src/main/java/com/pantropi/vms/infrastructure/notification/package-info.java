/**
 * Bounded context: {@code notification} — Notification. Owns dispatch and delivery status. (TDD §8 — EPIC-16/17, Phase 4.)
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.infrastructure.notification;
