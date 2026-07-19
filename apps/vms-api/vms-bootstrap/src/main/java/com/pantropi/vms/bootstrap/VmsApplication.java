package com.pantropi.vms.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Project Pinnacle VMS — application entry point (US-01.2.1, T-01.2.1.3).
 *
 * <p>Component scanning is restricted to {@code infrastructure} and {@code interfaces}
 * (plus this bootstrap package). {@code domain} and {@code application} are invisible to
 * Spring by construction: their classes carry no annotations and their packages are not
 * scanned, so the inner layers cannot silently accrete framework coupling.
 */
@SpringBootApplication(scanBasePackages = {
        "com.pantropi.vms.bootstrap",
        "com.pantropi.vms.infrastructure",
        "com.pantropi.vms.interfaces"
})
public class VmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(VmsApplication.class, args);
    }
}
