package com.pantropi.vms.infrastructure.shared;

import com.pantropi.vms.application.shared.port.ClockPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wiring for shared infrastructure adapters (US-01.2.1, T-01.2.1.3).
 *
 * <p>THE CONVENTION: ports are bound to adapters here, in infrastructure
 * {@code @Configuration} classes — never by annotating application classes. Component
 * scanning covers {@code infrastructure} and {@code interfaces} only (see
 * {@code VmsApplication}), so an accidental annotation in {@code application} is inert.
 */
@Configuration
public class SharedInfrastructureConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    ClockPort clockPort(Clock clock) {
        return new SystemClockAdapter(clock);
    }
}
