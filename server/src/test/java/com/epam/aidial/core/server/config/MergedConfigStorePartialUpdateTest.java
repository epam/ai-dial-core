package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStorePartialUpdateTest {

    private static final String MODEL_ID = "models/platform/gpt-4";
    private static final String INTERCEPTOR_ID = "interceptors/platform/chain-a";
    private static final String ROLE_ID = "roles/platform/admin";
    private static final String KEY_ID = "keys/platform/proj-a";

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

    @BeforeEach
    public void setUpLockService() {
        lenient().when(lockService.underBucketLocks(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        // MergedConfigStore adopts ApiKeyStore's mutation lock; mock must supply a real one.
        lenient().when(apiKeyStore.getMutationLock()).thenReturn(new ReentrantLock());
    }

    @Test
    public void interceptorDeleteCascadesToModelInvalidEntities() {
        Model model = new Model();
        model.setInterceptors(List.of(INTERCEPTOR_ID));
        Interceptor interceptor = new Interceptor();
        Config seeded = newConfig();
        seeded.setModels(mutable(MODEL_ID, model));
        seeded.setInterceptors(mutable(INTERCEPTOR_ID, interceptor));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_SKIP);

        Config result = store.applyEntityDelete(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID);

        assertFalse(result.getInterceptors().containsKey(INTERCEPTOR_ID), "interceptor removed");
        assertFalse(result.getModels().containsKey(MODEL_ID), "model cascade-removed from Config");
        Map<String, InvalidEntityRecord> invalidModels = store.getInvalidEntities().get(ResourceTypes.MODEL);
        assertEquals(1, invalidModels.size(), "model recorded in invalidEntities sibling store");
        assertTrue(invalidModels.containsKey(MODEL_ID));
    }

    @Test
    public void cascadeClassifiesInvalidatedModelsAsApiSourced() {
        // Models are keyed by short name uniformly now (file- and blob-sourced alike), so key
        // shape can no longer tell them apart. The partial-update path never touches file config
        // either way, so every survivor this cascade walks is classified "api" — regardless of
        // which source the model itself originally came from.
        Model firstModel = new Model();
        firstModel.setInterceptors(List.of(INTERCEPTOR_ID));
        Model secondModel = new Model();
        secondModel.setInterceptors(List.of(INTERCEPTOR_ID));
        Config seeded = newConfig();
        Map<String, Model> models = new LinkedHashMap<>();
        models.put("gpt-4-first", firstModel);
        models.put("gpt-4-second", secondModel);
        seeded.setModels(models);
        seeded.setInterceptors(mutable(INTERCEPTOR_ID, new Interceptor()));
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_SKIP);

        store.applyEntityDelete(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID);

        Map<String, InvalidEntityRecord> invalidModels = store.getInvalidEntities().get(ResourceTypes.MODEL);
        assertEquals(2, invalidModels.size(), "both cross-ref-invalidated models recorded");

        // invalidEntities is always keyed by (derived) canonical id, regardless of source —
        // unaffected by short-name keying, which only concerns Config's live entity maps.
        InvalidEntityRecord firstRecord = invalidModels.get("models/platform/gpt-4-first");
        assertEquals("api", firstRecord.getSource());
        assertEquals("gpt-4-first", firstRecord.getSimpleName());

        InvalidEntityRecord secondRecord = invalidModels.get("models/platform/gpt-4-second");
        assertEquals("api", secondRecord.getSource());
        assertEquals("gpt-4-second", secondRecord.getSimpleName());
    }

    @Test
    public void interceptorWriteResurrectsPreviouslySkippedModel() {
        // Seed: a model that references a missing interceptor sits in invalidEntities (validation skipped).
        JsonNode modelPayload = JsonNodeFactory.instance.objectNode()
                .set("interceptors", JsonNodeFactory.instance.arrayNode().add(INTERCEPTOR_ID));
        InvalidEntityRecord record = new InvalidEntityRecord(
                "gpt-4", MODEL_ID, "missing interceptor",
                List.of(new ValidationWarning("interceptors[0]", "missing")),
                "api", modelPayload);
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidSeed = new HashMap<>();
        Map<String, InvalidEntityRecord> modelInvalid = new HashMap<>();
        modelInvalid.put(MODEL_ID, record);
        invalidSeed.put(ResourceTypes.MODEL, modelInvalid);

        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_SKIP);
        seedInvalidEntities(store, invalidSeed);

        // Decryption is a no-op for this test — model has no @EncryptedField values set.
        Config result = store.applyEntityWrite(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID, new Interceptor());

        assertTrue(result.getInterceptors().containsKey(INTERCEPTOR_ID), "interceptor present");
        assertTrue(result.getModels().containsKey(MODEL_ID), "model resurrected to Config.models");
        assertNull(store.getInvalidEntities().get(ResourceTypes.MODEL),
                "invalidEntities cleared for MODEL after successful resurrection");
    }

    @Test
    public void projectKeyWriteDoesNotTouchApiKeyStore() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        // init() already calls addProjectKeys via processSemantic — reset so the assertion below
        // covers only what applyEntityWrite triggers post-init.
        org.mockito.Mockito.reset(apiKeyStore);

        Key key = new Key();
        key.setProject("proj-a");
        key.setKey("secret-A");
        Config result = store.applyEntityWrite(ResourceTypes.PROJECT_KEY, KEY_ID, key);

        assertSame(key, result.getKeys().get(KEY_ID));
        verifyNoInteractions(apiKeyStore); // controller fast-paths ApiKeyStore — store must not touch it.
    }

    @Test
    public void routeWriteSortsRoutesByOrder() {
        Route first = new Route();
        first.setOrder(10);
        Route second = new Route();
        second.setOrder(20);
        Config seeded = newConfig();
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("routes/platform/second", second);
        routes.put("routes/platform/first", first);
        seeded.setRoutes(routes);
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);

        Route inserted = new Route();
        inserted.setOrder(5);
        Config result = store.applyEntityWrite(ResourceTypes.ROUTE, "routes/platform/inserted", inserted);

        List<String> orderedKeys = new ArrayList<>(result.getRoutes().keySet());
        assertEquals(List.of("routes/platform/inserted", "routes/platform/first", "routes/platform/second"),
                orderedKeys, "routes resorted by ascending Route.order (5, 10, 20)");
    }

    @Test
    public void applyBatchAccumulatesFailures() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);

        // Second entry has a type/entity mismatch (Role passed under MODEL type) — putEntity ClassCast.
        List<EntityChange> changes = List.of(
                new EntityChange(ResourceTypes.MODEL, MODEL_ID, new Model()),
                new EntityChange(ResourceTypes.MODEL, "models/platform/bad", new Role()));

        Map<String, String> failures = store.applyBatch(changes);

        assertEquals(1, failures.size(), "one failure recorded");
        assertTrue(failures.containsKey("models/platform/bad"));
        assertTrue(store.get().getModels().containsKey(MODEL_ID), "first entry applied despite second failing");
    }

    @Test
    public void settingsWriteOverlaysAndDeleteRestores() {
        Config seeded = newConfig();
        seeded.setGlobalInterceptors(List.of("file-default"));
        seeded.setRetriableErrorCodes(Set.of(500));
        when(fileConfigStore.get()).thenReturn(seeded);
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);

        GlobalSettings overlay = new GlobalSettings();
        overlay.setGlobalInterceptors(List.of("api-overlay"));
        overlay.setRetriableErrorCodes(Set.of(503));
        store.applySettingsWrite(overlay);
        assertEquals(List.of("api-overlay"), store.get().getGlobalInterceptors());
        assertEquals(Set.of(503), store.get().getRetriableErrorCodes());
        assertTrue(store.isSettingsFromApi());

        store.applySettingsDelete();
        assertEquals(List.of("file-default"), store.get().getGlobalInterceptors());
        assertEquals(Set.of(500), store.get().getRetriableErrorCodes());
        assertFalse(store.isSettingsFromApi());
    }

    @Test
    public void applyEntityWriteSwapsConfigReference() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, MergedConfigStore.MODE_ABORT);
        Config before = store.get();

        store.applyEntityWrite(ResourceTypes.ROLE, ROLE_ID, new Role());

        assertNotSame(before, store.get(), "volatile Config reference swapped");
        assertSame(before.getApplications(), store.get().getApplications(),
                "untouched maps reference-shared with previous Config");
    }

    private MergedConfigStore initStore(Config seeded, String onInvalidEntity) {
        when(fileConfigStore.get()).thenReturn(seeded);
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, onInvalidEntity,
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

    private static <V> Map<String, V> mutable(String key, V value) {
        Map<String, V> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Reflective seed of {@code invalidEntities} — the field is otherwise written only inside
     * {@code rebuild()} which we bypass here to test the partial-update resurrection path in isolation.
     */
    private static void seedInvalidEntities(MergedConfigStore store,
                                            Map<ResourceTypes, Map<String, InvalidEntityRecord>> seed) {
        try {
            java.lang.reflect.Field field = MergedConfigStore.class.getDeclaredField("invalidEntities");
            field.setAccessible(true);
            field.set(store, seed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed invalidEntities for test", e);
        }
    }
}
