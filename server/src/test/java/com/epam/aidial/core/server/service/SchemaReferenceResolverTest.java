package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.SchemaReferenceResolver.SchemaResolution;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Outcome;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SchemaReferenceResolver} on its own: a schema named by a fixed field is located by a scan of the platform schema blobs, with every
 * outcome of that lookup and a single listing per schema type. {@code DeploymentEdgeDiscoveryTest} shows the same outcomes as edge statuses.
 */
@ExtendWith(MockitoExtension.class)
public class SchemaReferenceResolverTest {


    private static final String ID = "https://dial.example/schemas/quickapps";
    private static final String OTHER_ID = "https://dial.example/schemas/other";
    private static final String CATALOG_ID = "https://dial.example/catalog/model";
    private static final ResourceTypes SCHEMA = ResourceTypes.APP_TYPE_SCHEMA;
    private static final ResourceTypes CATALOG = ResourceTypes.CATALOG_SCHEMA;

    @Mock
    private ResourceService resourceService;

    private final Config merged = new Config();
    private final Config file = new Config();
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalid = new HashMap<>();
    private final Map<ResourceTypes, List<Pair<ResourceItemMetadata, String>>> blobs = new HashMap<>();

    @BeforeEach
    void setUp() {
        merged.setApplicationTypeSchemas(new LinkedHashMap<>());
        merged.setCatalogSchemas(new LinkedHashMap<>());
        file.setApplicationTypeSchemas(new LinkedHashMap<>());
        file.setCatalogSchemas(new LinkedHashMap<>());
        lenient().when(resourceService.getFolderMetadata(any(), any(), anyInt(), eq(true))).thenAnswer(invocation -> {
            ResourceDescriptor folder = invocation.getArgument(0);
            List<MetadataBase> items = blobs.getOrDefault((ResourceTypes) folder.getType(), List.of()).stream()
                    .map(blob -> (MetadataBase) blob.getKey()).toList();
            return new ResourceFolderMetadata(folder, items, null);
        });
        lenient().when(resourceService.getResource(any(ResourceDescriptor.class))).thenAnswer(invocation -> {
            ResourceDescriptor descriptor = invocation.getArgument(0);
            return blobs.getOrDefault((ResourceTypes) descriptor.getType(), List.of()).stream()
                    .filter(blob -> blob.getKey().getName().equals(descriptor.getName())).findFirst().map(Pair::getValue).orElse(null);
        });
    }

    private SchemaReferenceResolver resolver() {
        return new SchemaReferenceResolver(merged, invalid, file, resourceService);
    }

    private static String body(String id) {
        return "{\"$id\":\"" + id + "\"}";
    }

    private void givenBlob(ResourceTypes type, String blobName, String body) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", blobName);
        blobs.computeIfAbsent(type, t -> new ArrayList<>()).add(Pair.of(new ResourceItemMetadata(descriptor), body));
    }

    private void givenMerged(ResourceTypes type, String id, String body) {
        MergedConfigStore.getSchemaMapOf(merged, type).put(id, body);
    }

    private void givenSkipped(ResourceTypes type, String blobName, String reason) {
        String canonicalId = MergedConfigStore.canonicalId(type, "platform", blobName);
        invalid.computeIfAbsent(type, t -> new HashMap<>()).put(canonicalId,
                new InvalidEntityRecord(blobName, canonicalId, reason, List.of(), "api", null));
    }

    @Test
    public void testPlatformSchemaIsLocatedByItsIdWhateverItsBlobIsNamed() {
        givenMerged(SCHEMA, ID, body(ID));
        givenBlob(SCHEMA, "folder/arbitrary-name", body(ID));

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(Origin.PLATFORM, resolution.origin());
        assertEquals("arbitrary-name", resolution.descriptor().getName());
        assertEquals("folder", resolution.descriptor().getParentPath(), "the blob keeps its path");
        assertEquals(SCHEMA, resolution.descriptor().getType());
        assertEquals("platform/", resolution.descriptor().getBucketLocation());
    }

    @Test
    public void testPlatformSchemaWinsOverFileSchemaOfTheSameId() {
        file.getApplicationTypeSchemas().put(ID, "{\"$id\":\"" + ID + "\",\"title\":\"from the file\"}");
        givenMerged(SCHEMA, ID, body(ID));
        givenBlob(SCHEMA, "b", body(ID));

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(Origin.PLATFORM, resolution.origin(), "the platform blob has the higher priority");
        assertEquals("b", resolution.descriptor().getName());
    }

    @Test
    public void testPlatformSchemaWinsWhenTheMergedBodyIsTheFileInstance() {
        String shared = body(ID);
        givenMerged(SCHEMA, ID, shared);
        file.getApplicationTypeSchemas().put(ID, shared);
        givenBlob(SCHEMA, "b", body(ID));

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(Origin.PLATFORM, resolution.origin());
        assertEquals("b", resolution.descriptor().getName());
    }

    @Test
    public void testFileSchemaWithoutPlatformBlobIsFile() {
        String shared = body(ID);
        givenMerged(SCHEMA, ID, shared);
        file.getApplicationTypeSchemas().put(ID, shared);

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(Origin.FILE, resolution.origin());
        assertNull(resolution.descriptor());
    }

    @Test
    public void testTwoValidBlobsWithOneIdBesideFileSchemaAreOriginUnknown() {
        String shared = body(ID);
        givenMerged(SCHEMA, ID, shared);
        file.getApplicationTypeSchemas().put(ID, shared);
        givenBlob(SCHEMA, "first", body(ID));
        givenBlob(SCHEMA, "second", body(ID));

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.ORIGIN_UNKNOWN, resolution.outcome());
        assertNull(resolution.origin());
    }

    @Test
    public void testFileSchemaNextToSkippedBlobIsFile() {
        String shared = body(ID);
        givenMerged(SCHEMA, ID, shared);
        file.getApplicationTypeSchemas().put(ID, shared);
        givenBlob(SCHEMA, "bad", body(ID));
        givenSkipped(SCHEMA, "bad", "invalid schema");

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(Origin.FILE, resolution.origin());
    }

    @Test
    public void testOnlySkippedBlobGivesSkipped() {
        givenBlob(SCHEMA, "dup", body(ID));
        givenSkipped(SCHEMA, "dup", "duplicate $id");

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.SKIPPED, resolution.outcome());
        assertNull(resolution.descriptor());
    }

    @Test
    public void testValidBlobAbsentFromTheMergedConfigurationIsOriginUnknownNotNotFound() {
        givenBlob(SCHEMA, "late", body(ID));

        assertEquals(Outcome.ORIGIN_UNKNOWN, resolver().resolve(SCHEMA, ID).outcome());
    }

    @Test
    public void testSchemaThatNothingHoldsIsNotFound() {
        givenBlob(SCHEMA, "other", body(OTHER_ID));

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.NOT_FOUND, resolution.outcome());
    }

    @Test
    public void testTwoValidBlobsWithOneIdAreOriginUnknownWhetherOrNotTheConfigurationHoldsIt() {
        givenBlob(SCHEMA, "first", body(ID));
        givenBlob(SCHEMA, "second", body(ID));

        assertEquals(Outcome.ORIGIN_UNKNOWN, resolver().resolve(SCHEMA, ID).outcome());

        givenMerged(SCHEMA, ID, body(ID));
        SchemaResolution held = resolver().resolve(SCHEMA, ID);
        assertEquals(Outcome.ORIGIN_UNKNOWN, held.outcome());
        assertNull(held.descriptor());
    }

    @Test
    public void testOneValidBlobBesideSkippedOneIsFoundAtTheValidBlob() {
        givenMerged(SCHEMA, ID, body(ID));
        givenBlob(SCHEMA, "good", body(ID));
        givenBlob(SCHEMA, "bad", body(ID));
        givenSkipped(SCHEMA, "bad", "invalid schema");

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals("good", resolution.descriptor().getName());
    }

    @Test
    public void testBlobsWithoutUsableIdAreIgnored() {
        givenBlob(SCHEMA, "not-json", "{broken");
        givenBlob(SCHEMA, "no-id", "{}");
        givenBlob(SCHEMA, "numeric-id", "{\"$id\":5}");
        blobs.get(SCHEMA).add(Pair.of(new ResourceItemMetadata(
                ResourceDescriptorFactory.fromDecoded(SCHEMA, "platform", "platform/", "no-body")), null));

        assertEquals(Outcome.NOT_FOUND, resolver().resolve(SCHEMA, ID).outcome());
    }

    @Test
    public void testEachSchemaTypeIsScannedOnceInThePlatformFolderWhateverTheNumberOfLookups() {
        givenMerged(SCHEMA, ID, body(ID));
        givenBlob(SCHEMA, "b", body(ID));
        givenMerged(CATALOG, CATALOG_ID, body(CATALOG_ID));
        givenBlob(CATALOG, "c", body(CATALOG_ID));
        SchemaReferenceResolver resolver = resolver();

        resolver.resolve(SCHEMA, ID);
        resolver.resolve(SCHEMA, ID);
        resolver.resolve(SCHEMA, OTHER_ID);
        resolver.resolve(CATALOG, CATALOG_ID);
        resolver.resolve(CATALOG, "https://dial.example/catalog/other");

        ArgumentCaptor<ResourceDescriptor> folders = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService, times(2)).getFolderMetadata(folders.capture(), any(), anyInt(), eq(true));
        assertEquals(List.of(SCHEMA, CATALOG), folders.getAllValues().stream().map(ResourceDescriptor::getType).toList());
        for (ResourceDescriptor folder : folders.getAllValues()) {
            assertTrue(folder.isFolder());
            assertEquals("platform", folder.getBucketName());
            assertEquals("platform/", folder.getBucketLocation());
        }
    }

    @Test
    public void testCatalogSchemaIsResolvedFromItsOwnMapAndItsOwnListing() {
        givenMerged(CATALOG, CATALOG_ID, body(CATALOG_ID));
        givenBlob(CATALOG, "catalog-blob", body(CATALOG_ID));
        givenBlob(SCHEMA, "catalog-blob", body(CATALOG_ID));

        SchemaResolution resolution = resolver().resolve(CATALOG, CATALOG_ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(CATALOG, resolution.descriptor().getType());
        assertEquals("catalog-blob", resolution.descriptor().getName());
    }

    @Test
    public void testSkippedRecordOfOneSchemaTypeDoesNotMarkTheBlobOfTheOther() {
        givenBlob(CATALOG, "same-name", body(CATALOG_ID));
        givenSkipped(SCHEMA, "same-name", "invalid schema");

        SchemaResolution resolution = resolver().resolve(CATALOG, CATALOG_ID);

        assertEquals(Outcome.ORIGIN_UNKNOWN, resolution.outcome(), "the blob is valid for its own type and not held by the configuration");
    }

    @Test
    public void testScanFollowsThePagesOfTheListing() {
        givenMerged(SCHEMA, ID, body(ID));
        givenBlob(SCHEMA, "first", body(OTHER_ID));
        givenBlob(SCHEMA, "second", body(ID));
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(SCHEMA, "platform", "platform/", "");
        MetadataBase first = blobs.get(SCHEMA).get(0).getKey();
        MetadataBase second = blobs.get(SCHEMA).get(1).getKey();
        when(resourceService.getFolderMetadata(any(), isNull(), anyInt(), eq(true)))
                .thenReturn(new ResourceFolderMetadata(folder, List.of(first), "next"));
        when(resourceService.getFolderMetadata(any(), eq("next"), anyInt(), eq(true)))
                .thenReturn(new ResourceFolderMetadata(folder, List.of(second), null));

        SchemaResolution resolution = resolver().resolve(SCHEMA, ID);

        assertEquals(Outcome.FOUND, resolution.outcome());
        assertEquals(Origin.PLATFORM, resolution.origin());
    }
}
