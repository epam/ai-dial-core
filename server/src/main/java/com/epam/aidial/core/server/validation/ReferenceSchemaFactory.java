package com.epam.aidial.core.server.validation;

import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.resource.DisallowSchemaLoader;

/**
 * The schema factory of the dependency list: it refuses every {@code $ref} that is not local to the document, so that a schema cannot make
 * Core fetch anything.
 */
public final class ReferenceSchemaFactory {

    private ReferenceSchemaFactory() {
    }

    public static JsonSchemaFactory of(JsonMetaSchema metaSchema) {
        return JsonSchemaFactory.builder()
                .metaSchema(metaSchema)
                .defaultMetaSchemaIri(metaSchema.getIri())
                .schemaLoaders(loaders -> loaders.add(DisallowSchemaLoader.getInstance()))
                .build();
    }
}
