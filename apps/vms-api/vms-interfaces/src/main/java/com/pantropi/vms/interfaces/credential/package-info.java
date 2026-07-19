/**
 * Bounded context: {@code credential} — Credential (root) with ValidityWindow and Restriction. Owns the credential lifecycle and the ACS credential reference. (TDD §4.3 — EPIC-09/14.)
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.interfaces.credential;
