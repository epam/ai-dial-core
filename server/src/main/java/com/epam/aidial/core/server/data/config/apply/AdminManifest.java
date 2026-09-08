package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.openapi.annotations.ApiSubType;
import com.epam.aidial.core.openapi.annotations.ApiSubTypes;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire shape of one entry in the {@code manifests} array of {@code POST /v1/admin/apply} and
 * {@code POST /v1/admin/validate}. The OpenAPI component is a {@code kind}-discriminated oneOf
 * over the typed {@link AdminTypedManifest} implementations; at runtime each entry is converted
 * to its typed counterpart before validation/apply.
 */
@ApiSubTypes(
        discriminatorProperty = "kind",
        value = {
                @ApiSubType(discriminatorValue = "Settings", type = AdminSettingsManifest.class),
                @ApiSubType(discriminatorValue = "Schema", type = AdminSchemaManifest.class),
                @ApiSubType(discriminatorValue = "CatalogSchema", type = AdminCatalogSchemaManifest.class),
                @ApiSubType(discriminatorValue = "Interceptor", type = AdminInterceptorManifest.class),
                @ApiSubType(discriminatorValue = "Role", type = AdminRoleManifest.class),
                @ApiSubType(discriminatorValue = "Key", type = AdminKeyManifest.class),
                @ApiSubType(discriminatorValue = "Route", type = AdminRouteManifest.class),
                @ApiSubType(discriminatorValue = "Model", type = AdminModelManifest.class),
                @ApiSubType(discriminatorValue = "ToolSet", type = AdminToolSetManifest.class),
                @ApiSubType(discriminatorValue = "Application", type = AdminApplicationManifest.class)
        }
)
public record AdminManifest(String kind, String name, JsonNode spec) {
}
