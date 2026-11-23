package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.plugin.PluginRegistry;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
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

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStorePartialUpdateTest {

    private static final String MODEL_ID = "models/platform/gpt-4";
    private static final String INTERCEPTOR_ID = "interceptors/platform/chain-a";
    private static final String ROLE_ID = "roles/platform/admin";
    private static final String KEY_ID = "keys/platform/proj-a";
    private static final PluginRegistry PLUGINS = PluginRegistry.load(new JsonObject().put("dir", "no-plugins-dir"));

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

    private static BackgroundOperationTracer mockBackgroundTracer() {
        BackgroundOperationTracer tracer = org.mockito.Mockito.mock(BackgroundOperationTracer.class);
        org.mockito.Mockito.lenient().when(tracer.traceFuture(org.mockito.Mockito.any())).thenAnswer(inv -> {
            java.util.function.Supplier<io.vertx.core.Future<?>> supplier = inv.getArgument(0);
            return supplier.get();
        });
        return tracer;
    }

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
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.SKIP);

        Config result = store.applyEntityDelete(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID);

        assertFalse(result.getInterceptors().containsKey(INTERCEPTOR_ID), "interceptor removed");
        assertFalse(result.getModels().containsKey(MODEL_ID), "model cascade-removed from Config");
        Map<String, InvalidEntityRecord> invalidModels = store.getInvalidEntities().get(ResourceTypes.MODEL);
        assertEquals(1, invalidModels.size(), "model recorded in invalidEntities sibling store");
        assertTrue(invalidModels.containsKey(MODEL_ID));
    }

    @Test
    public void interceptorDeleteCascadesUnderAbort() {
        Model model = new Model();
        model.setInterceptors(List.of(INTERCEPTOR_ID));
        Config seeded = newConfig();
        seeded.setModels(mutable(MODEL_ID, model));
        seeded.setInterceptors(mutable(INTERCEPTOR_ID, new Interceptor()));
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);

        Config result = store.applyEntityDelete(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID);

        assertFalse(result.getModels().containsKey(MODEL_ID), "model cascade-removed from Config");
        assertTrue(store.getInvalidEntities().get(ResourceTypes.MODEL).containsKey(MODEL_ID));
    }

    @Test
    public void modelWriteWithDanglingReferenceIsRecordedUnderAbort() {
        MergedConfigStore store = initStore(newConfig(), OnInvalidEntity.ABORT);
        Model model = new Model();
        model.setInterceptors(List.of(INTERCEPTOR_ID));

        Config result = store.applyEntityWrite(ResourceTypes.MODEL, MODEL_ID, model);

        assertFalse(result.getModels().containsKey(MODEL_ID), "model with a dangling reference is not a deployment");
        InvalidEntityRecord record = store.getInvalidEntities().get(ResourceTypes.MODEL).get(MODEL_ID);
        assertEquals("interceptors[0]", record.getValidationWarnings().getFirst().getField());
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
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.SKIP);

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
    public void interceptorWriteRequestsRebuildWhenModelRecordsExist() {
        MergedConfigStore store = initStore(newConfig(), OnInvalidEntity.ABORT);
        Model model = new Model();
        model.setInterceptors(List.of(INTERCEPTOR_ID));
        store.applyEntityWrite(ResourceTypes.MODEL, MODEL_ID, model);

        store.applyEntityWrite(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID, new Interceptor());

        verify(vertx).setTimer(eq(500L), any());
    }

    @Test
    public void interceptorWriteDoesNotRebuildWithoutModelRecords() {
        MergedConfigStore store = initStore(newConfig(), OnInvalidEntity.ABORT);

        store.applyEntityWrite(ResourceTypes.INTERCEPTOR, INTERCEPTOR_ID, new Interceptor());

        verify(vertx, never()).setTimer(anyLong(), any());
    }

    @Test
    public void translatorWriteMakesModelResolveItLiveWithNoCascade() {
        // Confirms the design decision: a translator write does NOT walk/relink existing models —
        // TranslatorRef.resolve() looks the name up in the live registry each time it is asked, so a
        // model instance already in Config.models needs no relink to see a translator created after it.
        DeploymentInterface anthropic = new DeploymentInterface();
        anthropic.setMode(InterfaceMode.TRANSLATOR);
        anthropic.setTranslator(TranslatorRef.named("anthropicMessagesToOpenaiChatCompletions"));

        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages", anthropic));

        Config seeded = newConfig();
        seeded.setModels(mutable(MODEL_ID, model));

        MergedConfigStore store = initStore(seeded, OnInvalidEntity.SKIP);

        // Before the translator exists, the reference resolves to nothing.
        assertNull(anthropic.getTranslator().resolve(store.get().getTranslators()));

        Translator translator = new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS,
                "http://localhost:5003/to-chat-completions");
        Config result = store.applyEntityWrite(ResourceTypes.TRANSLATOR,
                "anthropicMessagesToOpenaiChatCompletions", translator);

        assertSame(model, result.getModels().get(MODEL_ID), "model instance untouched by the translator write");
        assertEquals("http://localhost:5003/to-chat-completions",
                anthropic.getTranslator().resolve(result.getTranslators()).getBaseUrl());
    }

    @Test
    public void translatorWriteWithoutInThrowsAndRollsBack() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);

        Translator bad = new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://localhost:5003/to-chat-completions");

        assertThrows(InvalidEntityException.class,
                () -> store.applyEntityWrite(ResourceTypes.TRANSLATOR, "bad-translator", bad));
        assertFalse(store.get().getTranslators().containsKey("bad-translator"),
                "rejected write must not leave a partial entry");
    }

    @Test
    public void translatorDeleteRemovesEntry() {
        Config seeded = newConfig();
        seeded.setTranslators(mutable("anthropicMessagesToOpenaiChatCompletions",
                new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, "http://localhost:5003/to-chat-completions")));
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);

        Config result = store.applyEntityDelete(ResourceTypes.TRANSLATOR, "anthropicMessagesToOpenaiChatCompletions");

        assertFalse(result.getTranslators().containsKey("anthropicMessagesToOpenaiChatCompletions"));
    }

    @Test
    public void pluginWriteKeepsTheOtherPlugins() {
        Config seeded = newConfig();
        seeded.setPlugins(mutable("a", echoPlugin()));
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT, PLUGINS);
        Config before = store.get();

        Config result = store.applyEntityWrite(ResourceTypes.PLUGIN, "b", echoPlugin());

        assertEquals(Set.of("a", "b"), result.getPlugins().keySet());
        assertEquals(Set.of("a"), before.getPlugins().keySet(), "the previous Config keeps its own map");
    }

    @Test
    public void pluginDeleteKeepsTheOtherPlugins() {
        Config seeded = newConfig();
        Map<String, Plugin> plugins = mutable("a", echoPlugin());
        plugins.put("b", echoPlugin());
        seeded.setPlugins(plugins);
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT, PLUGINS);

        Config result = store.applyEntityDelete(ResourceTypes.PLUGIN, "a");

        assertEquals(Set.of("b"), result.getPlugins().keySet());
    }

    @Test
    public void projectKeyWriteDoesNotTouchApiKeyStore() {
        Config seeded = newConfig();
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);
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
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);

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
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);

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
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);

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
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);
        Config before = store.get();

        store.applyEntityWrite(ResourceTypes.ROLE, ROLE_ID, new Role());

        assertNotSame(before, store.get(), "volatile Config reference swapped");
        assertSame(before.getApplications(), store.get().getApplications(),
                "untouched maps reference-shared with previous Config");
    }

    @Test
    public void modelWriteKeepsFileRouters() {
        Router router = new Router();
        router.setBaseUrl("http://router");
        router.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface()));
        Config seeded = newConfig();
        seeded.setRouters(mutable("r1", router));
        MergedConfigStore store = initStore(seeded, OnInvalidEntity.ABORT);
        assertSame(router, store.get().getRouters().get("r1"), "rebuild copies the file routers");

        Model model = new Model();
        model.setEndpoint("http://model/chat");
        Config result = store.applyEntityWrite(ResourceTypes.MODEL, MODEL_ID, model);

        assertTrue(result.getModels().containsKey(MODEL_ID));
        assertSame(router, result.getRouters().get("r1"), "partial update keeps the file routers");
    }

    @Test
    public void routerWriteIsVisibleAndDeleteRemovesIt() {
        MergedConfigStore store = initStore(newConfig(), OnInvalidEntity.ABORT);
        Router router = router();
        router.setInterceptors(null);

        Config written = store.applyEntityWrite(ResourceTypes.ROUTER, "r-blob", router);

        assertSame(router, written.getRouters().get("r-blob"));
        assertEquals("r-blob", router.getName());
        assertEquals(List.of(), router.getInterceptors(), "a null interceptor list never reaches the request path");

        Config deleted = store.applyEntityDelete(ResourceTypes.ROUTER, "r-blob");
        assertFalse(deleted.getRouters().containsKey("r-blob"));
    }

    @Test
    public void invalidRouterWriteUnderSkipIsRecordedAsInvalid() {
        MergedConfigStore store = initStore(newConfig(), OnInvalidEntity.SKIP);
        Router router = router();
        router.setInterceptors(List.of("i1"));

        Config result = store.applyEntityWrite(ResourceTypes.ROUTER, "r-bad", router);

        assertFalse(result.getRouters().containsKey("r-bad"));
        Map<String, InvalidEntityRecord> invalid = store.getInvalidEntities().get(ResourceTypes.ROUTER);
        assertNotNull(invalid, "the skipped router is recorded");
        assertEquals(1, invalid.size());
    }

    @Test
    public void invalidRouterWriteUnderAbortThrows() {
        MergedConfigStore store = initStore(newConfig(), OnInvalidEntity.ABORT);
        Router router = router();
        router.setEndpoint("http://router/chat");

        assertThrows(InvalidEntityException.class, () -> store.applyEntityWrite(ResourceTypes.ROUTER, "r-bad", router));
        assertFalse(store.get().getRouters().containsKey("r-bad"));
    }

    private static Router router() {
        Router router = new Router();
        router.setBaseUrl("http://router");
        router.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface()));
        return router;
    }

    private MergedConfigStore initStore(Config seeded, OnInvalidEntity onInvalidEntity) {
        return initStore(seeded, onInvalidEntity, PluginRegistry.EMPTY);
    }

    private MergedConfigStore initStore(Config seeded, OnInvalidEntity onInvalidEntity, PluginRegistry pluginRegistry) {
        when(fileConfigStore.get()).thenReturn(seeded);
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, onInvalidEntity, "", pluginRegistry,
                externalServiceService, resourceAuthSettingsEncryptionService, mockBackgroundTracer());
        store.init(fileConfigStore);
        return store;
    }

    private static Plugin echoPlugin() {
        return new Plugin(TestEchoPluginProvider.TYPE, JsonNodeFactory.instance.objectNode().put("greeting", "hi"));
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
}
