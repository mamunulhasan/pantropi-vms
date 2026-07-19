/**
 * Bounded context: {@code shared} — No aggregate roots — value objects, domain events and base types shared across contexts.
 *
 * <p>US-01.2.1 (T-01.2.1.2). Cross-context communication is by domain event or an
 * explicit port — never a shared table (workflow doc §8). One aggregate per transaction.
 */
package com.pantropi.vms.infrastructure.shared;
