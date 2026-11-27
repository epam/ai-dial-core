package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class PlatformSchemaScanTest {

    private static final ResourceTypes SCHEMA = ResourceTypes.APP_TYPE_SCHEMA;
    private static final ResourceTypes CATALOG = ResourceTypes.CATALOG_SCHEMA;

    @Mock
    private ResourceService resourceService;

    private final AtomicInteger listings = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final Map<ResourceTypes, String> schemaIdOfType = Map.of(SCHEMA, "https://dial.example/app", CATALOG, "https://dial.example/catalog");
    private volatile CountDownLatch holdListing = new CountDownLatch(0);
    private final AtomicInteger failingListings = new AtomicInteger();

    @BeforeEach
    void setUp() {
        lenient().when(resourceService.getFolderMetadata(any(), any(), anyInt(), eq(true))).thenAnswer(invocation -> {
            listings.incrementAndGet();
            holdListing.await(10, TimeUnit.SECONDS);
            if (failingListings.getAndDecrement() > 0) {
                throw new IllegalStateException("the storage is down");
            }
            ResourceDescriptor folder = invocation.getArgument(0);
            return new ResourceFolderMetadata(folder, List.<MetadataBase>of(blobOf(folder)), null);
        });
        lenient().when(resourceService.getResource(any(ResourceDescriptor.class))).thenAnswer(invocation -> {
            reads.incrementAndGet();
            ResourceDescriptor blob = invocation.getArgument(0);
            return "{\"$id\":\"" + schemaIdOfType.get((ResourceTypes) blob.getType()) + "\"}";
        });
    }

    private static MetadataBase blobOf(ResourceDescriptor folder) {
        return new ResourceItemMetadata(ResourceDescriptorFactory.fromDecoded(
                (ResourceTypes) folder.getType(), ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "blob"));
    }

    @Test
    public void testSameConfigIsScannedOnce() {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);
        Config merged = new Config();

        Map<String, List<String>> first = scan.listBlobNamesBySchemaId(SCHEMA, merged);
        Map<String, List<String>> second = scan.listBlobNamesBySchemaId(SCHEMA, merged);

        assertEquals(Map.of("https://dial.example/app", List.of("blob")), first);
        assertSame(first, second);
        assertEquals(1, listings.get());
        assertEquals(1, reads.get());
    }

    @Test
    public void testAnotherConfigIsScannedAgain() {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);

        scan.listBlobNamesBySchemaId(SCHEMA, new Config());
        scan.listBlobNamesBySchemaId(SCHEMA, new Config());

        assertEquals(2, listings.get(), "a publication replaces the Config, so the next walk scans again");
    }

    @Test
    public void testEachSchemaTypeKeepsItsOwnScan() {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);
        Config merged = new Config();

        Map<String, List<String>> application = scan.listBlobNamesBySchemaId(SCHEMA, merged);
        Map<String, List<String>> catalog = scan.listBlobNamesBySchemaId(CATALOG, merged);
        scan.listBlobNamesBySchemaId(SCHEMA, merged);
        scan.listBlobNamesBySchemaId(CATALOG, merged);

        assertEquals(Map.of("https://dial.example/app", List.of("blob")), application);
        assertEquals(Map.of("https://dial.example/catalog", List.of("blob")), catalog);
        assertEquals(2, listings.get(), "one listing for each type");
    }

    @Test
    public void testWalksThatNeedTheSameScanWaitForOneOfThem() throws Exception {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);
        Config merged = new Config();
        holdListing = new CountDownLatch(1);
        int walks = 8;
        ExecutorService executor = Executors.newFixedThreadPool(walks);
        try {
            List<Future<Map<String, List<String>>>> results = new ArrayList<>();
            for (int i = 0; i < walks; i++) {
                results.add(executor.submit(() -> scan.listBlobNamesBySchemaId(SCHEMA, merged)));
            }
            while (listings.get() == 0) {
                Thread.sleep(5);
            }
            Thread.sleep(100);
            holdListing.countDown();

            Map<String, List<String>> first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<Map<String, List<String>>> result : results) {
                assertSame(first, result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            holdListing.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, listings.get(), "one scan for all the walks that were waiting");
    }

    @Test
    public void testWalksOfTwoPublicationsKeepTheirOwnScanAndDoNotReplaceEachOther() {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);
        Config older = new Config();
        Config newer = new Config();

        scan.listBlobNamesBySchemaId(SCHEMA, newer);
        scan.listBlobNamesBySchemaId(SCHEMA, older);
        scan.listBlobNamesBySchemaId(SCHEMA, newer);
        scan.listBlobNamesBySchemaId(SCHEMA, older);

        assertEquals(2, listings.get(), "a walk that holds the older Config does not push out the scan of the newer one");
        assertEquals(2, scan.countKeptScans());
        Reference.reachabilityFence(older);
        Reference.reachabilityFence(newer);
    }

    @Test
    public void testScanOfConfigThatNothingHoldsIsDropped() throws Exception {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);
        Config kept = new Config();
        scan.listBlobNamesBySchemaId(SCHEMA, kept);
        scanForConfigOfEndedWalk(scan);

        for (int i = 0; i < 50 && scan.countKeptScans() > 1; i++) {
            System.gc();
            Thread.sleep(50);
        }

        assertEquals(1, scan.countKeptScans(), "only the scan of the Config that is still held is kept");
        Reference.reachabilityFence(kept);
    }

    private void scanForConfigOfEndedWalk(PlatformSchemaScan scan) {
        scan.listBlobNamesBySchemaId(SCHEMA, new Config());
    }

    @Test
    public void testFailureOfTheScanIsPassedOnAsItIsAndIsNotKept() {
        PlatformSchemaScan scan = new PlatformSchemaScan(resourceService);
        Config merged = new Config();
        failingListings.set(1);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> scan.listBlobNamesBySchemaId(SCHEMA, merged));

        assertEquals("the storage is down", failure.getMessage());
        assertEquals(Map.of("https://dial.example/app", List.of("blob")), scan.listBlobNamesBySchemaId(SCHEMA, merged), "the next walk scans again");
        assertEquals(2, listings.get());
    }
}
