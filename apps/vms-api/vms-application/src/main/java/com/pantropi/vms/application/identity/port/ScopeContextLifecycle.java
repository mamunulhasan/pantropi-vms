package com.pantropi.vms.application.identity.port;

import java.util.UUID;

/**
 * Opens and closes a request's scope (US-03.4.1, T-03.4.1.1).
 *
 * <p>Separate from {@link ScopeContext} because the two have different callers and one of them must
 * not have the other's power. The edge populates and clears; everything inward only reads. Handing
 * the read side a {@code begin} method would make "which tenant am I scoped to" something any use
 * case could answer for itself.
 *
 * <p>{@link #clear()} must run for every request that called {@link #begin}, including one whose
 * handler threw. Servlet threads are pooled, and a scope left behind is inherited by the next
 * request on that thread — one user's isolation silently applied to another's query.
 */
public interface ScopeContextLifecycle {

    void begin(UUID userId, String roleCode);

    void clear();
}
