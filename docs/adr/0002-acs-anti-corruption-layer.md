# ADR 0002 — Isolate ACS behind an anti-corruption layer, and build against a simulator

**Status:** Accepted
**Date:** 2026-07-19
**Deciders:** Lead Architect, Senior Java Architect
**Requirement:** CON-01, CON-02 (SRS B1); NFR-MNT-01, NFR-REL-01 (SRS B1); FR-API-01/02/03 (SRS B1)
**Related:** TODO-02, TDD §6

## Context

The detailed ACS API contract from Universal Automations Ltd — endpoints, authentication, payload
schemas, error codes, event delivery mechanism — **does not exist yet** (SRS Appendix B item 4, TDD
§11 dependency 1). The TDD states plainly that until it is provided, the ACS Integration Client
*"can be scaffolded against an interface but cannot be completed or tested against the real system."*

This blocks 15 of 28 SRS requirements — every credential operation, card accountability, exit
notification, and the ACS-sourced half of reporting.

Meanwhile the SRS is unambiguous about the boundary:

- **CON-01** — VMS shall not embed logic specific to ACS hardware.
- **CON-02** — all credential lifecycle actions go through the ACS API, never a shared database or
  direct hardware call.
- **NFR-MNT-01** — changes to the ACS API contract shall be isolated to the integration layer,
  without requiring changes to core VMS workflow logic.

We need an approach that lets us build real, testable functionality now, and absorb the eventual
contract without rework.

## Decision

**1. A single ACS port owned by the application layer.**
We define `AcsPort` in the application layer, expressed in **VMS domain terms** — not ACS terms. It
is the only way any VMS code reaches ACS. Operations follow TDD §6.1: `createCredential`,
`deactivateCredential`, `queryCredentialStatus`, plus inbound access/exit and card event handling.

**2. An anti-corruption layer at the adapter.**
The infrastructure adapter translates between VMS domain types and the ACS wire format. No ACS DTO,
error code, enum, or vocabulary crosses into the application or domain layers. When UAL publishes
the contract, the translation changes; nothing above it does.

**3. An ACS simulator as a first-class deliverable.**
We build a simulator implementing `AcsPort` with configurable latency, failure injection, and event
emission. Integration tests for every credential workflow run against it. The simulator is
production-quality test infrastructure, not a stub.

**4. Enforcement by automated architecture fitness test.**
CI fails the build if any type in the ACS adapter package is referenced from the domain,
application, or interface layers. Convention is not enough for a rule this important.

**5. Reliability patterns live in the port implementation, not in callers.**
Retry with exponential backoff, the durable outbox (`vms.acs_requests`), dead-letter handling, and
idempotent inbound event handling keyed on `acs_event_id` (per the schema's unique constraint) are
all implemented once, behind the port. Callers see a simple interface, whether or not ACS is up.

## Alternatives considered

**Wait for the contract.** Honest, but it idles the team indefinitely on a dependency we do not
control, and leaves us with zero credential-workflow test coverage when the contract finally lands.
Rejected.

**Guess the contract from the schema and TDD.** The schema's `acs_credential_id`, `qr_payload` and
`acs_op` enum hint at the shape. But guessing produces a design shaped around our guess, and the
anti-corruption layer gives us the same freedom to start without pretending we know the answer.
Rejected — and it would breach project rule 2.

**Thin pass-through client, ACS types used directly in services.** Simplest to write today. It
directly violates CON-01 and NFR-MNT-01, and it means the eventual contract ripples through every
service. Rejected.

## Consequences

**Positive**
- Credential workflows are buildable and testable **now**, against the simulator.
- Absorbing the real contract becomes an adapter change, not a rewrite — this is the whole point.
- CON-01, CON-02 and NFR-MNT-01 are satisfied by construction and verified in CI.
- Failure modes (ACS down, slow, returning garbage) become *testable on demand* rather than
  discovered in production — which is what NFR-REL-01 and FR-API-01 actually require.

**Negative**
- Translation code is real work with no user-visible output.
- The simulator must be maintained alongside the adapter, and can drift from real ACS behaviour.
- Our port design may not map cleanly onto UAL's eventual contract, forcing adapter complexity —
  or, if the mismatch is severe, a port revision.

**Honest limitation**
Passing tests against our own simulator proves our code is internally consistent. It proves nothing
about ACS. Every ACS-dependent story stays at **"done against simulator"** and cannot be marked
verified until it runs against the UAL non-production endpoint (TDD §9.3). The traceability matrix
records this distinction explicitly, and the milestone plan splits M3 into Stage A and Stage B for
exactly this reason.
