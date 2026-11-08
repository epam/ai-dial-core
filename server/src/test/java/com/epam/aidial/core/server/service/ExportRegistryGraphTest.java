package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureEdge;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.service.ExportRegistry.Dependency;
import com.epam.aidial.core.server.service.ExportRegistry.Resource;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The graph of the registry: roots, shared dependencies, cycles, {@code requiredByRoots} and identities that must not collide.
 * The closures are built directly, as the walker gives them; the walker's own handling of duplicate roots and cycles is tested with it.
 */
public class ExportRegistryGraphTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");

    private static final ResourceDescriptor A = descriptor(ResourceTypes.APPLICATION, "platform", "a");
    private static final ResourceDescriptor B = descriptor(ResourceTypes.APPLICATION, "platform", "b");
    private static final ResourceDescriptor C = descriptor(ResourceTypes.APPLICATION, "platform", "c");
    private static final ResourceDescriptor TOOLSET_X = descriptor(ResourceTypes.TOOL_SET, "platform", "x");
    private static final ResourceDescriptor MODEL_M = descriptor(ResourceTypes.MODEL, "platform", "m");
    private static final ResourceDescriptor TOOLSET_Y = descriptor(ResourceTypes.TOOL_SET, "platform", "y");

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, bucket + "/", name);
    }

    private static ClosureNode node(ResourceDescriptor descriptor, boolean isRoot) {
        return ExportTestNodes.node(descriptor, isRoot, "{}", null);
    }

    private static ClosureEdge included(ResourceDescriptor from, String pointer, ResourceDescriptor to) {
        return ClosureEdge.included(from, new DiscoveredEdge(pointer, null, null, null, List.of()), to);
    }

    private static ExportRegistry registryOf(List<ClosureNode> nodes, List<ClosureEdge> edges) {
        return ExportArchive.prepare(new Closure(nodes, edges), ExportTestNodes.NO_CONTENT, "v", NOW, ExportLimits.Deadline.unbounded()).registry();
    }

    private static String key(ResourceDescriptor descriptor) {
        return ExportResourceIdentity.ofDescriptor(descriptor, null).key();
    }

    private static List<String> keys(ResourceDescriptor... descriptors) {
        List<String> keys = new ArrayList<>();
        for (ResourceDescriptor descriptor : descriptors) {
            keys.add(key(descriptor));
        }
        return keys;
    }

    private static Resource resource(ExportRegistry registry, ResourceDescriptor descriptor) {
        return registry.resources().get(key(descriptor));
    }

    @Test
    public void testDeepChainIsRequiredByItsRootAtEveryDepth() {
        // a -> x -> m -> c -> y, with a cycle-free chain through four different types
        ExportRegistry registry = registryOf(
                List.of(node(A, true), node(TOOLSET_X, false), node(MODEL_M, false), node(C, false), node(TOOLSET_Y, false)),
                List.of(included(A, "/dependencies/0", TOOLSET_X), included(TOOLSET_X, "/dependencies/0", MODEL_M),
                        included(MODEL_M, "/dependencies/0", C), included(C, "/dependencies/0", TOOLSET_Y)));

        assertEquals(keys(A), registry.roots());
        for (ResourceDescriptor required : List.of(TOOLSET_X, MODEL_M, C, TOOLSET_Y)) {
            assertEquals(keys(A), resource(registry, required).requiredByRoots(), key(required));
        }
        assertEquals(List.of(), resource(registry, A).requiredByRoots());
        assertEquals(key(MODEL_M), resource(registry, TOOLSET_X).dependencies().get(0).key());
        assertEquals("model", resource(registry, TOOLSET_X).dependencies().get(0).kind());
    }

    @Test
    public void testSharedDirectAndTransitiveDependenciesAppearOnceAndAreRequiredByBothRoots() {
        ExportRegistry registry = registryOf(
                List.of(node(A, true), node(B, true), node(TOOLSET_X, false), node(MODEL_M, false)),
                List.of(included(A, "/dependencies/0", TOOLSET_X), included(B, "/dependencies/0", TOOLSET_X),
                        included(TOOLSET_X, "/dependencies/0", MODEL_M)));

        assertEquals(keys(A, B), registry.roots());
        assertEquals(4, registry.resources().size(), "the shared dependencies are in the registry once");
        assertEquals(keys(A, B), resource(registry, TOOLSET_X).requiredByRoots());
        assertEquals(keys(A, B), resource(registry, MODEL_M).requiredByRoots(), "a transitive dependency is required by both roots too");
        assertEquals(key(TOOLSET_X), resource(registry, A).dependencies().get(0).key());
        assertEquals(key(TOOLSET_X), resource(registry, B).dependencies().get(0).key());
        assertEquals(registry.resources().values().stream().map(Resource::entryName).collect(Collectors.toSet()).size(), 4,
                "one archive entry per resource");
    }

    @Test
    public void testRootThatAnotherRootRequiresIsSelectedAndListsOnlyTheOtherRoot() {
        ExportRegistry registry = registryOf(List.of(node(A, true), node(B, true), node(C, false)),
                List.of(included(A, "/dependencies/0", B), included(B, "/dependencies/0", C)));

        assertEquals("selected", resource(registry, B).role());
        assertEquals(keys(A, B), registry.roots());
        assertEquals(keys(A), resource(registry, B).requiredByRoots(), "B is required by A and not by itself");
        assertEquals(List.of(), resource(registry, A).requiredByRoots());
        assertEquals(keys(A, B), resource(registry, C).requiredByRoots());
        assertEquals(1, registry.resources().keySet().stream().filter(key(B)::equals).count(), "B has one entry although it is also a dependency");
    }

    @Test
    public void testDirectAndIndirectCyclesTerminateAndNeverListResourceForItself() {
        // a <-> b directly, and a -> c -> x -> a indirectly; the roots are a and b
        ExportRegistry registry = registryOf(List.of(node(A, true), node(B, true), node(C, false), node(TOOLSET_X, false)),
                List.of(included(A, "/dependencies/0", B), included(B, "/dependencies/0", A), included(A, "/dependencies/1", C),
                        included(C, "/dependencies/0", TOOLSET_X), included(TOOLSET_X, "/dependencies/0", A)));

        assertEquals(keys(B), resource(registry, A).requiredByRoots(), "A is required by B and not by itself, although the cycle returns to it");
        assertEquals(keys(A), resource(registry, B).requiredByRoots(), "B is reached from A and not from itself");
        assertEquals(keys(A, B), resource(registry, C).requiredByRoots());
        assertEquals(keys(A, B), resource(registry, TOOLSET_X).requiredByRoots());
        assertEquals(4, registry.resources().size());
    }

    @Test
    public void testRootsKeepTheOrderOfTheSelectionAndRequiredByRootsFollowsIt() {
        // the walker puts the roots first in the order of the request, whatever their names
        ExportRegistry registry = registryOf(List.of(node(C, true), node(A, true), node(B, true), node(MODEL_M, false)),
                List.of(included(A, "/dependencies/0", MODEL_M), included(B, "/dependencies/0", MODEL_M), included(C, "/dependencies/0", MODEL_M)));

        assertEquals(keys(C, A, B), registry.roots());
        assertEquals(keys(C, A, B), resource(registry, MODEL_M).requiredByRoots());
        assertEquals(keys(C, A, B), List.copyOf(registry.resources().keySet()).subList(0, 3));
    }

    @Test
    public void testSeveralReferencesToOneTargetAreSeparateDependenciesWithTheSameKey() {
        ExportRegistry registry = registryOf(List.of(node(A, true), node(MODEL_M, false)),
                List.of(included(A, "/dependencies/0", MODEL_M), included(A, "/application_properties/model", MODEL_M)));

        List<Dependency> dependencies = resource(registry, A).dependencies();
        assertEquals(2, dependencies.size());
        assertEquals(List.of("/dependencies/0", "/application_properties/model"), dependencies.stream().map(Dependency::pointer).toList());
        assertEquals(1, dependencies.stream().map(Dependency::key).distinct().count());
    }

    @Test
    public void testResourcesThatSharePathAreDistinctByTypeOriginAndVersion() {
        List<ResourceDescriptor> descriptors = List.of(
                descriptor(ResourceTypes.APPLICATION, "platform", "same"), descriptor(ResourceTypes.TOOL_SET, "platform", "same"),
                descriptor(ResourceTypes.MODEL, "platform", "same"), descriptor(ResourceTypes.APPLICATION, "public", "same"),
                descriptor(ResourceTypes.APPLICATION, "public", "same__1.0.0"), descriptor(ResourceTypes.APPLICATION, "public", "same__1.0.1"),
                descriptor(ResourceTypes.APPLICATION, "public", "f/same__1.0.0"), descriptor(ResourceTypes.APPLICATION, "platform", "a:b"),
                descriptor(ResourceTypes.APPLICATION, "platform", "a~3Ab"), descriptor(ResourceTypes.APPLICATION, "platform", "a%3Ab"));
        List<ClosureNode> nodes = new ArrayList<>();
        descriptors.forEach(descriptor -> nodes.add(node(descriptor, nodes.isEmpty())));

        ExportRegistry registry = registryOf(nodes, List.of());

        assertEquals(descriptors.size(), registry.resources().size(), "no two identities share a key");
        assertEquals(descriptors.size(), registry.resources().values().stream().map(Resource::entryName).distinct().count());
        registry.resources().keySet().forEach(key -> assertFalse(key.split(":", 3)[2].contains(":"),
                "the path part holds no colon, so the key splits at its first three"));
    }
}
