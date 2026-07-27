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

    /** Permission code from {@code vms.permissions}, e.g. {@code user.manage}. */
    String value();
}
