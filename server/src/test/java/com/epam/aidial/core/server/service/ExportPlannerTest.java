package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.networknt.schema.JsonSchema;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ExportPlannerTest {

    @Mock
    private ResourceService resourceService;
    @Mock
    private ApplicationSchemaService applicationSchemaService;
    @Mock
    private CatalogSchemaService catalogSchemaService;
    @Mock
    private EncryptionService encryptionService;

    private final Config config = new Config();
    private final ReentrantLock lock = new ReentrantLock();
    private final List<String> reads = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        MarkerCollection none = new MarkerCollection(List.of(), List.of());
        lenient().when(applicationSchemaService.buildExportSchema(any(URI.class), any(Config.class)))
                .thenReturn(new ApplicationSchemaService.ExportSchema(mock(JsonSchema.class), null));
        lenient().when(applicationSchemaService.collectMarkerReferences(any(Application.class), any(JsonSchema.class))).thenReturn(none);
        lenient().when(catalogSchemaService.collectMarkerReferences(any(Application.class), any(Config.class))).thenReturn(none);
    }

    private ExportPlanner planner() {
        return planner(() -> {
            reads.add("config:" + lock.isHeldByCurrentThread());
            return config;
        });
    }

    /** The reads record whether the lock is held at the moment they are made. */
    private ExportPlanner planner(Supplier<Config> configSource) {
        return new ExportPlanner(lock,
                () -> {
                    reads.add("invalid:" + lock.isHeldByCurrentThread());
                    return Map.of();
                },
                configSource,
                () -> {
                    reads.add("file:" + lock.isHeldByCurrentThread());
                    return new Config();
                },
                resourceService, applicationSchemaService, catalogSchemaService, encryptionService, null, descriptor -> null);
    }

    private static ResourceDescriptor platformApplication(String name) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "platform", "platform/", name);
    }

    private static ResourceDescriptor publicApplication(String name) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "public", "public/", name);
    }

    private void givenStored(ResourceDescriptor descriptor, String body) {
        lenient().when(resourceService.getResourceWithMetadata(eq(descriptor), any())).thenReturn(Pair.of(new ResourceItemMetadata(), body));
    }

    private static ExportRequest request(ResourceDescriptor... roots) {
        return new ExportRequest(List.of(roots));
    }

    private Closure plan(ExportRequest request, Predicate<ResourceDescriptor> canReadPublic) {
        return planner().plan(request, canReadPublic, ExportLimits.defaults(), ExportLimits.Deadline.unbounded());
    }

    @Test
    public void testMissingRootIsNotFoundAndTheMessageNamesNoUrl() {
        ResourceDescriptor root = platformApplication("nobody-by-this-name");

        HttpException error = assertThrows(HttpException.class, () -> plan(request(root), descriptor -> true));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals(false, error.getMessage().contains("nobody-by-this-name"));
    }

    @Test
    public void testPublicRootThatIsNotReadableIsForbiddenBeforeAnythingIsRead() {
        ResourceDescriptor root = publicApplication("a__1.0.0");
        givenStored(root, "{\"endpoint\":\"a\"}");

        HttpException error = assertThrows(HttpException.class, () -> plan(request(root), descriptor -> false));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals(List.of(), reads, "nothing is read before the public root is authorized");
    }

    @Test
    public void testPlanWaitsForPublicationInProgress() throws Exception {
        ResourceDescriptor root = platformApplication("a");
        givenStored(root, "{\"endpoint\":\"a\"}");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } finally {
                lock.unlock();
            }
            return null;
        });
        assertTrue(held.await(10, TimeUnit.SECONDS));

        Future<Closure> plan = executor.submit(() -> plan(request(root), descriptor -> false));

        assertThrows(TimeoutException.class, () -> plan.get(300, TimeUnit.MILLISECONDS), "the pair is not read during a publication");
        release.countDown();
        assertEquals(1, plan.get(10, TimeUnit.SECONDS).nodes().size());
    }

    @Test
    public void testInterruptOfTheDeadlineIsGatewayTimeoutAndNotSchemaFailureOfTheRunner() {
        ResourceDescriptor root = platformApplication("a");
        givenStored(root, "{\"endpoint\":\"a\",\"application_type_schema_id\":\"https://dial.example/schemas/s\"}");
        when(applicationSchemaService.buildExportSchema(any(URI.class), any(Config.class))).thenAnswer(invocation -> {
            throw new InterruptedException("deadline");
        });
        ExportRequest request = request(root);

        HttpException failure = assertThrows(HttpException.class, () -> plan(request, descriptor -> true));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, failure.getStatus(), "the plan ends; no edge records the interrupt as a schema failure");
    }
}
