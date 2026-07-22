package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.CatalogSchemaValidationException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogSchemaServiceTest {

    private static final URI SCHEMA_ID = URI.create("https://dial.epam.com/catalog-schemas/model");

    private final String schema = """
            {
              "$schema": "https://dial.epam.com/catalog_schemas/schema#",
              "$id": "https://dial.epam.com/catalog-schemas/model",
              "dial:catalogEntityType": "model",
              "dial:catalogDisplayName": "Model",
              "dial:defaultLocale": "en",
              "type": "object",
              "properties": {
                "badge": {
                  "type": "string",
                  "format": "dial-file-encoded",
                  "dial:file": true
                },
                "tag": {
                  "type": "string",
                  "enum": ["Free", "Featured"]
                },
                "about": {
                  "type": "string",
                  "dial:meta": { "dial:localized": true }
                }
              }
            }""";

    @Mock
    private Config config;
    @Mock
    private ConfigStore configStore;
    @Mock
    private ResourceService resourceService;
    @Mock
    private EncryptionService encryptionService;

    private CatalogSchemaService service;
    private Model model;

    @BeforeEach
    void setUp() {
        service = new CatalogSchemaService(resourceService, configStore, encryptionService);
        model = new Model();
    }

    @Test
    void validateIsNoOpWhenSchemaIdIsNull() {
        Assertions.assertDoesNotThrow(() -> service.validate(model));
    }

    @Test
    void validateIsNoOpWhenCatalogPropertiesIsNull() {
        model.setCatalogSchemaId(SCHEMA_ID);
        Assertions.assertDoesNotThrow(() -> service.validate(model));
    }

    @Test
    void validateThrowsWhenSchemaNotFound() {
        when(configStore.get()).thenReturn(config);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(Map.of("tag", "Featured"));

        Assertions.assertThrows(CatalogSchemaValidationException.class, () -> service.validate(model));
    }

    @Test
    void validatePassesForConformingProperties() {
        when(configStore.get()).thenReturn(config);
        when(config.getCatalogSchema(SCHEMA_ID)).thenReturn(schema);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(Map.of("tag", "Featured"));

        Assertions.assertDoesNotThrow(() -> service.validate(model));
    }

    @Test
    void validateThrowsForNonConformingProperties() {
        when(configStore.get()).thenReturn(config);
        when(config.getCatalogSchema(SCHEMA_ID)).thenReturn(schema);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(Map.of("tag", "NotAllowed"));

        Assertions.assertThrows(CatalogSchemaValidationException.class, () -> service.validate(model));
    }

    @Test
    void validateThrowsWhenLocalizedFieldMapMissingDefaultLocale() {
        when(configStore.get()).thenReturn(config);
        when(config.getCatalogSchema(SCHEMA_ID)).thenReturn(schema);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(Map.of("about", Map.of("de", "Modell")));

        Assertions.assertThrows(CatalogSchemaValidationException.class, () -> service.validate(model));
    }

    @Test
    void validatePassesWhenLocalizedFieldIsPlainString() {
        when(configStore.get()).thenReturn(config);
        when(config.getCatalogSchema(SCHEMA_ID)).thenReturn(schema);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(Map.of("about", "A model."));

        Assertions.assertDoesNotThrow(() -> service.validate(model));
    }

    @Test
    void getFilesReturnsEmptyListWhenSchemaIdIsNull() {
        Assertions.assertTrue(service.getFiles(model).isEmpty());
    }

    @Test
    void getFilesReturnsCollectedFiles() {
        when(configStore.get()).thenReturn(config);
        when(config.getCatalogSchema(SCHEMA_ID)).thenReturn(schema);
        when(resourceService.hasResource(any())).thenReturn(true);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(Map.of("badge", "files/public/valid-file-path/badge.png"));

        List<ResourceDescriptor> result = service.getFiles(model);

        Assertions.assertEquals(1, result.size());
    }
}
