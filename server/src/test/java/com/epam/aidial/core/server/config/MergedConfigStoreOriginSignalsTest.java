package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A merged entity is the file {@code Config}'s own instance, and the merged schema maps hold the file's values, so a
 * comparison by identity against the file-sourced configuration tells a file entity from a blob override.
 */
public class MergedConfigStoreOriginSignalsTest extends MergedConfigStoreTestSupport {

    private static final String SCHEMA_ID = "https://example.test/schemas/guard";
    private static final String SCHEMA_BODY = "{\"$schema\":\"https://dial.epam.com/application_type_schemas/schema#\","
            + "\"$id\":\"" + SCHEMA_ID + "\","
            + "\"dial:applicationTypeEditorUrl\":\"http://editor.example\","
            + "\"dial:applicationTypeDisplayName\":\"Guard\","
            + "\"dial:applicationTypeCompletionEndpoint\":\"http://completion.example\"}";

    @Test
    public void testMergedSchemaMapsHoldTheFileValues() {
        Config file = new Config();
        file.setApplicationTypeSchemas(new LinkedHashMap<>(Map.of(SCHEMA_ID, SCHEMA_BODY)));
        fileConfigIs(file);
        stubBlobs();

        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        assertSame(SCHEMA_BODY, store.get().getApplicationTypeSchemas().get(SCHEMA_ID));
        assertSame(file.getApplicationTypeSchemas().get(SCHEMA_ID), store.get().getApplicationTypeSchemas().get(SCHEMA_ID));
    }
}
