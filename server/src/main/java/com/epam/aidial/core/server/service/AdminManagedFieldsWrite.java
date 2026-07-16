package com.epam.aidial.core.server.service;

/**
 * Per-field write policy for the admin-managed application fields ({@code app_identity},
 * {@code allow_user_external_services}): {@code honor*} takes the incoming value as authoritative,
 * otherwise the stored value is inherited on update and stripped on create. Producers:
 * config file / admin-apply honor both (declarative desired state); an admin PUT to the public
 * bucket honors exactly the fields present in the request body (so a read-modify-write client that
 * omits them can never wipe a grant, while present-as-null explicitly clears); every other write —
 * non-admin PUT, any user-bucket write, copy/move, publication — honors neither.
 */
public record AdminManagedFieldsWrite(boolean honorAppIdentity, boolean honorAllowUserExternalServices) {

    public static final AdminManagedFieldsWrite AUTHORITATIVE = new AdminManagedFieldsWrite(true, true);

    public static final AdminManagedFieldsWrite INHERIT_ONLY = new AdminManagedFieldsWrite(false, false);
}
