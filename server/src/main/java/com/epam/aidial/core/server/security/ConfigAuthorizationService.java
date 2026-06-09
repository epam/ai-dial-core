package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;

/**
 * Authorization gate for the Configuration API. Per-entity CRUD shares the URL pattern
 * {@code /v1/{type}/{bucket}/{name}} with the existing user Resource API, so authorization
 * dispatches from {@code (role, verb, type, bucket)} rather than from URL prefix.
 *
 * <p>See {@code docs/sandbox/dial-unified-config/04-security-and-audit.md} §1.2.
 */
public interface ConfigAuthorizationService {

    /**
     * Check if the actor can perform the operation on the given entity.
     *
     * <p>{@code entityType} and {@code entityName} are reserved for future hierarchical
     * (multi-tenancy) implementations and per-entity ACLs (e.g., role-scoped allowlists,
     * tenant-bound resources). Implementations may dispatch on {@code bucket} and
     * {@code operation} only — the additional parameters exist so future implementations can
     * tighten authorization without reshaping every controller call site.
     */
    boolean isAuthorized(ProxyContext context, String entityType, String entityName,
                         String bucket, Operation operation);

    /**
     * Check whether the caller holds the admin role for cross-entity operations and projection
     * dispatch (Owner-vs-Public view selection per §1.5).
     *
     * <p>Used at two call sites: (a) the {@code /v1/admin/*} cross-entity endpoints (apply,
     * validate, export, audit, health/config, schema) which do not have a per-entity
     * {@code (type, bucket)} dimension and gate on the admin role only; (b) {@code projectionFor()}
     * in per-entity GET / listing controllers, where "admin OR bucket-owner" yields the Owner view
     * and everyone else gets Public.
     */
    boolean isAdmin(ProxyContext context);
}
