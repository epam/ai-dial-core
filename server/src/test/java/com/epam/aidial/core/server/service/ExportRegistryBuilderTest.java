package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeTarget;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureEdge;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.service.ExportRegistry.Dependency;
import com.epam.aidial.core.server.service.ExportRegistry.Resource;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Skipped;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CollectionIssue;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExportRegistryBuilderTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.987654321Z");
    private static final MarkerCollection NO_MARKERS = new MarkerCollection(List.of(), List.of());
    private static final ResourceDescriptor ROOT = descriptor(ResourceTypes.APPLICATION, "platform", "root");
    private static final ResourceDescriptor DEPENDENCY = descriptor(ResourceTypes.APPLICATION, "platform", "dep");
    private static final ResourceDescriptor SCHEMA = descriptor(ResourceTypes.APP_TYPE_SCHEMA, "platform", "schema-blob");

    /** The registry of the archive that the preparation of {@code closure} gives, as production builds it. */
    private static ExportRegistry registryOf(Closure closure, String coreVersion, Instant createdAt) {
        return ExportArchive.prepare(closure, ExportTestNodes.NO_CONTENT, coreVersion, createdAt, ExportLimits.Deadline.unbounded()).registry();
    }

    private static String toJson(ExportRegistry registry) {
        try {
            return ProxyUtil.MAPPER.writeValueAsString(registry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, bucket + "/", name);
    }

    private static DiscoveredEdge discovered(String pointer, EdgeStatus status, ReferenceForm kind, EdgeTarget target, List<Skipped> skipped) {
        return new DiscoveredEdge(pointer, status, kind, target, skipped);
    }

    private static ClosureEdge listed(String pointer, EdgeStatus status, ReferenceForm kind, EdgeTarget target, List<Skipped> skipped) {
        return ClosureEdge.unresolved(ROOT, discovered(pointer, status, kind, target, skipped), status);
    }

    private static ClosureEdge included(String pointer, ResourceDescriptor to) {
        return ClosureEdge.included(ROOT, discovered(pointer, null, null, null, List.of()), to);
    }

    private static Closure closureOf(List<ClosureEdge> edges, MarkerCollection markers) {
        return closureOf(edges, markers, ExportTestNodes.KEEPS_THE_BODY);
    }

    /** The redactor is applied when the nodes are made; the registry lists what it removed. */
    private static Closure closureOf(List<ClosureEdge> edges, MarkerCollection markers, BodyRedactor redactor) {
        ExportDiscovery rootEdges = DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, markers);
        List<ClosureNode> nodes = List.of(
                ExportTestNodes.node(redactor, ROOT, true, "{}", rootEdges),
                ExportTestNodes.node(redactor, DEPENDENCY, false, "{}", DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, NO_MARKERS)),
                ExportTestNodes.node(redactor, SCHEMA, false, "{\"$id\":\"https://dial.example/schemas/quick:apps\"}", null));
        return new Closure(nodes, edges);
    }

    private static Closure typicalClosure() {
        return typicalClosure(ExportTestNodes.KEEPS_THE_BODY);
    }

    private static Closure typicalClosure(BodyRedactor redactor) {
        EdgeTarget fileModel = new EdgeTarget(ResourceTypes.MODEL, "file-model", null, Origin.FILE, false);
        List<ClosureEdge> edges = List.of(
                included("/application_type_schema_id", SCHEMA),
                included("/dependencies/0", DEPENDENCY),
                listed("/interceptors/0", EdgeStatus.UNSUPPORTED, null, fileModel, List.of()),
                listed("/application_properties/folder", EdgeStatus.UNSUPPORTED, null, null, List.of()),
                listed("/application_properties/name", EdgeStatus.UNRESOLVED_SHORT_NAME, ReferenceForm.SHORT_NAME, null, List.of()),
                listed("/application_properties/url", EdgeStatus.EXTERNAL, ReferenceForm.EXTERNAL_URL, null, List.of()),
                listed("/dependencies/1", EdgeStatus.MISSING, ReferenceForm.SHORT_NAME, null,
                        List.of(new Skipped(ResourceTypes.MODEL, "models/platform/m", "api", "SECRET exception text"))));
        return closureOf(edges, NO_MARKERS, redactor);
    }

    private static Resource resource(ExportRegistry registry, ResourceDescriptor descriptor) {
        return registry.resources().get(ExportResourceIdentity.ofDescriptor(descriptor, null).key());
    }

    /** The dependencies of a resource that do not end in an included resource. */
    private static List<Dependency> unresolved(Resource resource) {
        return resource.dependencies().stream().filter(dependency -> !ExportRegistryBuilder.INCLUDED.equals(dependency.status())).toList();
    }

    @Test
    public void testResourcesHaveRolesDependenciesAndEntryNamesAndSchemaIdentityIsItsId() {
        ExportRegistry registry = registryOf(typicalClosure(), "v", NOW);

        assertEquals(List.of("application:platform:root"), registry.roots());
        assertEquals(List.of("selected", "dependency", "dependency"), registry.resources().values().stream().map(Resource::role).toList());
        Resource root = resource(registry, ROOT);
        assertEquals("platform/applications/root", root.entryName());
        assertEquals("root", root.path());
        assertEquals("platform/", root.bucketLocation());

        Dependency toDependency = root.dependencies().get(1);
        assertEquals("application:platform:dep", toDependency.key());
        assertEquals("application", toDependency.kind());
        assertEquals("included", toDependency.status());
        assertEquals("/dependencies/0", toDependency.pointer());
        assertEquals(List.of("application:platform:root"), resource(registry, DEPENDENCY).requiredByRoots());

        Resource schema = registry.resources().values().stream().skip(2).findFirst().orElseThrow();
        assertEquals("https://dial.example/schemas/quick:apps", schema.schemaId());
        assertEquals("application-type-schema", schema.type());
        assertFalse(schema.entryName().contains(":") || schema.entryName().contains("%"), schema.entryName());
        assertEquals("/application_type_schema_id", root.dependencies().get(0).pointer());
        assertEquals(schema.type() + ":platform:" + ExportResourceIdentity.schemaDigest(schema.schemaId()), root.dependencies().get(0).key());
    }

    @Test
    public void testOnlyReferencesThatDoNotEndInResourceListTheirKindOrIdentityAndNeverKey() {
        ExportRegistry registry = registryOf(typicalClosure(), "v", NOW);

        List<Dependency> unresolved = unresolved(resource(registry, ROOT));
        assertEquals(List.of("unsupported", "unsupported", "unresolved", "external", "unresolved"),
                unresolved.stream().map(Dependency::status).toList());
        Dependency fileInterceptor = unresolved.get(0);
        assertEquals("/interceptors/0", fileInterceptor.pointer());
        assertEquals("file", fileInterceptor.target().origin());
        assertEquals("model", fileInterceptor.kind());
        assertNull(fileInterceptor.valueKind());
        assertEquals("short-name", unresolved.get(2).valueKind());
        assertEquals("external-url", unresolved.get(3).valueKind());
        assertNull(unresolved.get(3).target());
        assertNull(unresolved.get(3).kind(), "the type of a target that is not identified is not guessed");
        assertTrue(unresolved.stream().allMatch(dependency -> dependency.key() == null), "no unresolved reference names a resource");
    }

    @Test
    public void testSkippedEntityIsReportedByTypeAndSourceNeverByItsFreeTextReason() {
        ExportRegistry registry = registryOf(typicalClosure(), "v", NOW);

        Dependency missing = unresolved(resource(registry, ROOT)).get(4);
        assertEquals(1, missing.skipped().size());
        assertEquals("model", missing.skipped().get(0).type());
        assertEquals("api", missing.skipped().get(0).source());
        assertFalse(toJson(registry).contains("SECRET"),
                "a skip reason can hold an exception message");
    }

    @Test
    public void testSummaryHasExactlyTheThreeEdgeKeysAndCountsTheDependenciesOfTheRegistry() {
        ExportRegistry registry = registryOf(typicalClosure(), "v", NOW);

        assertEquals(3, registry.summary().resources());
        assertEquals(0, registry.summary().files());
        assertEquals(registry.warnings().size(), registry.summary().warnings());
        assertEquals(List.of("unsupported", "external", "unresolved"), List.copyOf(registry.summary().edges().keySet()));
        assertEquals(Map.of("unsupported", 2, "external", 1, "unresolved", 2), registry.summary().edges());
    }

    @Test
    public void testPromptBodyWarningIsEmittedOnlyWhenPromptIsInTheClosure() {
        List<ClosureNode> nodes = new ArrayList<>(closureOf(List.of(), NO_MARKERS).nodes());
        nodes.add(ExportTestNodes.node(ExportTestNodes.KEEPS_THE_BODY, descriptor(ResourceTypes.PROMPT, "public", "p"), false, "{}", null));

        ExportRegistry with = registryOf(new Closure(nodes, List.of()), "v", NOW);
        ExportRegistry without = registryOf(closureOf(List.of(), NO_MARKERS), "v", NOW);

        assertTrue(with.warnings().stream().anyMatch(warning -> warning.contains("body of an exported Prompt is exported as stored")));
        assertTrue(without.warnings().stream().noneMatch(warning -> warning.contains("Prompt")));
    }

    @Test
    public void testIssueIsListedWithItsResourceKindAndPointerAndMakesTheDiscoveryIncomplete() {
        MarkerCollection invalid = new MarkerCollection(List.of(),
                List.of(new CollectionIssue(CollectionIssueKind.INSTANCE_INVALID, "/x", "Violated keyword: type")));

        ExportRegistry registry = registryOf(closureOf(List.of(), invalid), "v", NOW);

        assertFalse(registry.discovery().complete());
        assertEquals(1, registry.discovery().issues().size());
        assertEquals("/catalog_properties/x", registry.discovery().issues().get(0).pointer());
        assertEquals("instance-invalid", registry.discovery().issues().get(0).kind());
        assertEquals("application:platform:root", registry.discovery().issues().get(0).resource());
    }

    @Test
    public void testDiscoveryIsIncompleteWhenSchemaCouldNotBeEvaluatedAndTheConditionIsAnEdgeNotAnIssue() {
        MarkerCollection schemaProblem = new MarkerCollection(List.of(),
                List.of(new CollectionIssue(CollectionIssueKind.SCHEMA_UNAVAILABLE, "", "The schema cannot be evaluated")));

        ExportRegistry withSchemaProblem = registryOf(closureOf(List.of(), schemaProblem), "v", NOW);

        assertFalse(withSchemaProblem.discovery().complete());
        assertTrue(withSchemaProblem.discovery().issues().isEmpty(), "a schema condition is an edge, never an issue");
    }

    @Test
    public void testRegistryHoldsEveryResourceTypeWithDistinctIdentitiesAndEntryNames() {
        ResourceDescriptor toolset = descriptor(ResourceTypes.TOOL_SET, "platform", "root");
        ResourceDescriptor model = descriptor(ResourceTypes.MODEL, "platform", "root");
        ResourceDescriptor interceptor = descriptor(ResourceTypes.INTERCEPTOR, "platform", "root");
        ResourceDescriptor catalog = descriptor(ResourceTypes.CATALOG_SCHEMA, "platform", "root");
        ResourceDescriptor publicApplication = descriptor(ResourceTypes.APPLICATION, "public", "root");
        List<ClosureNode> nodes = List.of(
                ExportTestNodes.node(ROOT, true, "{}", DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, NO_MARKERS)),
                ExportTestNodes.node(toolset, false, "{}", null),
                ExportTestNodes.node(model, false, "{}", null),
                ExportTestNodes.node(interceptor, false, "{}", null),
                ExportTestNodes.node(catalog, false, "{\"$id\":\"https://dial.example/catalog\"}", null),
                ExportTestNodes.node(publicApplication, false, "{}", DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, NO_MARKERS)));
        List<ClosureEdge> edges = List.of(included("/application_properties/t", toolset), included("/application_properties/m", model),
                included("/interceptors/0", interceptor), included("/catalog_schema_id", catalog),
                included("/dependencies/0", publicApplication));

        ExportRegistry registry = registryOf(new Closure(nodes, edges), "v", NOW);

        List<Resource> resources = List.copyOf(registry.resources().values());
        assertEquals(List.of("application", "toolset", "model", "interceptor", "catalog-schema", "application"),
                resources.stream().map(Resource::type).toList());
        assertEquals(List.of("platform", "platform", "platform", "platform", "platform", "public"),
                resources.stream().map(Resource::origin).toList());
        assertEquals(6, resources.stream().map(Resource::entryName).distinct().count(), "no entry name collides");
        assertEquals(6, registry.resources().size(), "the same path in several types and origins gives distinct keys");
        assertEquals("https://dial.example/catalog", resources.get(4).schemaId());
        assertEquals(List.of("application:platform:root"), registry.roots());
        assertEquals("/application_properties/t", resources.get(0).dependencies().get(0).pointer());
        assertEquals(6, registry.summary().resources());
    }

    @Test
    public void testPublicStatusesAreExactlyTheFourAndHoldNoReasonOrLegacyStatus() {
        EdgeTarget target = new EdgeTarget(ResourceTypes.MODEL, "m", null, Origin.FILE, false);
        List<ClosureEdge> edges = List.of(
                included("/dependencies/0", DEPENDENCY),
                listed("/a", EdgeStatus.MISSING, ReferenceForm.SHORT_NAME, null, List.of()),
                listed("/b", EdgeStatus.UNRESOLVED_SHORT_NAME, ReferenceForm.SHORT_NAME, null, List.of()),
                listed("/c", EdgeStatus.ORIGIN_UNKNOWN, ReferenceForm.SHORT_NAME, null, List.of()),
                listed("/d", EdgeStatus.SCHEMA_UNAVAILABLE, null, target, List.of()),
                listed("/e", EdgeStatus.UNSUPPORTED, null, null, List.of()),
                listed("/f", EdgeStatus.EXTERNAL, ReferenceForm.EXTERNAL_URL, null, List.of()));

        ExportRegistry registry = registryOf(closureOf(edges, NO_MARKERS), "v", NOW);

        Set<String> statuses = new java.util.HashSet<>();
        registry.resources().values().forEach(resource -> resource.dependencies().forEach(dependency -> statuses.add(dependency.status())));
        assertEquals(Set.of("included", "unresolved", "unsupported", "external"), statuses);
        String json = toJson(registry);
        assertFalse(json.contains("\"reason\""), json);
        for (String legacy : List.of("missing", "unresolved-short-name", "origin-unknown")) {
            assertFalse(json.contains("\"status\":\"" + legacy + "\""), legacy);
        }
        assertEquals(Map.of("unsupported", 1, "external", 1, "unresolved", 4), registry.summary().edges());
    }

    @Test
    public void testDiscoveryLimitationWarningIsEmittedOnlyForAnUnresolvedShortName() {
        ExportRegistry none = registryOf(closureOf(List.of(), NO_MARKERS), "v", NOW);
        ExportRegistry missing = registryOf(closureOf(List.of(listed("/dependencies/0", EdgeStatus.MISSING, ReferenceForm.SHORT_NAME, null, List.of())),
                NO_MARKERS), "v", NOW);
        ExportRegistry unresolved = registryOf(closureOf(List.of(listed("/application_properties/n", EdgeStatus.UNRESOLVED_SHORT_NAME,
                ReferenceForm.SHORT_NAME, null, List.of())), NO_MARKERS), "v", NOW);

        assertTrue(none.warnings().isEmpty(), "not on every export");
        assertTrue(missing.warnings().isEmpty(), "a missing fixed reference is not an unmarked reference");
        assertEquals(1, unresolved.warnings().size());
        assertTrue(unresolved.warnings().get(0).startsWith("Only fixed fields and marked fields were examined"));
    }

    @Test
    public void testModelAndInterceptorDefinitionOnlyWarningIsSeparateAndOnlyForThem() {
        ExportRegistry model = registryOf(closureWith(ResourceTypes.MODEL), "v", NOW);
        ExportRegistry interceptor = registryOf(closureWith(ResourceTypes.INTERCEPTOR), "v", NOW);
        ExportRegistry toolset = registryOf(closureWith(ResourceTypes.TOOL_SET), "v", NOW);
        ExportRegistry application = registryOf(closureWith(ResourceTypes.APPLICATION), "v", NOW);

        for (ExportRegistry registry : List.of(model, interceptor)) {
            assertEquals(1, registry.warnings().size(), registry.warnings().toString());
            assertTrue(registry.warnings().get(0).startsWith("A Model or Interceptor is exported as its definition only"));
        }
        assertTrue(toolset.warnings().isEmpty(), "a ToolSet is not a definition-only type");
        assertTrue(application.warnings().isEmpty());
    }

    private static Closure closureWith(ResourceTypes type) {
        ResourceDescriptor dependency = descriptor(type, "platform", "x");
        return new Closure(List.of(ExportTestNodes.node(ROOT, true, "{}", DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, NO_MARKERS)),
                ExportTestNodes.node(dependency, false, "{}", null)), List.of());
    }

    @Test
    public void testFileEntityThatShadowsPlatformBlobIsWarnedWithItsOwnText() {
        EdgeTarget fileEntity = new EdgeTarget(ResourceTypes.MODEL, "m", DEPENDENCY, Origin.FILE, true);
        ExportRegistry registry = registryOf(closureOf(List.of(
                ClosureEdge.included(ROOT, discovered("/dependencies/0", null, null, fileEntity, List.of()), DEPENDENCY)), NO_MARKERS), "v", NOW);

        assertEquals(1, registry.warnings().size());
        assertTrue(registry.warnings().get(0).startsWith("A file entity shadows a platform entity"), registry.warnings().toString());
    }
}
