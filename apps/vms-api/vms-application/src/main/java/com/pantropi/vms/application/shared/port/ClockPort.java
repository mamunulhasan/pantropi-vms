package com.pantropi.vms.application.shared.port;

import java.time.Instant;

/**
 * Reference outbound port — proves the port/adapter convention (US-01.2.1, T-01.2.1.3, AC-3).
 *
 * <p>The convention every future port follows:
 * <ol>
 *   <li>The interface lives in {@code vms-application}, expressed in domain terms,
 *       with no framework import.</li>
 *   <li>The implementation lives in {@code vms-infrastructure} as a plain class.</li>
 *   <li>The bean is registered by a {@code @Configuration} class in infrastructure —
 *       application code is never annotated as a Spring component.</li>
 * </ol>
 *
 * <p>A clock is the deliberate choice of example: time is an input, and treating it as a
 * port makes validity-window logic (FR-VMS-11, SRS B1) testable with a fixed clock instead
 * of {@code Instant.now()} scattered through the code.
 */
public interface ClockPort {

    /** Current instant, from whatever the adapter decides "now" means. */
    Instant now();
}
