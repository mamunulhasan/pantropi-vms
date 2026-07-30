package com.pantropi.vms.interfaces.rest.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission a handler requires (US-03.2.1, T-03.2.1.2).
 *
 * <p>Evaluated by {@link AuthorizationInterceptor} <em>before</em> the handler runs and before any
 * read of the target resource, so a denial performs no work and discloses nothing (AC-2, AC-5).
 *
 * <p>May be placed on a controller class (applying to every handler in it) or on a single method,
 * where the method-level declaration wins.
 *
 * <p><strong>Deny-by-default:</strong> a route carrying neither this nor {@link RequiresAuthentication},
 * and absent from {@link PublicRoutes}, is refused at runtime and fails the coverage test (AC-3).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * Permission codes from {@code vms.permissions}, e.g. {@code user.manage}.
     *
     * <p>Holding <strong>any one</strong> of them satisfies the route (US-07.3.3, T-07.3.3.2).
     * Some resources are legitimately reachable by two different authorities: a request detail is
     * read by the tenant who raised it under {@code visitor.request} and by the FM Admin deciding it
     * under {@code visitor.approve}, and neither is a subset of the other.
     *
     * <p>Any-of rather than all-of because that is what such a route means. A resource needing two
     * permissions at once is a different shape, and if one ever appears it should say so explicitly
     * rather than inheriting the meaning from here.
     *
     * <p>Declaring two codes does <em>not</em> mean the two callers see the same thing. What each
     * may read is still decided by the scoping policy at the query — this only decides who may ask.
     */
    String[] value();
}
