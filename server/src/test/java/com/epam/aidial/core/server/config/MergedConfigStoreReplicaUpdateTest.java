package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStoreReplicaUpdateTest {

    private static final String POD_ID = "pod-this";
    private static final String KEY_ID = "keys/platform/proj-a";
    private static final String MODEL_ID = "models/public/gpt-4";
    private static final String SETTINGS_ID = "settings/platform/global";
    private static final String KEY_JSON =
            "{\"project\":\"proj-a\",\"role\":\"admin-role\",\"key\":\"secret-A\"}";
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

    @BeforeEach
    public void setUpLockService() {
        lenient().when(lockService.underBucketLocks(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
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
    public void projectKeyCreateFetchesDecryptsAndUpdatesKeyStoreBeforeApply() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Mockito.reset(apiKeyStore);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(KEY_ID, null);
        when(resourceService.getResource(descriptor)).thenReturn(KEY_JSON);

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.CREATE);

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

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE);

        assertTrue(store.get().getKeys().containsKey(KEY_ID));
        verify(apiKeyStore).addOrUpdateKey(eq("secret-A"), any(ApiKeyData.class));
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
        store.applyReplicaEvent(descriptor, ResourceEvent.Action.DELETE);

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

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE);

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
        store.applyReplicaEvent(descriptor, ResourceEvent.Action.DELETE);

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

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.CREATE);

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

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.CREATE);

        Model applied = store.get().getModels().get(MODEL_ID);
        assertEquals("GPT-4", applied.getDisplayName());
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

        store.applyReplicaEvent(descriptor, ResourceEvent.Action.UPDATE);

        // requestRebuild() schedules via vertx.setTimer
        verify(vertx, atLeastOnce()).setTimer(anyLong(), any());
    }

    private MergedConfigStore initStore(Config seeded, String onInvalidEntity) {
        when(fileConfigStore.get()).thenReturn(seeded);
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, onInvalidEntity, false, POD_ID);
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
