package com.epam.aidial.core.server.service;

/**
 * Who may set the admin-managed application fields ({@code app_identity}, {@code allow_user_external_services}).
 * Independent of {@code preserveForwardAuthToken}: an admin resource write may set {@code forwardAuthToken} yet
 * must not be authoritative for these governance fields — only config file / admin-apply is.
 */
public enum AdminManagedFieldsWriteMode {

    /** Config file / admin-apply: honor the incoming values (create and update). */
    AUTHORITATIVE,

    /** Any other write: strip on create, inherit the stored values on update. */
    INHERIT_ONLY
}
