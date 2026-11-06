package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ExternalServiceService;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MergedConfigStorePolicyTest {

    private static final String GRANT = """
            { "priority": 10, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"], "cost": { "month": 500 } }
            """;
    private static final String GRANT_PRIORITY_20 = GRANT.replace("\"priority\": 10", "\"priority\": 20");
    private static final String ASSIGNMENT = """
            { "policy": "%s", "costObject": "%s",
              "subject": [ { "source": "sub", "function": "EQUAL", "targets": ["alice"] } ] }
            """;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Map<ResourceTypes, List<Pair<ResourceItemMetadata, String>>> storedByType = new EnumMap<>(ResourceTypes.class);

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
    void setUp() {
        Metrics.addRegistry(meterRegistry);
        lenient().when(lockService.underBucketLocks(any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        lenient().when(apiKeyStore.getMutationLock()).thenReturn(new ReentrantLock());
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meterRegistry);
    }

    @Test
    void testReplicaEventsOfOnePodBecomeVisibleOnAnother() {
        MergedConfigStore store = initStore(MergedConfigStore.MODE_ABORT);

        applyReplica(store, ResourceTypes.POLICY, "grant", ResourceEvent.Action.CREATE, GRANT);
        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("grant", "IMF"));

        assertTrue(store.get().getPolicies().containsKey("grant"));
        assertTrue(store.get().getPolicyAssignments().containsKey("IMF/a-1"));
        assertEquals(List.of("IMF/a-1"), instanceIds(store.get(), "IMF"));

        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ResourceEvent.Action.DELETE, null);

        assertTrue(store.get().getPolicyAssignments().isEmpty());
        assertTrue(instanceIds(store.get(), "IMF").isEmpty());
    }

    @Test
    void testUpdateOfPolicyReachesItsInstances() {
        MergedConfigStore store = initStore(MergedConfigStore.MODE_ABORT);
        applyReplica(store, ResourceTypes.POLICY, "grant", ResourceEvent.Action.CREATE, GRANT);
        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("grant", "IMF"));

        applyReplica(store, ResourceTypes.POLICY, "grant", ResourceEvent.Action.UPDATE, GRANT_PRIORITY_20);

        assertEquals(20, store.get().getCostObjectIndex().getInstances("IMF").get(0).policy().getPriority());
    }

    @Test
    void testEqualIdsInDifferentFoldersCoexist() {
        MergedConfigStore store = initStore(MergedConfigStore.MODE_ABORT);
        applyReplica(store, ResourceTypes.POLICY, "grant", ResourceEvent.Action.CREATE, GRANT);

        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("grant", "IMF"));
        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "ORG/a-1", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("grant", "ORG"));

        assertEquals(2, store.get().getPolicyAssignments().size());
        assertEquals(List.of("IMF/a-1"), instanceIds(store.get(), "IMF"));
        assertEquals(List.of("ORG/a-1"), instanceIds(store.get(), "ORG"));
    }

    @Test
    void testGlobalAndProjectPolicyOfTheSameNameResolveSeparately() {
        MergedConfigStore store = initStore(MergedConfigStore.MODE_ABORT);
        applyReplica(store, ResourceTypes.POLICY, "grant", ResourceEvent.Action.CREATE, GRANT);
        applyReplica(store, ResourceTypes.POLICY, "IMF/grant", ResourceEvent.Action.CREATE, GRANT_PRIORITY_20);

        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("grant", "IMF"));
        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-2", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("IMF/grant", "IMF"));

        List<BudgetInstance> instances = store.get().getCostObjectIndex().getInstances("IMF");
        assertEquals(2, instances.size());
        for (BudgetInstance instance : instances) {
            assertEquals("IMF/a-1".equals(instance.id()) ? 10 : 20, instance.policy().getPriority());
        }
    }

    @Test
    void testAssignmentBeforeItsPolicyLeavesConfigUnchangedAndRequestsRebuild() {
        MergedConfigStore store = initStore(MergedConfigStore.MODE_SKIP);

        applyReplica(store, ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ResourceEvent.Action.CREATE, ASSIGNMENT.formatted("grant", "IMF"));

        assertTrue(store.get().getPolicyAssignments().isEmpty());
        assertTrue(store.getInvalidEntities().isEmpty());
        verify(vertx).setTimer(anyLong(), any());
    }

    @Test
    void testRebuildKeepsRecordsOfEqualIdInDifferentFolders() {
        stubStored(ResourceTypes.POLICY, "grant", GRANT);
        stubStored(ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ASSIGNMENT.formatted("grant", "IMF"));
        stubStored(ResourceTypes.POLICY_ASSIGNMENT, "ORG/a-1", ASSIGNMENT.formatted("grant", "ORG"));

        MergedConfigStore store = initStore(MergedConfigStore.MODE_ABORT);

        assertEquals(List.of("IMF/a-1", "ORG/a-1"), store.get().getPolicyAssignments().keySet().stream().sorted().toList());
        assertEquals(List.of("IMF/a-1"), instanceIds(store.get(), "IMF"));
        assertEquals(List.of("ORG/a-1"), instanceIds(store.get(), "ORG"));
    }

    @Test
    void testDanglingAssignmentAbortsRebuildInAbortMode() {
        stubStored(ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ASSIGNMENT.formatted("gone", "IMF"));

        InvalidEntityException error = assertThrows(InvalidEntityException.class, () -> initStore(MergedConfigStore.MODE_ABORT));

        assertEquals(ResourceTypes.POLICY_ASSIGNMENT, error.getType());
        assertEquals("IMF/a-1", error.getMapKey());
    }

    @Test
    void testDanglingAssignmentIsSkippedAndCountedInSkipMode() {
        stubStored(ResourceTypes.POLICY, "grant", GRANT);
        stubStored(ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ASSIGNMENT.formatted("grant", "IMF"));
        stubStored(ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-2", ASSIGNMENT.formatted("gone", "IMF"));

        MergedConfigStore store = initStore(MergedConfigStore.MODE_SKIP);

        assertEquals(List.of("IMF/a-1"), List.copyOf(store.get().getPolicyAssignments().keySet()));
        assertEquals(List.of("IMF/a-1"), instanceIds(store.get(), "IMF"));
        InvalidEntityRecord record = store.getInvalidEntities().get(ResourceTypes.POLICY_ASSIGNMENT)
                .get("policy_assignments/platform/IMF/a-2");
        assertNotNull(record);
        assertEquals("api", record.getSource());
        assertEquals(1, skipCount(ResourceTypes.POLICY_ASSIGNMENT));
    }

    @Test
    void testInvalidPolicyRecordIsSkippedAndItsAssignmentsDangle() {
        stubStored(ResourceTypes.POLICY, "grant", GRANT.replace("\"kind\": \"budget\"", "\"kind\": \"budget\", \"harness\": {}"));
        stubStored(ResourceTypes.POLICY_ASSIGNMENT, "IMF/a-1", ASSIGNMENT.formatted("grant", "IMF"));

        MergedConfigStore store = initStore(MergedConfigStore.MODE_SKIP);

        assertTrue(store.get().getPolicies().isEmpty());
        assertTrue(store.get().getPolicyAssignments().isEmpty());
        assertEquals(1, store.getInvalidEntities().get(ResourceTypes.POLICY).size());
        assertEquals(1, store.getInvalidEntities().get(ResourceTypes.POLICY_ASSIGNMENT).size());
    }

    @Test
    void testSelfApplyingPolicyWithoutFolderIsSkipped() {
        stubStored(ResourceTypes.POLICY, "loose", GRANT.replace("\"per\"",
                "\"costObject\": \"IMF\", \"subject\": [ { \"source\": \"sub\", \"function\": \"TRUE\" } ], \"per\""));

        MergedConfigStore store = initStore(MergedConfigStore.MODE_SKIP);

        assertTrue(store.get().getPolicies().isEmpty());
        assertTrue(store.getInvalidEntities().get(ResourceTypes.POLICY).containsKey("policies/platform/loose"));
    }

    @Test
    void testRebuildLeavesOtherManagedTypesEmptyWhenNoneStored() {
        stubStored(ResourceTypes.POLICY, "grant", GRANT);

        MergedConfigStore store = initStore(MergedConfigStore.MODE_ABORT);

        assertTrue(store.get().getModels().isEmpty());
        assertTrue(store.get().getRoles().isEmpty());
        assertTrue(store.get().getKeys().isEmpty());
    }

    private void stubStored(ResourceTypes type, String path, String body) {
        List<Pair<ResourceItemMetadata, String>> items = storedByType.computeIfAbsent(type, key -> new ArrayList<>());
        items.add(Pair.of(new ResourceItemMetadata(descriptor(type, path)), body));
        lenient().when(resourceService.listResources(argThat(folder -> folder != null && folder.getType() == type), any()))
                .thenReturn(items);
    }

    private MergedConfigStore initStore(String onInvalidEntity) {
        Config file = new Config();
        file.setModels(new LinkedHashMap<>());
        file.setInterceptors(new LinkedHashMap<>());
        file.setRoles(new HashMap<>());
        file.setKeys(new HashMap<>());
        file.setRoutes(new LinkedHashMap<>());
        file.setApplicationTypeSchemas(new LinkedHashMap<>());
        file.setApplications(new HashMap<>());
        file.setToolsets(new LinkedHashMap<>());
        lenient().when(fileConfigStore.get()).thenReturn(file);
        MergedConfigStore store = new MergedConfigStore(
                vertx, taskExecutor, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, lockService, onInvalidEntity, false, "pod-this",
                externalServiceService, resourceAuthSettingsEncryptionService, Mockito.mock(BackgroundOperationTracer.class));
        store.init(fileConfigStore);
        return store;
    }

    private void applyReplica(MergedConfigStore store, ResourceTypes type, String path, ResourceEvent.Action action, String body) {
        ResourceDescriptor descriptor = descriptor(type, path);
        lenient().when(resourceService.getResource(descriptor)).thenReturn(body);
        store.applyReplicaEvent(descriptor, action, null);
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, path);
    }

    private static List<String> instanceIds(Config config, String costObject) {
        return config.getCostObjectIndex().getInstances(costObject).stream().map(BudgetInstance::id).sorted().toList();
    }

    private double skipCount(ResourceTypes type) {
        Counter counter = meterRegistry.find("dial_config_skip_events_total")
                .tag("type", type.urlSegment()).tag("reason", "validation_error").counter();
        return counter == null ? 0 : counter.count();
    }
}
