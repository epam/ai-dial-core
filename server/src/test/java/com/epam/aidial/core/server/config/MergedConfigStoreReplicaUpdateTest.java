package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStoreReplicaUpdateTest {

    private static final String POD_ID = "pod-this";
    private static final String KEY_ID = "keys/platform/proj-a";
    private static final String MODEL_ID = "models/platform/gpt-4";
    private static final String SETTINGS_ID = "settings/platform/global";
    private static final String KEY_JSON =
            "{\"project\":\"proj-a\",\"role\":\"admin-role\",\"key\":\"secret-A\"}";
    private static final String KEY_JSON_NEW_SECRET =
            "{\"project\":\"proj-a\",\"role\":\"admin-role\",\"key\":\"secret-NEW\"}";
    private static final String SETTINGS_JSON =
            "{\"globalInterceptors\":[\"api-overlay\"],\"retriableErrorCodes\":[503]}";

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
    @Mock
    private ExternalServiceService externalServiceService;
    @Mock
    private ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;

    // The real ReentrantLock the store adopts as its rebuildLock (== ApiKeyStore's mutationLock).
    // Captured here so lock-held-invariant tests can assert it is held at the mutation call sites.
    private ReentrantLock mutationLock;

    @BeforeEach
    public void setUpLockService() {
        lenient().when(lockService.underBucketLocks(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        // MergedConfigStore adopts ApiKeyStore's mutation lock; mock must supply a real one.
        mutationLock = new ReentrantLock();
        lenient().when(apiKeyStore.getMutationLock()).thenReturn(mutationLock);
    }

    @Test
    public void selfPodEventIsFiltered() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(taskExecutor, resourceService);

        ResourceEvent event = new ResourceEvent()
                .setUrl(KEY_ID)
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId(POD_ID);
        invokeOnResourceEvent(store, event);

        verifyNoInteractions(taskExecutor);
        verifyNoInteractions(resourceService);
    }

    @Test
    public void nonManagedFirehoseEventShortCircuitsBeforeParse() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(taskExecutor, resourceService);

        ResourceEvent event = new ResourceEvent()
                .setUrl("conversations/someEncryptedBucket/folder/conv-1")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other");
        invokeOnResourceEvent(store, event);

        verifyNoInteractions(taskExecutor);
        verifyNoInteractions(resourceService);
    }

    @Test
    public void publicBucketApplicationEventIsFilteredByBucket() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(taskExecutor, resourceService);

        // APPLICATION is a MANAGED_TYPE and fromAnyUrl resolves the public bucket, so this passes the
        // type filter; rebuild() only scans platform, so the bucket scope must drop the event on peer
        // pods rather than materialize a public app into the merged Config.
        ResourceEvent event = new ResourceEvent()
                .setUrl("applications/public/my-app")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other");
        invokeOnResourceEvent(store, event);

        verifyNoInteractions(taskExecutor);
        verifyNoInteractions(resourceService);
    }

    @Test
    public void publicBucketToolSetEventIsFilteredByBucket() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(taskExecutor, resourceService);

        ResourceEvent event = new ResourceEvent()
                .setUrl("toolsets/public/my-toolset")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other");
        invokeOnResourceEvent(store, event);

        verifyNoInteractions(taskExecutor);
        verifyNoInteractions(resourceService);
    }

    @Test
    public void platformBucketApplicationEventIsDispatched() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(taskExecutor, resourceService);
        when(taskExecutor.submit(any())).thenReturn(Future.succeededFuture());

        // Positive control: a platform-bucket app event passes the bucket scope and is dispatched,
        // proving the filter does not over-drop legitimate managed events.
        ResourceEvent event = new ResourceEvent()
                .setUrl("applications/platform/my-app")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other");
        invokeOnResourceEvent(store, event);

        verify(taskExecutor).submit(any());
    }

    @Test
    public void platformBucketToolSetEventIsDispatched() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(taskExecutor, resourceService);
        when(taskExecutor.submit(any())).thenReturn(Future.succeededFuture());

        // Positive control: a platform-bucket toolset event passes the bucket scope and is dispatched.
        ResourceEvent event = new ResourceEvent()
                .setUrl("toolsets/platform/my-toolset")
                .setAction(ResourceEvent.Action.UPDATE)
                .setSenderPodId("pod-other");
        invokeOnResourceEvent(store, event);

        verify(taskExecutor).submit(any());
    }

    @Test
    public void isManagedEventUrlMatchesManagedSegments() {
        assertTrue(MergedConfigStore.isManagedEventUrl("models/platform/gpt-4"));
        assertTrue(MergedConfigStore.isManagedEventUrl("schemas/platform/s-1"));
        assertTrue(MergedConfigStore.isManagedEventUrl("interceptors/public/i-1"));
        assertTrue(MergedConfigStore.isManagedEventUrl("roles/public/r-1"));
        assertTrue(MergedConfigStore.isManagedEventUrl("keys/platform/proj-a"));
        assertTrue(MergedConfigStore.isManagedEventUrl("routes/public/rt-1"));
        assertTrue(MergedConfigStore.isManagedEventUrl("settings/platform/global"));

        assertFalse(MergedConfigStore.isManagedEventUrl("conversations/x"));
        assertFalse(MergedConfigStore.isManagedEventUrl("prompts/x"));
        assertFalse(MergedConfigStore.isManagedEventUrl("files/x"));
        assertFalse(MergedConfigStore.isManagedEventUrl(null));
        assertFalse(MergedConfigStore.isManagedEventUrl(""));
        assertFalse(MergedConfigStore.isManagedEventUrl("noslash"));
        assertFalse(MergedConfigStore.isManagedEventUrl("/models/x"));
    }

    @Test
    public void projectKeyCreateFetchesDecryptsAndUpdatesKeyStoreBeforeApply() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(KEY_JSON);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.CREATE, null);

        Key applied = store.get().getKeys().get(KEY_ID);
        assertEquals("secret-A", applied.getKey());
        ArgumentCaptor<ApiKeyData> dataCaptor = ArgumentCaptor.forClass(ApiKeyData.class);
        InOrder inOrder = Mockito.inOrder(apiKeyStore, secretFieldProcessor);
        inOrder.verify(secretFieldProcessor).decryptFields(any(Key.class), eq(descriptor));
        inOrder.verify(apiKeyStore).addOrUpdateKey(eq("secret-A"), dataCaptor.capture());
        assertEquals("secret-A", dataCaptor.getValue().getOriginalKey().getKey());
    }

    @Test
    public void projectKeyUpdateBehavesAsCreate() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(KEY_JSON);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE, null);

        assertTrue(store.get().getKeys().containsKey(KEY_ID));
        verify(apiKeyStore).addOrUpdateKey(eq("secret-A"), any(ApiKeyData.class));
    }

    @Test
    public void projectKeyRotationViaReplicaRemovesOldSecret() {
        Key existing = new Key();
        existing.setProject("proj-a");
        existing.setKey("secret-OLD");
        Config seeded = newConfig();
        seeded.setKeys(new HashMap<>(java.util.Map.of(KEY_ID, existing)));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore, resourceService);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(KEY_JSON_NEW_SECRET);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE, null);

        verify(apiKeyStore).addOrUpdateKey(eq("secret-NEW"), any(ApiKeyData.class));
        verify(apiKeyStore).removeKey("secret-OLD");
    }

    @Test
    public void projectKeyReplicaSameSecretDoesNotRemove() {
        Key existing = new Key();
        existing.setProject("proj-a");
        existing.setKey("secret-A");
        Config seeded = newConfig();
        seeded.setKeys(new HashMap<>(java.util.Map.of(KEY_ID, existing)));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore, resourceService);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(KEY_JSON);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE, null);

        verify(apiKeyStore).addOrUpdateKey(eq("secret-A"), any(ApiKeyData.class));
        verify(apiKeyStore, never()).removeKey(any());
    }

    @Test
    public void projectKeyDeleteSnapshotsSecretAndRemovesFromKeyStore() {
        Key existing = new Key();
        existing.setProject("proj-a");
        existing.setKey("secret-A");
        Config seeded = newConfig();
        seeded.setKeys(new HashMap<>(java.util.Map.of(KEY_ID, existing)));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore, resourceService);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        store.applyReplicaEvent(descriptor, ResourceEvent.Action.DELETE, null);

        verify(apiKeyStore).removeKey("secret-A");
        assertFalse(store.get().getKeys().containsKey(KEY_ID));
        verify(resourceService, never()).getResource(any(ResourceDescriptor.class));
    }

    @Test
    public void globalSettingsUpdateAppliesSettingsWrite() {
        Config seeded = newConfig();
        seeded.setGlobalInterceptors(List.of("file-default"));
        seeded.setRetriableErrorCodes(Set.of(500));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(SETTINGS_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(SETTINGS_JSON);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE, null);

        assertEquals(List.of("api-overlay"), store.get().getGlobalInterceptors());
        assertEquals(Set.of(503), store.get().getRetriableErrorCodes());
        assertTrue(store.isSettingsFromApi());
    }

    @Test
    public void globalSettingsDeleteAppliesSettingsDelete() {
        Config seeded = newConfig();
        seeded.setGlobalInterceptors(List.of("file-default"));
        seeded.setRetriableErrorCodes(Set.of(500));
        when(fileConfigStore.get()).thenReturn(seeded);
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);

        // First overlay so the delete has something to revert
        GlobalSettings overlay = new GlobalSettings();
        overlay.setGlobalInterceptors(List.of("api-overlay"));
        overlay.setRetriableErrorCodes(Set.of(503));
        store.applySettingsWrite(overlay);
        Mockito.reset(resourceService);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(SETTINGS_ID, null);
        store.applyReplicaEvent(descriptor, ResourceEvent.Action.DELETE, null);

        assertEquals(List.of("file-default"), store.get().getGlobalInterceptors());
        assertEquals(Set.of(500), store.get().getRetriableErrorCodes());
        assertFalse(store.isSettingsFromApi());
        verify(resourceService, never()).getResource(any(ResourceDescriptor.class));
    }

    @Test
    public void createWithNullFetchTreatedAsDelete() {
        Key existing = new Key();
        existing.setProject("proj-a");
        existing.setKey("secret-A");
        Config seeded = newConfig();
        seeded.setKeys(new HashMap<>(java.util.Map.of(KEY_ID, existing)));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(null);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.CREATE, null);

        assertFalse(store.get().getKeys().containsKey(KEY_ID));
        verify(apiKeyStore).removeKey("secret-A");
        verify(apiKeyStore, never()).addOrUpdateKey(any(), any());
    }

    @Test
    public void modelCreateFetchesDecryptsAndAppliesEntity() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(MODEL_ID, null);
        when(resourceService.getResource(descriptor))
                .thenReturn("{\"displayName\":\"GPT-4\",\"type\":\"chat\"}");

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.CREATE, null);

        Model applied = store.get().getModels().get(descriptor.getName());
        assertEquals("GPT-4", applied.getDisplayName().getPlainValue());
        verify(secretFieldProcessor).decryptFields(any(Model.class), eq(descriptor));
        verifyNoInteractions(apiKeyStore);
    }

    @Test
    public void fetchExceptionFallsBackToRequestRebuild() {
        MergedConfigStore store = initStore(newConfig(), MergedConfigStore.MODE_ABORT);
        Mockito.reset(vertx);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor))
                .thenThrow(new RuntimeException("blob storage offline"));

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE, null);

        // requestRebuild() schedules via vertx.setTimer
        verify(vertx, atLeastOnce()).setTimer(anyLong(), any());
    }

    @Test
    public void replicaRotationHoldsRebuildLockAcrossSnapshotAndMutations() {
        Key existing = new Key();
        existing.setProject("proj-a");
        existing.setKey("secret-OLD");
        Config seeded = newConfig();
        seeded.setKeys(new HashMap<>(java.util.Map.of(KEY_ID, existing)));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore, resourceService);

        boolean[] heldOnAdd = {false};
        boolean[] heldOnRemove = {false};
        // The store mutates a Mockito mock here, so these doAnswers run at the call site INSIDE
        // applyReplicaEvent — not inside any real ApiKeyStore lock acquisition. On the fixed code
        // the surrounding rebuildLock critical section means the lock is held by this thread; on the
        // unfixed code (no outer critical section, only the inner per-method locks which this mock
        // skips) it is not, turning the test red.
        doAnswer(inv -> {
            heldOnAdd[0] = mutationLock.isHeldByCurrentThread();
            return null;
        }).when(apiKeyStore).addOrUpdateKey(eq("secret-NEW"), any(ApiKeyData.class));
        doAnswer(inv -> {
            heldOnRemove[0] = mutationLock.isHeldByCurrentThread();
            return null;
        }).when(apiKeyStore).removeKey("secret-OLD");

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(KEY_JSON_NEW_SECRET);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE, null);

        assertTrue(heldOnAdd[0], "rebuildLock must be held by current thread during addOrUpdateKey");
        assertTrue(heldOnRemove[0], "rebuildLock must be held by current thread during removeKey");
        // Lock must be fully released once the critical section completes.
        assertFalse(mutationLock.isLocked(), "rebuildLock must be released after applyReplicaEvent");
    }

    @Test
    public void replicaDeleteHoldsRebuildLockAcrossSnapshotAndRemoveKey() {
        Key existing = new Key();
        existing.setProject("proj-a");
        existing.setKey("secret-A");
        Config seeded = newConfig();
        seeded.setKeys(new HashMap<>(java.util.Map.of(KEY_ID, existing)));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore, resourceService);

        boolean[] heldOnRemove = {false};
        doAnswer(inv -> {
            heldOnRemove[0] = mutationLock.isHeldByCurrentThread();
            return null;
        }).when(apiKeyStore).removeKey("secret-A");

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        store.applyReplicaEvent(descriptor, ResourceEvent.Action.DELETE, null);

        assertTrue(heldOnRemove[0], "rebuildLock must be held by current thread during removeKey");
        assertFalse(mutationLock.isLocked(), "rebuildLock must be released after applyReplicaDelete");
        verify(resourceService, never()).getResource(any(ResourceDescriptor.class));
    }

    private MergedConfigStore initStore(Config seeded, String onInvalidEntity) {
        when(fileConfigStore.get()).thenReturn(seeded);
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, onInvalidEntity, false, POD_ID,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);
        return store;
    }

    private static Config newConfig() {
        Config config = new Config();
        config.setModels(new LinkedHashMap<>());
        config.setInterceptors(new LinkedHashMap<>());
        config.setRoles(new HashMap<>());
        config.setKeys(new HashMap<>());
        config.setRoutes(new LinkedHashMap<>());
        config.setApplicationTypeSchemas(new LinkedHashMap<>());
        config.setApplications(new HashMap<>());
        config.setToolsets(new LinkedHashMap<>());
        return config;
    }

    private static void invokeOnResourceEvent(MergedConfigStore store, ResourceEvent event) {
        try {
            Method m = MergedConfigStore.class.getDeclaredMethod("onResourceEvent", ResourceEvent.class);
            m.setAccessible(true);
            m.invoke(store, event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke onResourceEvent for test", e);
        }
    }
}
