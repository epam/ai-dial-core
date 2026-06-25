package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStoreTest {

    @Mock
    private Vertx vertx;
    @Mock
    private AsyncTaskExecutor taskExecutor;
    @Mock
    private ResourceService resourceService;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private SecretFieldProcessor secretFieldProcessor;
    @Mock
    private FileConfigStore fileConfigStore;
    @Mock
    private LockService lockService;

    @BeforeEach
    public void setUpLockService() {
        // Pass-through mock: invoke the supplied action without any actual locking. Real distributed
        // serialization is covered by AdminReadSerializationTest; these unit tests assert orthogonal logic.
        lenient().when(lockService.underBucketLocks(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        // MergedConfigStore adopts ApiKeyStore's mutation lock; mock must supply a real one.
        lenient().when(apiKeyStore.getMutationLock()).thenReturn(new ReentrantLock());
    }

    @Test
    public void testRequestRebuildIsNoOpBeforeInit() {
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT);

        store.requestRebuild();
        store.requestRebuild();

        verify(vertx, never()).setTimer(anyLong(), any());
        verify(vertx, never()).cancelTimer(anyLong());
    }

    @Test
    public void testRebuildNowCancelsPendingTimer() {
        long sentinelTimerId = 42L;
        when(vertx.setTimer(anyLong(), any())).thenReturn(sentinelTimerId);
        when(fileConfigStore.get()).thenReturn(new Config());

        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT);
        store.init(fileConfigStore);

        store.requestRebuild();
        Config rebuilt = store.rebuildNow();

        verify(vertx, times(1)).cancelTimer(eq(sentinelTimerId));
        org.junit.jupiter.api.Assertions.assertNotNull(rebuilt);
    }

    @Test
    public void testListenerSelfEventIsSkipped() {
        when(fileConfigStore.get()).thenReturn(new Config());
        Consumer<ResourceEvent> listener = registerAndCaptureListener("pod-self");

        listener.accept(new ResourceEvent()
                .setUrl("models/platform/gpt-4")
                .setAction(ResourceEvent.Action.CREATE)
                .setSenderPodId("pod-self"));

        verify(vertx, never()).setTimer(anyLong(), any());
    }

    @Test
    public void testListenerOtherPodManagedTypeDispatchesReplicaApply() {
        when(fileConfigStore.get()).thenReturn(new Config());
        when(taskExecutor.submit(any())).thenReturn(Future.succeededFuture(null));
        Consumer<ResourceEvent> listener = registerAndCaptureListener("pod-self");

        listener.accept(new ResourceEvent()
                .setUrl("models/platform/gpt-4")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other"));

        verify(taskExecutor, times(1)).submit(any());
    }

    @Test
    public void testListenerOtherPodNonManagedTypeIsSkipped() {
        when(fileConfigStore.get()).thenReturn(new Config());
        Consumer<ResourceEvent> listener = registerAndCaptureListener("pod-self");

        listener.accept(new ResourceEvent()
                .setUrl("conversations/some-bucket/some-id")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other"));

        verify(vertx, never()).setTimer(anyLong(), any());
    }

    @Test
    public void testListenerMalformedUrlIsSkipped() {
        when(fileConfigStore.get()).thenReturn(new Config());
        Consumer<ResourceEvent> listener = registerAndCaptureListener("pod-self");

        listener.accept(new ResourceEvent()
                .setUrl("not-a-valid-url")
                .setAction(ResourceEvent.Action.CREATE)
                .setSenderPodId("pod-other"));

        verify(vertx, never()).setTimer(anyLong(), any());
    }

    @Test
    public void testListenerNullSenderPodIdTreatedAsForeign() {
        when(fileConfigStore.get()).thenReturn(new Config());
        when(taskExecutor.submit(any())).thenReturn(Future.succeededFuture(null));
        Consumer<ResourceEvent> listener = registerAndCaptureListener("pod-self");

        listener.accept(new ResourceEvent()
                .setUrl("interceptors/platform/foo")
                .setAction(ResourceEvent.Action.CREATE));

        verify(taskExecutor, times(1)).submit(any());
    }

    @SuppressWarnings("unchecked")
    private Consumer<ResourceEvent> registerAndCaptureListener(String thisPodId) {
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT, false, thisPodId);
        store.init(fileConfigStore);

        ArgumentCaptor<Consumer<ResourceEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(resourceService).subscribeAllResources(captor.capture());
        return captor.getValue();
    }
}
