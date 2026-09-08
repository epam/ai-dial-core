package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.fasterxml.jackson.databind.JsonNode;

public record AdminCatalogSchemaManifest(
        String kind,
        String name,
        @ApiSchema(schemaRef = "CatalogSchema") JsonNode spec) implements AdminTypedManifest {
}
