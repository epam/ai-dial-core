package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;

/**
 * Shared harness of the tests that drive a real {@link MergedConfigStore} over a mocked file store and a mocked {@link ResourceService}.
 * A blob listed through {@link #stubBlobs} is visible to the rebuild scan and to {@code hasResource}; a lag between the two is set up
 * with {@link #platformBlobExists}.
 */
@ExtendWith(MockitoExtension.class)
abstract class MergedConfigStoreTestSupport {

    static final String FILE_ENDPOINT = "http://file.example/v1";
    static final String BLOB_ENDPOINT = "http://blob.example/v1";

    @Mock
    protected Vertx vertx;
    @Mock
    protected AsyncTaskExecutor taskExecutor;
    @Mock
    protected ResourceService resourceService;
    @Mock
    protected ApiKeyStore apiKeyStore;
    @Mock
    protected SecretFieldProcessor secretFieldProcessor;
    @Mock
    protected FileConfigStore fileConfigStore;
    @Mock
    protected LockService lockService;
    @Mock
    protected ExternalServiceService externalServiceService;
    @Mock
    protected ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;

    protected ReentrantLock mutationLock;

    @BeforeEach
    void stubLockingAndExistence() {
        lenient().when(lockService.underBucketLocks(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        mutationLock = new ReentrantLock();
        lenient().when(apiKeyStore.getMutationLock()).thenReturn(mutationLock);
        lenient().when(resourceService.hasResource(any())).thenReturn(false);
    }

    protected void fileConfigIs(Config file) {
        lenient().when(fileConfigStore.get()).thenReturn(file);
    }

    protected static Config fileConfig(Model model, String name) {
        Config config = new Config();
        config.setModels(new LinkedHashMap<>(Map.of(name, model)));
        return config;
    }

    protected static Model model(String endpoint) {
        Model model = new Model();
        model.setEndpoint(endpoint);
        return model;
    }

    protected static Application appWithEndpoint(String endpoint) {
        Application application = new Application();
        application.setEndpoint(endpoint);
        return application;
    }

    protected static String modelBody(String endpoint) {
        return "{\"type\":\"chat\",\"endpoint\":\"" + endpoint + "\"}";
    }

    protected static String applicationBody(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\"}";
    }

    protected static String interceptorBody(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\"}";
    }

    protected record Blob(ResourceTypes type, String name, String body) {
    }

    protected static Blob blob(ResourceTypes type, String name, String body) {
        return new Blob(type, name, body);
    }

    protected static ResourceDescriptor platformDescriptor(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", name);
    }

    /** Makes the scan list exactly these platform blobs, and {@code hasResource} report each of them present. */
    protected void stubBlobs(Blob... blobs) {
        lenient().when(resourceService.listResources(any(), any())).thenReturn(List.of());
        Map<ResourceTypes, List<Pair<ResourceItemMetadata, String>>> byType = new LinkedHashMap<>();
        for (Blob blob : blobs) {
            byType.computeIfAbsent(blob.type(), t -> new ArrayList<>())
                    .add(Pair.of(new ResourceItemMetadata(platformDescriptor(blob.type(), blob.name())), blob.body()));
            platformBlobExists(blob.type(), blob.name(), true);
        }
        byType.forEach((type, items) -> lenient().when(resourceService.listResources(
                argThat(descriptor -> descriptor != null && descriptor.getType() == type
                        && "platform".equals(descriptor.getBucketName())),
                any())).thenReturn(items));
    }

    /** Sets what {@code hasResource} answers for one platform descriptor, independently of what the scan lists. */
    protected void platformBlobExists(ResourceTypes type, String name, boolean exists) {
        lenient().when(resourceService.hasResource(argThat(descriptor -> descriptor != null
                && descriptor.getType() == type
                && "platform".equals(descriptor.getBucketName())
                && name.equals(descriptor.getName())))).thenReturn(exists);
    }

    /** Makes {@code getResource} return this body for the platform descriptor, as a replica event handler reads it. */
    protected void platformBlobBodyIs(ResourceTypes type, String name, String body) {
        lenient().when(resourceService.getResource(argThat(descriptor -> descriptor != null
                && descriptor.getType() == type
                && "platform".equals(descriptor.getBucketName())
                && name.equals(descriptor.getName())))).thenReturn(body);
    }

    protected MergedConfigStore newStore(String mode) {
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, mode,
                externalServiceService, resourceAuthSettingsEncryptionService, mockBackgroundTracer());
        store.init(fileConfigStore);
        return store;
    }

    private static BackgroundOperationTracer mockBackgroundTracer() {
        BackgroundOperationTracer tracer = org.mockito.Mockito.mock(BackgroundOperationTracer.class);
        lenient().when(tracer.traceFuture(any())).thenAnswer(inv -> {
            Supplier<Future<?>> supplier = inv.getArgument(0);
            return supplier.get();
        });
        return tracer;
    }
}
