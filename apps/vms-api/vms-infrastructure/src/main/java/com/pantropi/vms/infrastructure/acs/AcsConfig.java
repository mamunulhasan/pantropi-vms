package com.pantropi.vms.infrastructure.acs;

import com.pantropi.vms.application.acs.port.AcsPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the ACS boundary (US-11.2.1).
 *
 * <p>{@code vms.acs.mode} chooses the adapter, and today {@code simulator} is the only value that
 * resolves — the wire adapter is F-11.7 and is blocked outright on TODO-02. The property exists
 * now anyway, so that switching to a real ACS is configuration rather than a code change, and so
 * that no environment is ever pointed at the simulator by accident: an environment that wants a
 * real ACS sets {@code vms.acs.mode=wire} and fails to start until one exists, which is the
 * correct behaviour for a building's access control.
 *
 * <p>Deliberately <strong>not</strong> defaulted on: {@code matchIfMissing} is false, so a profile
 * that says nothing about ACS gets no {@code AcsPort} at all rather than silently getting a
 * simulator. A missing bean is a loud failure; a simulated barrier in production would not be.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.acs", name = "mode", havingValue = "simulator")
public class AcsConfig {

    @Bean
    AcsPort acsPort(Clock clock, @Value("${vms.acs.simulator.banner:true}") boolean banner) {
        if (banner) {
            // Printed rather than logged at debug, because someone reading a startup log needs to
            // see this without going looking: no credential issued here opens a real door.
            System.out.println("""

                    ============================================================
                     ACS SIMULATOR ACTIVE - no credential reaches a real barrier
                     Stories exercised against this close at "done against
                     simulator", never "verified" (ADR-0002, TODO-02).
                    ============================================================
                    """);
        }
        return new AcsSimulator(clock);
    }
}
