package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    @Mock
    private ExternalServiceService externalServiceService;
    @Mock
    private ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;

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
    public void testRebuildKeepsTranslatorsSoNamedReferencesResolve() {
        // the merged Config is assembled field by field, and a translators map dropped there leaves every
        // deployment referencing one by name serving nothing on that interface
        Config fileConfig = new Config();
        fileConfig.setTranslators(Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, "http://localhost:5002/to-chat-completions")));
        DeploymentInterface anthropic = new DeploymentInterface();
        anthropic.setMode(InterfaceMode.TRANSLATOR);
        anthropic.setTranslator(TranslatorRef.named("anthropicMessagesToOpenaiChatCompletions"));
        Model model = new Model();
        model.setBaseUrl("http://localhost:6001");
        model.setInterfaces(Map.of(
                "openaiChatCompletions", new DeploymentInterface(),
                "anthropicMessages", anthropic));
        fileConfig.setModels(new LinkedHashMap<>(Map.of("openai-gpt-5.4-mini", model)));
        when(fileConfigStore.get()).thenReturn(fileConfig);

        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);

        Config config = store.get();

        assertEquals(1, config.getTranslators().size());
        Model merged = config.getModels().get("openai-gpt-5.4-mini");
        assertEquals("http://localhost:5002/to-chat-completions",
                DeploymentEndpointUtil.resolveServingEndpoint(merged, InterfaceType.ANTHROPIC_MESSAGES));
        assertEquals("http://localhost:6001",
                DeploymentEndpointUtil.resolveServingEndpoint(merged, InterfaceType.OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    public void testRequestRebuildIsNoOpBeforeInit() {
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT,
                externalServiceService, resourceAuthSettingsEncryptionService);

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
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);

        store.requestRebuild();
        Config rebuilt = store.rebuildNow();

        verify(vertx, times(1)).cancelTimer(eq(sentinelTimerId));
        org.junit.jupiter.api.Assertions.assertNotNull(rebuilt);
    }

    @Test
    public void testRebuildMaterializesPlatformApplicationWithDecryptedSecrets() {
        ResourceDescriptor appDescriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.APPLICATION, "platform", "platform/", "my-app");
        String appBody = "{\"endpoint\":\"http://localhost/completions\"}";
        stubListResources(ResourceTypes.APPLICATION,
                List.of(Pair.of(new ResourceItemMetadata(appDescriptor), appBody)));
        when(fileConfigStore.get()).thenReturn(new Config());

        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);

        Config config = store.get();
        Application materialized = config.getApplications().get("my-app");
        assertEquals("http://localhost/completions", materialized.getEndpoint());
        verify(externalServiceService).decryptSecrets(
                argThat(d -> "platform".equals(d.getBucketName())), eq(materialized));
    }

    @Test
    public void testRebuildMaterializesPlatformToolSetWithDecryptedAuthSettings() {
        ResourceDescriptor toolSetDescriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.TOOL_SET, "platform", "platform/", "my-toolset");
        String toolSetBody = "{\"transport\":\"http\",\"endpoint\":\"http://localhost:9876\","
                + "\"auth_settings\":{\"client_secret\":\"ENC[abc]\"}}";
        stubListResources(ResourceTypes.TOOL_SET,
                List.of(Pair.of(new ResourceItemMetadata(toolSetDescriptor), toolSetBody)));
        when(fileConfigStore.get()).thenReturn(new Config());

        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);

        Config config = store.get();
        ToolSet materialized = config.getToolsets().get("my-toolset");
        assertEquals("http://localhost:9876", materialized.getEndpoint());
        verify(resourceAuthSettingsEncryptionService).decrypt(
                eq(toolSetDescriptor.getUrl()), any(BucketInfo.class), eq(materialized.getAuthSettings()));
    }

    @Test
    public void testRebuildRecordsInvalidEntityOnApplicationDecryptFailure() {
        ResourceDescriptor appDescriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.APPLICATION, "platform", "platform/", "bad-app");
        String appBody = "{\"endpoint\":\"http://localhost/completions\"}";
        stubListResources(ResourceTypes.APPLICATION,
                List.of(Pair.of(new ResourceItemMetadata(appDescriptor), appBody)));
        when(fileConfigStore.get()).thenReturn(new Config());
        doThrow(new RuntimeException("decrypt failed"))
                .when(externalServiceService).decryptSecrets(any(), any());

        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, MergedConfigStore.MODE_SKIP,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);

        Config config = store.get();
        assertTrue(config.getApplications().isEmpty(), "decrypt-failed entity must not enter merged Config");
        assertTrue(store.getInvalidEntities().get(ResourceTypes.APPLICATION).containsKey("applications/platform/bad-app"));
    }

    private void stubListResources(ResourceTypes type, List<Pair<ResourceItemMetadata, String>> items) {
        lenient().when(resourceService.listResources(any(), any())).thenReturn(List.of());
        lenient().when(resourceService.listResources(
                argThat(descriptor -> descriptor != null && descriptor.getType() == type
                        && "platform".equals(descriptor.getBucketName())),
                any())).thenReturn(items);
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
                secretFieldProcessor, lockService, MergedConfigStore.MODE_ABORT, false, thisPodId,
                externalServiceService, resourceAuthSettingsEncryptionService);
        store.init(fileConfigStore);

        ArgumentCaptor<Consumer<ResourceEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(resourceService).subscribeAllResources(captor.capture());
        return captor.getValue();
    }
}
