package com.pantropi.vms.application.identity.port;

/**
 * Outbound port: does a role hold a permission? (US-02.2.1 AC-6; a focused precursor to the
 * full API-boundary authorization of US-03.2). Backed by {@code vms.role_permissions}.
 */
public interface PermissionChecker {

    boolean roleHasPermission(String roleCode, String permissionCode);
}
