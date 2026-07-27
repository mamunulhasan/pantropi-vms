package com.pantropi.vms.infrastructure.shared;

import com.pantropi.vms.application.shared.port.TransactionRunner;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Spring-backed {@link TransactionRunner} (US-02.2.2). Runs the supplied work in a single
 * transaction; any exception rolls the whole unit back — a bulk import creates its batch
 * all-or-nothing.
 */
public final class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    public SpringTransactionRunner(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T call(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
