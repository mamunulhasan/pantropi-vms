package com.pantropi.vms.application.shared.port;

import java.util.function.Supplier;

/**
 * Outbound port for running a unit of work in a single transaction (US-02.2.2, T-02.2.2.2).
 *
 * <p>Lets a pure application use case demand transactional atomicity — a bulk import creates its
 * batch all-or-nothing — without importing a transaction framework. Implemented in infrastructure
 * with Spring's {@code TransactionTemplate}.
 */
public interface TransactionRunner {

    <T> T call(Supplier<T> work);

    default void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }
}
