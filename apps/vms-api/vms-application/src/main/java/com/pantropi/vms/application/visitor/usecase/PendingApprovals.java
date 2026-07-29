package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;

/**
 * The approval queue an FM Admin works through (US-07.3.1, T-07.3.1.3) — FR-VMS-02 (SRS B1).
 *
 * <p>Thin on purpose. What it contributes is the bound: the endpoint hands over raw {@code page} and
 * {@code size} query parameters and this is where they become a {@link PageRequest}, which cannot
 * exist unclamped (AC-5). Letting the controller build one would be equivalent today and would put
 * the ceiling one refactor away from being optional.
 *
 * <p>Which rows are visible is not decided here. The store obtains that from the scoping policy, so
 * this class has no tenant condition to get wrong (AC-6).
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class PendingApprovals {

    private final ApprovalQueueStore queue;

    public PendingApprovals(ApprovalQueueStore queue) {
        this.queue = queue;
    }

    /**
     * @param page zero-based; a negative value is treated as the first page
     * @param size clamped to {@link PageRequest#MAX_SIZE}, and the applied value is on the result
     */
    public ApprovalQueueStore.Page list(int page, int size) {
        return queue.pending(new PageRequest(page, size));
    }
}
