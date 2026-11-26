package com.epam.aidial.core.server.service.dependency;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.CatalogSchemaService;
import com.epam.aidial.core.server.service.dependency.DependencyAccessPolicy.ConfigSnapshot;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * The walk on a fake storage: the list of each root holds every resource it reaches once, whatever the shape of the graph, in breadth first
 * order; a resource is read once however many roots reach it; and a reference that is not followed is one log record without its value.
 */
@ExtendWith(MockitoExtension.class)
public class DependencyWalkerTest {

    private static final DependencyAccessPolicy ADMIN = new AdminAccessPolicy(descriptor -> true);

    @Mock
    private ResourceService resourceService;
    @Mock
    private ComplexResourceService complexResourceService;
    @Mock
    private ApplicationSchemaService schemaService;
    @Mock
    private CatalogSchemaService catalogSchemaService;
    @Mock
    private EncryptionService encryptionService;

    /** The stored bodies by the URL of the resource, and the URLs of the bodies in the order in which they were read. */
    private final Map<String, String> stored = new HashMap<>();
    private final List<String> reads = new ArrayList<>();
    private final List<String> existenceChecks = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger walkerLog = (Logger) LoggerFactory.getLogger(DependencyWalker.class);

    @BeforeEach
    void setUp() {
        lenient().when(catalogSchemaService.collectMarkerReferences(any(), any())).thenReturn(new MarkerCollection(List.of(), List.of()));
        lenient().when(resourceService.hasResource(any())).thenAnswer(invocation -> {
            String url = ((ResourceDescriptor) invocation.getArgument(0)).getUrl();
            existenceChecks.add(url);
            return stored.containsKey(url);
        });
        lenient().when(resourceService.getResource(any(ResourceDescriptor.class))).thenAnswer(invocation -> {
            String url = ((ResourceDescriptor) invocation.getArgument(0)).getUrl();
            String body = stored.get(url);
            if (body != null) {
                reads.add(url);
            }
            return body;
        });
        logs.start();
        walkerLog.addAppender(logs);
    }

    @AfterEach
    void detachLog() {
        walkerLog.detachAppender(logs);
    }

    /** The admin policy with a filter on what it lists and a hook that runs for every call of a level. */
    private static final class WrappedPolicy implements DependencyAccessPolicy {
        private final DependencyAccessPolicy delegate = new AdminAccessPolicy(descriptor -> true);
        private final UnaryOperator<Set<ResourceDescriptor>> filter;
        private final Runnable onCall;
        /** The configuration that every call of a level was given. */
        private final List<ConfigSnapshot> snapshots = new ArrayList<>();

        private WrappedPolicy(UnaryOperator<Set<ResourceDescriptor>> filter, Runnable onCall) {
            this.filter = filter;
            this.onCall = onCall;
        }

        @Override
        public boolean isRootAllowed(ResourceDescriptor root) {
            return delegate.isRootAllowed(root);
        }

        @Override
        public boolean isSupported(ResourceDescriptor target) {
            return delegate.isSupported(target);
        }

        @Override
        public Set<ResourceDescriptor> selectListable(Set<ResourceDescriptor> targets, ConfigSnapshot configuration) {
            onCall.run();
            snapshots.add(configuration);
            return filter.apply(delegate.selectListable(targets, configuration));
        }
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String location, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, location, path);
    }

    private static ResourceDescriptor platformApp(String name) {
        return descriptor(ResourceTypes.APPLICATION, "platform", "platform/", name);
    }

    /** A name is the Application of that name in the platform, a name that starts with a tilde is a short name, anything else is a URL. */
    private static String urlOf(String name) {
        if (name.startsWith("~")) {
            return name.substring(1);
        }
        return name.contains("/") ? name : "applications/platform/" + name;
    }

    private static String dependencies(String... dependencies) {
        List<String> urls = new ArrayList<>();
        for (String dependency : dependencies) {
            urls.add("\"" + urlOf(dependency) + "\"");
        }
        return "\"dependencies\":[" + String.join(",", urls) + "]";
    }

    private void app(String name, String... dependencies) {
        stored.put("applications/platform/" + name, "{\"endpoint\":\"http://" + name + ".invalid\"," + dependencies(dependencies) + "}");
    }

    private void model(String name, String... dependencies) {
        stored.put("models/platform/" + name,
                "{\"type\":\"chat\",\"displayName\":\"M\",\"endpoint\":\"http://" + name + ".invalid/v1\"," + dependencies(dependencies) + "}");
    }

    private void file(String path) {
        stored.put("files/public/" + path, "bytes");
    }

    private DependencyWalker walker() {
        return walker(Config::new, () -> null);
    }

    private DependencyWalker walker(Supplier<Config> configSource, Supplier<Config> fileConfigSource) {
        return walker(new ReentrantLock(), Map::of, configSource, fileConfigSource);
    }

    private DependencyWalker walker(ReentrantLock publicationLock,
                                    Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource,
                                    Supplier<Config> configSource, Supplier<Config> fileConfigSource) {
        return new DependencyWalker(publicationLock, invalidEntitiesSource, configSource, fileConfigSource, resourceService, schemaService,
                catalogSchemaService, encryptionService, complexResourceService);
    }

    private DependencyListResponse walk(String... roots) {
        return walk(walker(), ADMIN, roots);
    }

    private static DependencyListResponse walk(DependencyWalker walker, DependencyAccessPolicy policy, String... roots) {
        List<ResourceDescriptor> descriptors = new ArrayList<>();
        for (String root : roots) {
            descriptors.add(platformApp(root));
        }
        return walker.walk(descriptors, policy);
    }

    /** The dependencies of a root as {@code type:name}, in the order of the list. */
    private static List<String> listOf(DependencyRootEntry entry) {
        return entry.dependencies().stream().map(item -> item.type() + ":" + item.name()).toList();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void testWalkWaitsForConfigurationPublicationThatHoldsTheLock() throws Exception {
        app("a");
        ReentrantLock publicationLock = new ReentrantLock();
        DependencyWalker walker = walker(publicationLock, Map::of, Config::new, () -> null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        publicationLock.lock();
        try {
            Future<DependencyListResponse> walk = executor.submit(() -> walk(walker, ADMIN, "a"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!publicationLock.hasQueuedThreads() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }

            assertTrue(publicationLock.hasQueuedThreads(), "the walk must queue on the publication lock");
            assertFalse(walk.isDone(), "the walk must not answer while a publication holds the lock");

            publicationLock.unlock();
            assertEquals(1, walk.get(10, TimeUnit.SECONDS).roots().size());
        } finally {
            if (publicationLock.isHeldByCurrentThread()) {
                publicationLock.unlock();
            }
            executor.shutdownNow();
        }
        assertFalse(publicationLock.isLocked());
    }

    @Test
    void testPublicationLockIsHeldForTheTwoConfigurationReadsOnly() {
        app("a");
        ReentrantLock publicationLock = new ReentrantLock();
        List<String> held = new ArrayList<>();
        DependencyWalker walker = walker(publicationLock,
                () -> {
                    held.add("invalid=" + publicationLock.isHeldByCurrentThread());
                    return Map.of();
                },
                () -> {
                    held.add("merged=" + publicationLock.isHeldByCurrentThread());
                    return new Config();
                },
                () -> {
                    held.add("file=" + publicationLock.isHeldByCurrentThread());
                    return null;
                });

        walk(walker, ADMIN, "a");

        assertEquals(List.of("invalid=true", "merged=true", "file=false"), held);
        assertFalse(publicationLock.isLocked());
    }

    @Test
    void testRootNamedTwiceHasOneEntry() {
        app("a");

        DependencyListResponse response = walk("a", "a");

        assertEquals(1, response.roots().size());
    }

    @Test
    void testSeveralRootsKeepTheOrderOfTheRequest() {
        app("a");
        app("b");

        DependencyListResponse response = walk("b", "a");

        assertEquals(List.of("b", "a"), response.roots().stream().map(entry -> entry.root().name()).toList());
    }

    @Test
    void testApplicationWithoutDependenciesHasAnEmptyList() {
        app("a");

        DependencyRootEntry entry = walk("a").roots().get(0);

        assertEquals(new DependencyResource("application", "applications/platform/a", "a", "platform"), entry.root());
        assertEquals(List.of(), entry.dependencies());
    }

    @Test
    void testDependencyWhoseBodyCannotBeBoundIsListedAndDoesNotFailTheWalk() {
        app("a", "broken", "ok");
        stored.put("applications/platform/broken", "not json SECRET-VALUE");
        app("ok");

        DependencyRootEntry entry = walk("a").roots().get(0);

        assertEquals(List.of("application:broken", "application:ok"), listOf(entry));
        assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("SECRET-VALUE")));
    }

    @Test
    void testRootWhoseBodyCannotBeBoundFailsWithTheResourceAndNoValue() {
        for (String body : List.of("{ sk-abc123 not json", "[\"sk-abc123\"]", "\"sk-abc123\"", "{\"dependencies\":\"sk-abc123\"}")) {
            stored.put("applications/platform/a", body);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> walk("a"), body);

            assertTrue(failure.getMessage().endsWith("(resource=application:platform:a)"), failure.getMessage());
            assertFalse(failure.getMessage().contains("sk-abc123"), "no value of the body in the message");
            assertNull(failure.getCause(), "no cause, whose message can quote the body");
        }
    }

    @Test
    void testCycleBetweenTwoApplicationsListsTheOtherOnceAndNotTheRoot() {
        app("a", "b");
        app("b", "a");

        DependencyRootEntry entry = walk("a").roots().get(0);

        assertEquals(List.of("application:b"), listOf(entry));
    }

    @Test
    void testCycleBetweenModelAndInterceptorListsEachOnce() {
        Config merged = new Config();
        merged.setInterceptors(Map.of("i", new Interceptor()));
        stored.put("interceptors/platform/i", "{\"endpoint\":\"http://i.invalid\"," + dependencies("models/platform/m") + "}");
        stored.put("models/platform/m", "{\"type\":\"chat\",\"displayName\":\"M\",\"endpoint\":\"http://m.invalid/v1\",\"interceptors\":[\"i\"]}");
        app("a", "models/platform/m");

        DependencyRootEntry entry = walk(walker(() -> merged, Config::new), ADMIN, "a").roots().get(0);

        assertEquals(List.of("model:m", "interceptor:i"), listOf(entry));
    }

    @Test
    void testDiamondListsTheSharedResourceOnce() {
        app("root", "left", "right");
        app("left", "models/platform/m");
        app("right", "models/platform/m");
        model("m");

        DependencyRootEntry entry = walk("root").roots().get(0);

        assertEquals(List.of("application:left", "application:right", "model:m"), listOf(entry));
    }

    @Test
    void testReferenceToItselfIsNotDependency() {
        app("a", "a", "a");

        assertEquals(List.of(), walk("a").roots().get(0).dependencies());
    }

    @Test
    void testRootThatAnotherRootNeedsIsAnItemAndAlsoAnEntryWithItsOwnList() {
        app("a", "b");
        app("b", "models/platform/m");
        model("m");

        DependencyListResponse response = walk("a", "b");

        assertEquals(List.of("application:b", "model:m"), listOf(response.roots().get(0)));
        assertEquals("b", response.roots().get(1).root().name());
        assertEquals(List.of("model:m"), listOf(response.roots().get(1)));
    }

    @Test
    void testResourceThatTwoRootsReachIsInBothListsWhateverThePath() {
        app("first", "models/platform/m");
        app("second", "via");
        app("via", "models/platform/m");
        model("m");

        DependencyListResponse response = walk("first", "second");

        assertEquals(List.of("model:m"), listOf(response.roots().get(0)));
        assertEquals(List.of("application:via", "model:m"), listOf(response.roots().get(1)));
    }

    @Test
    void testSharedDependencyIsReadOnce() {
        app("first", "models/platform/m");
        app("second", "models/platform/m");
        model("m");

        walk("first", "second");

        assertEquals(1, reads.stream().filter("models/platform/m"::equals).count(), reads.toString());
        assertEquals(3, reads.size(), "each resource is read once: " + reads);
    }

    @Test
    void testOrderIsBreadthFirstInTheOrderOfTheReferences() {
        app("root", "x", "y");
        app("x", "z");
        app("y");
        app("z");

        assertEquals(List.of("application:x", "application:y", "application:z"), listOf(walk("root").roots().get(0)));
    }

    @Test
    void testTwoRootsWithSharedDependenciesListThemInBoth() {
        app("support-bot", "summarizer", "interceptors/platform/pii-filter", "models/platform/gpt-4o", "~gpt-4o-mini", "files/public/team/icon.png",
                "prompts/public/notes");
        app("summarizer", "interceptors/platform/pii-filter", "models/platform/gpt-4o", "~gpt-4o-mini");
        stored.put("interceptors/platform/pii-filter", "{\"endpoint\":\"http://p.invalid\"}");
        model("gpt-4o");
        file("team/icon.png");
        stored.put("prompts/public/notes", "{\"content\":\"text\"}");
        Config file = new Config();
        file.setModels(Map.of("gpt-4o-mini", new Model()));

        DependencyListResponse response = walk(walker(() -> file, () -> file), ADMIN, "support-bot", "summarizer");

        assertEquals(List.of("application:summarizer", "interceptor:pii-filter", "model:gpt-4o", "model:gpt-4o-mini", "file:icon.png", "prompt:notes"),
                listOf(response.roots().get(0)));
        assertEquals(List.of("interceptor:pii-filter", "model:gpt-4o", "model:gpt-4o-mini"), listOf(response.roots().get(1)));
    }

    @Test
    void testModelToInterceptorToFile() {
        app("a", "models/platform/m");
        stored.put("models/platform/m", "{\"type\":\"chat\",\"displayName\":\"M\",\"endpoint\":\"http://m.invalid/v1\","
                + "\"icon_url\":\"files/public/a/t.png\"," + dependencies("interceptors/platform/i") + "}");
        stored.put("interceptors/platform/i", "{\"endpoint\":\"http://i.invalid\"}");
        file("a/t.png");

        assertEquals(List.of("model:m", "interceptor:i", "file:t.png"), listOf(walk("a").roots().get(0)));
    }

    @Test
    void testLeafIsNotExaminedOnlyCheckedForExistence() {
        app("a", "prompts/public/p");
        stored.put("prompts/public/p", "{\"content\":\"" + urlOf("z") + "\"}");
        app("z");

        DependencyRootEntry entry = walk("a").roots().get(0);

        assertEquals(List.of("prompt:p"), listOf(entry), "the text of a Prompt names no reference");
        assertFalse(reads.contains("prompts/public/p"), "the body of a leaf is not read: " + reads);
        assertTrue(existenceChecks.contains("prompts/public/p"));
    }

    @Test
    void testMissingDependencyIsNotListedAndIsLoggedWithItsReferrer() {
        app("a", "models/platform/gone", "files/public/gone.png");

        DependencyRootEntry entry = walk("a").roots().get(0);

        assertEquals(List.of(), entry.dependencies());
        assertEquals(2, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).contains("resource=application:platform:a pointer=/dependencies/0 reason=missing"), warnings().toString());
        assertTrue(warnings().get(1).contains("pointer=/dependencies/1 reason=missing"), warnings().toString());
    }

    @Test
    void testEntityOfTheFileConfigurationHasNoUrlAndOriginFile() {
        app("a", "~fm");
        Config file = new Config();
        file.setModels(Map.of("fm", new Model()));

        DependencyRootEntry entry = walk(walker(() -> file, () -> file), ADMIN, "a").roots().get(0);

        assertEquals(List.of(new DependencyResource("model", null, "fm", "file")), entry.dependencies());
    }

    @Test
    void testTokenLikeValueIsLoggedWithItsPointerAndReasonButNotItsValue() {
        app("a");
        lenient().when(catalogSchemaService.collectMarkerReferences(any(), any()))
                .thenReturn(new MarkerCollection(List.of(new MarkerReference("/x", "sk-abc123")), List.of()));

        DependencyRootEntry entry = walk("a").roots().get(0);

        assertEquals(List.of(), entry.dependencies());
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).contains("pointer=/catalog_properties/x reason=unresolved-short-name"), warnings().toString());
        assertFalse(warnings().get(0).contains("sk-abc123"), "the value never reaches the log");
    }

    @Test
    void testPolicyIsGivenTheConfigurationThatTheWalkResolvedAgainst() {
        app("a", "models/platform/m", "models/platform/n");
        model("m", "models/platform/n");
        model("n");
        Config merged = new Config();
        Config file = new Config();
        Config laterMerged = new Config();
        int[] configReads = {0};
        WrappedPolicy policy = new WrappedPolicy(allowed -> allowed, () -> { });

        walk(walker(() -> configReads[0]++ == 0 ? merged : laterMerged, () -> file), policy, "a");

        assertFalse(policy.snapshots.isEmpty(), "the policy was asked");
        for (ConfigSnapshot snapshot : policy.snapshots) {
            assertSame(merged, snapshot.merged(), "the configuration that discovery used, not a later one");
            assertSame(file, snapshot.file());
        }
    }

    @Test
    void testTargetThatPolicyDoesNotListIsLeftOutWithoutTrace() {
        app("a", "models/platform/m", "models/platform/hidden");
        model("m");
        model("hidden");
        DependencyAccessPolicy policy = new WrappedPolicy(allowed -> {
            Set<ResourceDescriptor> kept = new HashSet<>(allowed);
            kept.removeIf(target -> "hidden".equals(target.getName()));
            return kept;
        }, () -> { });

        DependencyRootEntry entry = walk(walker(), policy, "a").roots().get(0);

        assertEquals(List.of("model:m"), listOf(entry));
        assertFalse(reads.contains("models/platform/hidden"), "a target that is not listed is not read");
        assertEquals(List.of(), warnings(), "and leaves no log record");
    }

    @Test
    void testRootThatNoLongerExistsEndsTheWalk() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> walk("gone"));

        assertTrue(failure.getMessage().contains("application:platform:gone"), failure.getMessage());
    }

    @Test
    void testExistsAsksStorageForStoredResourceAndTrustsFileEntity() {
        app("a");

        assertTrue(walker().isStored(platformApp("a")));
        assertFalse(walker().isStored(platformApp("b")));
        assertTrue(walker().isStored(FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "fm")));
    }

    /** The body of a resource is garbage once its references are found: the walk keeps identities and links, never a body. */
    @Test
    void testBodyIsNotHeldAfterItsReferencesAreFound() throws InterruptedException {
        app("a", "b");
        app("b", "c");
        app("c");
        List<WeakReference<String>> bodies = new ArrayList<>();
        boolean[] firstBodyWasHeld = {true};
        lenient().when(resourceService.getResource(any(ResourceDescriptor.class))).thenAnswer(invocation -> {
            String stored = this.stored.get(((ResourceDescriptor) invocation.getArgument(0)).getUrl());
            if (bodies.size() == 2) {
                // c is read: the body of a was read two resources ago and is no longer needed
                for (int attempt = 0; attempt < 10 && bodies.get(0).get() != null; attempt++) {
                    System.gc();
                    Thread.sleep(20);
                }
                firstBodyWasHeld[0] = bodies.get(0).get() != null;
            }
            String body = new String(stored.toCharArray());
            bodies.add(new WeakReference<>(body));
            return body;
        });

        walk("a");

        assertEquals(3, bodies.size());
        assertFalse(firstBodyWasHeld[0], "the body of the first resource is still reachable while the third is read");
    }

    /**
     * Two roots reach 50 resources through a diamond and a cycle: 16 Applications that both roots depend on, 16 that depend back on them,
     * and 16 Files. A resource is read once, a leaf is only checked for existence, and the policy is asked once per level.
     */
    @Test
    void testOneReadPerResourceOneCheckPerLeafAndOnePolicyCallPerLevel() {
        List<String> first = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            first.add("m" + i);
            app("m" + i, "n" + i);
            app("n" + i, "m" + i, "files/public/f" + i);
            file("f" + i);
        }
        app("r1", first.toArray(new String[0]));
        app("r2", first.toArray(new String[0]));
        int[] policyCalls = {0};
        DependencyAccessPolicy counting = new WrappedPolicy(allowed -> allowed, () -> policyCalls[0]++);

        DependencyListResponse response = walk(walker(), counting, "r1", "r2");

        assertEquals(34, reads.size(), "every Application once: " + reads);
        assertEquals(34, new HashSet<>(reads).size());
        assertEquals(16, existenceChecks.stream().filter(url -> url.startsWith("files/")).count(), "every File once");
        assertEquals(3, policyCalls[0], "the levels are the m, the n and the files");
        assertEquals(48, response.roots().get(0).dependencies().size());
        assertEquals(response.roots().get(0).dependencies(), response.roots().get(1).dependencies());
    }
}
