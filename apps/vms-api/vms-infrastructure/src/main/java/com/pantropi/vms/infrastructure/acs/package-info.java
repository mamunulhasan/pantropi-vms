/**
 * ACS anti-corruption layer — THE ONLY PACKAGE IN THE CODEBASE where an ACS-specific
 * type (DTO, error code, enum, vocabulary) may exist.
 *
 * <p>The wire adapter (F-11.7, blocked on TODO-02) and the simulator wiring live here.
 * Translation between VMS domain types and the ACS wire format happens here and nowhere
 * else, so a change to the UAL contract touches this package only — NFR-MNT-01 (SRS B1),
 * CON-01/CON-02 (SRS B1), ADR-0002. Enforced by fitness tests (US-01.2.2).
 */
package com.pantropi.vms.infrastructure.acs;
