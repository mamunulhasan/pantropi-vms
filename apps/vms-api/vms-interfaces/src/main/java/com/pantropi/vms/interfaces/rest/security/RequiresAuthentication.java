package com.pantropi.vms.interfaces.rest.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler requires a valid, live session but no particular permission
 * (US-03.2.1, T-03.2.1.2) — for example {@code /auth/me} and {@code /auth/logout}, which act on the
 * caller's own session.
 *
 * <p>This is an explicit declaration, not an absence of one: deny-by-default still applies to any
 * route that declares nothing.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAuthentication {
}
