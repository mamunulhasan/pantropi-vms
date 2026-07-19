package com.pantropi.vms.infrastructure.shared;

import com.pantropi.vms.application.shared.port.ClockPort;

import java.time.Clock;
import java.time.Instant;

/**
 * System-clock adapter for {@link ClockPort} — the infrastructure half of the reference
 * port/adapter pair (US-01.2.1, T-01.2.1.3).
 *
 * <p>Deliberately a plain class, not a {@code @Component}: beans are registered explicitly
 * in {@link SharedInfrastructureConfig} so wiring stays visible and reviewable.
 */
public final class SystemClockAdapter implements ClockPort {

    private final Clock clock;

    public SystemClockAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
