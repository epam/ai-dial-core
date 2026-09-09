package com.epam.aidial.core.server.data.config.apply;

/**
 * Typed counterpart of {@link AdminManifest}: the per-kind record a manifest is converted to once
 * its {@code kind} is known. Every implementation mirrors the wire shape — {@code kind} and
 * {@code name} carried verbatim, {@code spec} bound to the entity type the kind selects.
 */
public sealed interface AdminTypedManifest permits
        AdminSettingsManifest,
        AdminSchemaManifest,
        AdminCatalogSchemaManifest,
        AdminInterceptorManifest,
        AdminTranslatorManifest,
        AdminRoleManifest,
        AdminKeyManifest,
        AdminRouteManifest,
        AdminModelManifest,
        AdminToolSetManifest,
        AdminApplicationManifest {

    String kind();

    String name();
}
