package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.CatalogSchemaValidationException;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.epam.aidial.core.server.validation.MarkerKind;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
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

    // ----------------------------------------------------------------------------------------------------------
    // Marker collection of a catalog Deployment: the shared collection algorithm is tested with ApplicationSchemaService, so only what is
    // specific to catalog schemas is tested here. They live in the config, so there is no schema cache and no remote download.

    private static Map<String, Object> properties(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    private static MarkerReference fileReference(String pointer, String value) {
        return new MarkerReference(MarkerKind.FILE, pointer, value);
    }

    private MarkerCollection collectFrom(String catalogSchema, Map<String, Object> catalogProperties) {
        when(configStore.get()).thenReturn(config);
        when(config.getCatalogSchema(SCHEMA_ID)).thenReturn(catalogSchema);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(catalogProperties);
        return service.collectMarkerReferences(model, configStore.get());
    }

    @Test
    void collectMarkerReferencesReturnsKindPointerAndValueOfEachFile() {
        String catalogSchema = """
                {"type":"object","properties":{
                  "badge":{"type":"string","dial:file":true},
                  "icon":{"type":"string","dial:file":true},
                  "tag":{"type":"string"}}}""";

        MarkerCollection result = collectFrom(catalogSchema,
                properties("badge", "files/public/b.png", "icon", "files/public/i.png", "tag", "Free"));

        Assertions.assertEquals(List.of(fileReference("/badge", "files/public/b.png"),
                fileReference("/icon", "files/public/i.png")), result.references());
        Assertions.assertTrue(result.issues().isEmpty());
        Assertions.assertTrue(result.isComplete());
    }

    @Test
    void collectMarkerReferencesReturnsEmptyCompleteCollectionWithoutSchemaIdOrProperties() {
        MarkerCollection withoutSchema = service.collectMarkerReferences(model, config);
        model.setCatalogSchemaId(SCHEMA_ID);
        MarkerCollection withoutProperties = service.collectMarkerReferences(model, config);

        for (MarkerCollection result : List.of(withoutSchema, withoutProperties)) {
            Assertions.assertTrue(result.references().isEmpty());
            Assertions.assertTrue(result.issues().isEmpty());
            Assertions.assertTrue(result.isComplete());
        }
        verifyNoInteractions(configStore);
    }

    @Test
    void collectMarkerReferencesReportsSchemaNotFoundWithoutThrowing() {
        when(configStore.get()).thenReturn(config);
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(properties("badge", "files/public/b.png"));

        MarkerCollection result = service.collectMarkerReferences(model, configStore.get());

        Assertions.assertTrue(result.references().isEmpty());
        Assertions.assertEquals(1, result.issues().size());
        Assertions.assertEquals(CollectionIssueKind.SCHEMA_NOT_FOUND, result.issues().get(0).kind());
        Assertions.assertFalse(result.isComplete());
    }

    @Test
    void collectMarkerReferencesPropagatesAnUnexpectedRuntimeExceptionInsteadOfRecordingAnIssue() {
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(properties("badge", "files/public/b.png"));
        Config failing = org.mockito.Mockito.mock(Config.class);
        when(failing.getCatalogSchema(SCHEMA_ID)).thenThrow(new IllegalStateException("boom"));

        IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                () -> service.collectMarkerReferences(model, failing));

        Assertions.assertEquals("boom", thrown.getMessage());
    }

    @Test
    void collectMarkerReferencesAcceptsLocalRefsOfSchemaWithAbsoluteId() {
        String catalogSchema = """
                {"$id":"https://dial.example/catalog-schemas/model","type":"object",
                 "properties":{"badge":{"$ref":"#/$defs/file"}},
                 "$defs":{"file":{"type":"string","dial:file":true}}}""";

        MarkerCollection result = collectFrom(catalogSchema, properties("badge", "files/public/b.png"));

        Assertions.assertEquals(List.of(fileReference("/badge", "files/public/b.png")), result.references());
        Assertions.assertTrue(result.isComplete());
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote", "local file"})
    void collectMarkerReferencesRefusesRefOutsideTheDocumentWithoutFetchingOrReadingIt(String kind) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ref.json", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"type\":\"string\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path file = Files.createTempFile("ref", ".json");
        try {
            Files.writeString(file, "{\"type\":\"string\"}");
            String address = kind.equals("remote") ? "http://127.0.0.1:" + server.getAddress().getPort() + "/ref.json" : file.toUri().toString();
            String catalogSchema = "{\"type\":\"object\",\"properties\":{\"badge\":{\"$ref\":\"" + address + "\"}}}";

            MarkerCollection result = collectFrom(catalogSchema, properties("badge", "files/public/b.png"));

            Assertions.assertEquals(0, requests.get(), "a schema document must not make Core send a request");
            Assertions.assertTrue(result.references().isEmpty());
            Assertions.assertEquals(CollectionIssueKind.SCHEMA_UNAVAILABLE, result.issues().get(0).kind());
            Assertions.assertFalse(result.issues().get(0).message().contains(address));
            Assertions.assertFalse(result.isComplete());
        } finally {
            server.stop(0);
            Files.deleteIfExists(file);
        }
    }

    @Test
    void collectMarkerReferencesWithGivenConfigReadsTheSchemaFromThatConfigAndNotFromTheStore() {
        Config snapshotConfig = org.mockito.Mockito.mock(Config.class);
        when(snapshotConfig.getCatalogSchema(SCHEMA_ID)).thenReturn("""
                {"type":"object","properties":{"badge":{"type":"string","dial:file":true}}}""");
        model.setCatalogSchemaId(SCHEMA_ID);
        model.setCatalogProperties(properties("badge", "files/public/b.png"));

        MarkerCollection result = service.collectMarkerReferences(model, snapshotConfig);

        Assertions.assertEquals(List.of(new MarkerReference(MarkerKind.FILE, "/badge", "files/public/b.png")), result.references());
        Assertions.assertTrue(result.isComplete());
        verifyNoInteractions(configStore);
    }

}
