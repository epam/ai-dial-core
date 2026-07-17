package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-field write mode for the admin-managed application fields ({@code app_identity},
 * {@code allow_user_external_services}): {@code honor*} takes the incoming value as authoritative,
 * otherwise the stored value is inherited on update and stripped on create. Producers:
 * config file / admin-apply honor both (declarative desired state); an admin PUT to the public
 * bucket honors exactly the fields present in the request body (see {@link #of});
 * every other write — non-admin PUT, any user-bucket write, copy/move, publication — honors neither.
 */
public record AdminManagedFieldsWriteMode(boolean honorAppIdentity, boolean honorAllowUserExternalServices) {

    public static final AdminManagedFieldsWriteMode AUTHORITATIVE = new AdminManagedFieldsWriteMode(true, true);

    public static final AdminManagedFieldsWriteMode INHERIT_ONLY = new AdminManagedFieldsWriteMode(false, false);

    /**
     * Mode for a resource-API PUT. An admin PUT to the public bucket honors exactly the admin-managed
     * fields present in the request body — an omitted field inherits the stored value (so a
     * read-modify-write client can never wipe a grant), while present-as-{@code null}/{@code false}
     * explicitly clears it. Any other resource write honors neither field.
     */
    public static AdminManagedFieldsWriteMode of(boolean adminPublicWrite, JsonNode body) {
        if (!adminPublicWrite) {
            return INHERIT_ONLY;
        }
        return new AdminManagedFieldsWriteMode(
                ProxyUtil.hasTopLevelField(body, "app_identity", "appIdentity"),
                ProxyUtil.hasTopLevelField(body, "allow_user_external_services", "allowUserExternalServices"));
    }
}
