/**
 * ACS port — the ONLY doorway to the Access Control System.
 *
 * <p>The {@code AcsPort} interface lands here with F-11.1 (pulled forward into Phase 1;
 * see ADR-0002). Every credential lifecycle action crosses this port; no VMS code may
 * reach ACS any other way — CON-02 (SRS B1).
 *
 * <p><strong>Deliberately absent</strong> from {@code vms-domain} and {@code vms-interfaces}:
 * CON-01 (SRS B1) and ADR-0002 forbid ACS concepts outside the integration boundary. The
 * backlog's AC-2 wording ("eight contexts under each layer") is corrected on this point —
 * an SRS-backed constraint outranks an enabler story's phrasing. Recorded in the US-01.2.1 PR.
 */
package com.pantropi.vms.application.acs.port;
