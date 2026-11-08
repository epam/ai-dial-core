package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeTarget;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureEdge;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ExportClosureWalkerTest {

    private static final MarkerCollection NO_MARKERS = new MarkerCollection(List.of(), List.of());

    @Mock
    private ResourceService resourceService;
    @Mock
    private DeploymentEdgeDiscovery discovery;

    private final Map<ResourceDescriptor, String> stored = new HashMap<>();
    private final Map<String, ExportDiscovery> edgesByEndpoint = new HashMap<>();
    private final AtomicInteger publicChecks = new AtomicInteger();
    private boolean publicReadable = true;

    @BeforeEach
    void setUp() {
        lenient().when(resourceService.getResourceWithMetadata(any(), any())).thenAnswer(invocation -> {
            String body = stored.get(invocation.<ResourceDescriptor>getArgument(0));
            return body == null ? null : Pair.of(new ResourceItemMetadata().setCreatedAt(1L).setUpdatedAt(2L), body);
        });
        lenient().when(discovery.discover(any(ResourceTypes.class), any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(edgesOf());
        lenient().when(discovery.discover(any(ResourceTypes.class), anyString())).thenAnswer(invocation -> {
            String endpoint = ProxyUtil.MAPPER.readTree((String) invocation.getArgument(1)).path("endpoint").asText();
            return edgesByEndpoint.getOrDefault(endpoint, edgesOf());
        });
    }

    private ExportClosureWalker walker() {
        return new ExportClosureWalker(resourceService, discovery, descriptor -> {
            publicChecks.incrementAndGet();
            return publicReadable;
        }, null, ExportLimits.defaults(), ExportLimits.Deadline.unbounded(), descriptor -> null, descriptor -> null);
    }

    private static ResourceDescriptor platform(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", name);
    }

    private static ResourceDescriptor publicApplication(String name) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "public", "public/", name);
    }

    private void givenStored(ResourceDescriptor descriptor, String endpoint) {
        stored.put(descriptor, "{\"endpoint\":\"" + endpoint + "\"}");
    }

    private static ExportDiscovery edgesOf(DiscoveredEdge... edges) {
        return DeploymentEdgeDiscovery.toDiscovery(List.of(edges), NO_MARKERS, NO_MARKERS);
    }

    private static DiscoveredEdge candidate(ResourceDescriptor target) {
        return new DiscoveredEdge("/dependencies/0", null, null,
                new EdgeTarget((ResourceTypes) target.getType(), target.getName(), target, Origin.PLATFORM, false), List.of());
    }

    private static DiscoveredEdge listed(EdgeStatus status) {
        return new DiscoveredEdge("/application_properties/x", status, null, null, List.of());
    }

    private static List<ResourceDescriptor> nodeDescriptors(Closure closure) {
        return closure.nodes().stream().map(ClosureNode::descriptor).toList();
    }

    private static EdgeStatus statusOf(ClosureEdge edge) {
        return edge instanceof ClosureEdge.Unresolved unresolved ? unresolved.status() : null;
    }

    @Test
    public void testBodyThatCannotBeParsedOrBoundFailsTheWalkAndLogsOnlyTheResource() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "broken-root");
        stored.put(root, "{\"endpoint\":\"SECRET-IN-BODY\"}");
        IllegalArgumentException failure = new IllegalArgumentException("Provided payload do not match required schema");
        lenient().when(discovery.discover(any(ResourceTypes.class), anyString())).thenThrow(failure);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExportClosureWalker.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> walker().walk(List.of(root)));

            assertTrue(thrown == failure, "the same exception is rethrown, so the answer stays the generic 500");
            assertEquals(1, appender.list.size());
            String logged = appender.list.get(0).getFormattedMessage();
            assertTrue(logged.contains(root.getUrl()), "the resource is named: " + logged);
            assertTrue(appender.list.get(0).getThrowableProxy() == null, "no exception, so no parser message");
            assertTrue(!logged.contains("SECRET-IN-BODY") && !logged.contains("Provided payload"), logged);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static ResourceDescriptor publicFile(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", path);
    }

    private static DiscoveredEdge fileEdge(ResourceDescriptor target) {
        return new DiscoveredEdge("/icon_url", null, null,
                new EdgeTarget(ResourceTypes.FILE, target.getName(), target, null, false), List.of());
    }

    private void givenFile(ResourceDescriptor file, long length, String etag) {
        lenient().when(resourceService.getResourceMetadata(file)).thenReturn(
                (ResourceItemMetadata) new FileMetadata(file, length, "image/png").setEtag(etag).setCreatedAt(11L).setUpdatedAt(22L));
    }

    @Test
    public void testFileNodeHoldsItsMetadataOnlyAndNoByteOfItsContentIsRead() throws Exception {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor file = publicFile("icons/a.png");
        givenStored(root, "root");
        givenFile(file, 1234, "etag-1");
        edgesByEndpoint.put("root", edgesOf(fileEdge(file)));

        Closure closure = walker().walk(List.of(root));

        ClosureNode node = closure.nodes().get(1);
        assertEquals(new ExportNodeContent.StreamedFile(node.content().identity(), 1234, "etag-1", "image/png", 11L, 22L), node.content(),
                "the content is not read, so a streamed file has no digest");
        assertEquals(1, publicChecks.get(), "the file is authorized through the public check before it is read");
        verify(resourceService, never()).getResourceWithMetadata(eq(file), any());
        verify(resourceService, never()).getResourceBytes(file);
        verify(resourceService, never()).getResourceStream(any(), any());
        assertTrue(closure.hasStreamedFiles());
        assertTrue(node.reportedIssues().isEmpty());
    }

    @Test
    public void testFileWithoutMetadataIsMissing() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor gone = publicFile("icons/gone.png");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(fileEdge(gone)));

        assertEquals(EdgeStatus.MISSING, statusOf(walker().walk(List.of(root)).edges().get(0)));
    }

    private static ResourceDescriptor publicSkill(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.SKILL, "public", "public/", path);
    }

    private static DiscoveredEdge skillEdge(ResourceDescriptor target) {
        return new DiscoveredEdge("/dependencies/0", null, null,
                new EdgeTarget(ResourceTypes.SKILL, target.getName(), target, null, false), List.of());
    }

    private static FolderResourceMarker markerOf(String version, String etag, Map<String, Long> sizes) {
        FolderResourceMarker marker = new FolderResourceMarker();
        marker.setCurrentVersion(version);
        marker.setEtag(etag);
        Map<String, FolderResourceMarker.ResourceFileMetadata> files = new HashMap<>();
        sizes.forEach((path, size) -> files.put(path, new FolderResourceMarker.ResourceFileMetadata(size, "etag-" + path)));
        marker.setFileMetadata(files);
        return marker;
    }

    private ExportClosureWalker skillWalker(java.util.function.Function<ResourceDescriptor, FolderResourceMarker> markers) {
        return new ExportClosureWalker(resourceService, discovery, descriptor -> {
            publicChecks.incrementAndGet();
            return publicReadable;
        }, null, ExportLimits.defaults(), ExportLimits.Deadline.unbounded(), descriptor -> null, markers);
    }

    @Test
    public void testSkillNodePinsTheVersionFromTheMarkerAndNoFileOfItIsRead() throws Exception {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor skill = publicSkill("s");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(skillEdge(skill)));
        FolderResourceMarker marker = markerOf("v1", "m1", Map.of("scripts/run.py", 10L, "SKILL.md", 20L));

        Closure closure = skillWalker(descriptor -> marker).walk(List.of(root));

        ClosureNode node = closure.nodes().get(1);
        ExportNodeContent.Complex complex = (ExportNodeContent.Complex) node.content();
        assertEquals("v1", complex.versionId());
        assertEquals("m1", complex.markerEtag());
        assertEquals(List.of(new ExportNodeContent.ComplexFile("SKILL.md", 20, "etag-SKILL.md"),
                new ExportNodeContent.ComplexFile("scripts/run.py", 10, "etag-scripts/run.py")), complex.files(),
                "the files are in the order of their relative paths");
        assertEquals(1, publicChecks.get(), "the Skill is authorized through the public check");
        verify(resourceService, never()).getResourceWithMetadata(eq(skill), any());
        verify(resourceService, never()).getResourceMetadata(skill);
        verify(resourceService, never()).getResourceStream(any(), any());
        assertTrue(closure.hasStreamedFiles());
    }

    @Test
    public void testSkillWithoutMarkerOrWithoutFilesIsMissing() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor skill = publicSkill("s");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(skillEdge(skill)));

        assertEquals(EdgeStatus.MISSING, statusOf(skillWalker(descriptor -> null).walk(List.of(root)).edges().get(0)));
        assertEquals(EdgeStatus.MISSING, statusOf(skillWalker(descriptor -> markerOf("v1", "m1", Map.of())).walk(List.of(root)).edges().get(0)));
    }

    @Test
    public void testDependencyApplicationsAreFollowedRecursivelyInBreadthFirstOrder() {
        ResourceDescriptor a = platform(ResourceTypes.APPLICATION, "a");
        ResourceDescriptor b = platform(ResourceTypes.APPLICATION, "b");
        ResourceDescriptor c = platform(ResourceTypes.APPLICATION, "c");
        ResourceDescriptor d = platform(ResourceTypes.APPLICATION, "d");
        givenStored(a, "a");
        givenStored(b, "b");
        givenStored(c, "c");
        givenStored(d, "d");
        edgesByEndpoint.put("a", edgesOf(candidate(b), candidate(c)));
        edgesByEndpoint.put("b", edgesOf(candidate(d)));

        Closure closure = walker().walk(List.of(a));

        assertEquals(List.of(a, b, c, d), nodeDescriptors(closure));
        assertEquals(List.of(true, false, false, false), closure.nodes().stream().map(ClosureNode::isRoot).toList());
        assertEquals(List.of(b, c, d), closure.edges().stream().map(edge -> ((ClosureEdge.Included) edge).includedNode()).toList());
    }

    @Test
    public void testCycleBetweenApplicationsTerminatesWithEachNodeOnce() {
        ResourceDescriptor a = platform(ResourceTypes.APPLICATION, "a");
        ResourceDescriptor b = platform(ResourceTypes.APPLICATION, "b");
        givenStored(a, "a");
        givenStored(b, "b");
        edgesByEndpoint.put("a", edgesOf(candidate(b)));
        edgesByEndpoint.put("b", edgesOf(candidate(a)));

        Closure closure = walker().walk(List.of(a));

        assertEquals(List.of(a, b), nodeDescriptors(closure));
        assertEquals(2, closure.edges().size());
        assertEquals(a, ((ClosureEdge.Included) closure.edges().get(1)).includedNode(), "the edge back to the root ends in the existing node");
        verify(resourceService, times(1)).getResourceWithMetadata(eq(a), any());
    }

    @Test
    public void testSameResourceByShortNameFormAndByUrlFormIsOneNodeReadOnce() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor byShortName = platform(ResourceTypes.APPLICATION, "x");
        ResourceDescriptor byUrl = ResourceDescriptorFactory.fromAnyUrl("applications/platform/x", null);
        givenStored(root, "root");
        givenStored(byShortName, "x");
        edgesByEndpoint.put("root", edgesOf(candidate(byShortName), candidate(byUrl)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(byShortName, byUrl, "the two forms resolve to equal descriptors");
        assertEquals(List.of(root, byShortName), nodeDescriptors(closure));
        verify(resourceService, times(1)).getResourceWithMetadata(eq(byShortName), any());
    }

    @Test
    public void testRootThatIsAlsoDependencyIsOneRootNode() {
        ResourceDescriptor first = platform(ResourceTypes.APPLICATION, "first");
        ResourceDescriptor second = platform(ResourceTypes.APPLICATION, "second");
        givenStored(first, "first");
        givenStored(second, "second");
        edgesByEndpoint.put("first", edgesOf(candidate(second)));

        Closure closure = walker().walk(List.of(first, second, first));

        assertEquals(List.of(first, second), nodeDescriptors(closure));
        assertTrue(closure.nodes().stream().allMatch(ClosureNode::isRoot));
        assertEquals(second, ((ClosureEdge.Included) closure.edges().get(0)).includedNode());
        verify(resourceService, times(1)).getResourceWithMetadata(eq(second), any());
    }

    @Test
    public void testMissingDependencyIsMissingEdgeAndNoNode() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor gone = platform(ResourceTypes.APPLICATION, "gone");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(candidate(gone)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(List.of(root), nodeDescriptors(closure));
        assertEquals(EdgeStatus.MISSING, ((ClosureEdge.Unresolved) closure.edges().get(0)).status());
    }

    @Test
    public void testEdgesWithStatusAreKeptAsTheyAreAndNothingIsRead() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(listed(EdgeStatus.UNSUPPORTED), listed(EdgeStatus.EXTERNAL),
                listed(EdgeStatus.ORIGIN_UNKNOWN), listed(EdgeStatus.SCHEMA_UNAVAILABLE)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(List.of(EdgeStatus.UNSUPPORTED, EdgeStatus.EXTERNAL, EdgeStatus.ORIGIN_UNKNOWN, EdgeStatus.SCHEMA_UNAVAILABLE),
                closure.unresolvedEdges().stream().map(ClosureEdge.Unresolved::status).toList());
        verify(resourceService, times(1)).getResourceWithMetadata(any(), any());
    }

    @Test
    public void testDependencyThatCannotBeBoundIsKeptWithAnIncompleteDiscoveryAndItsInterceptorIsNotFollowed() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor toolset = platform(ResourceTypes.TOOL_SET, "broken-toolset");
        ResourceDescriptor interceptor = platform(ResourceTypes.INTERCEPTOR, "never-reached");
        givenStored(root, "root");
        givenStored(toolset, "broken");
        givenStored(interceptor, "interceptor");
        edgesByEndpoint.put("root", edgesOf(candidate(toolset)));
        lenient().when(discovery.discover(eq(ResourceTypes.TOOL_SET), anyString()))
                .thenThrow(new IllegalArgumentException("Provided payload do not match required schema"));

        Closure closure = walker().walk(List.of(root));

        assertEquals(List.of(root, toolset), nodeDescriptors(closure), "the dependency is exported and nothing behind it is read");
        assertFalse(closure.isComplete(), "its references could not be read, so the discovery is incomplete");
        ClosureNode broken = closure.nodes().get(1);
        assertEquals(List.of(CollectionIssueKind.INSTANCE_INVALID), broken.discovery().issues().stream().map(issue -> issue.kind()).toList());
        assertEquals("", broken.discovery().issues().get(0).pointer());
        assertEquals(List.of(toolset), closure.edges().stream().map(edge -> ((ClosureEdge.Included) edge).includedNode()).toList());
    }

    @ParameterizedTest
    @EnumSource(value = ResourceTypes.class, names = {"TOOL_SET", "MODEL"})
    public void testApplicationChainThroughDeploymentAndInterceptorIsFollowedToTheEnd(ResourceTypes middleType) {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor middle = platform(middleType, "t");
        ResourceDescriptor interceptor = platform(ResourceTypes.INTERCEPTOR, "i");
        ResourceDescriptor icon = publicFile("icons/i.png");
        givenStored(root, "root");
        givenStored(middle, "middle");
        givenStored(interceptor, "interceptor");
        givenFile(icon, 5, "etag-icon");
        edgesByEndpoint.put("root", edgesOf(candidate(middle)));
        edgesByEndpoint.put("middle", edgesOf(candidate(interceptor)));
        edgesByEndpoint.put("interceptor", edgesOf(fileEdge(icon)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(List.of(root, middle, interceptor, icon), nodeDescriptors(closure));
        assertEquals(List.of(middle, interceptor, icon), closure.edges().stream().map(edge -> ((ClosureEdge.Included) edge).includedNode()).toList());
        assertTrue(closure.nodes().subList(0, 3).stream().allMatch(node -> node.discovery() != null), "the three Deployments are examined");
        assertNull(closure.nodes().get(3).discovery(), "a File is a leaf");
        verify(discovery, times(1)).discover(middleType, "{\"endpoint\":\"middle\"}");
        verify(discovery, times(1)).discover(ResourceTypes.INTERCEPTOR, "{\"endpoint\":\"interceptor\"}");
    }

    @Test
    public void testPromptAndSchemaAreLeavesThatAreReadButNotExamined() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor prompt = ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROMPT, "public", "public/", "p");
        ResourceDescriptor catalogSchema = platform(ResourceTypes.CATALOG_SCHEMA, "c");
        givenStored(root, "root");
        stored.put(prompt, "{\"endpoint\":\"prompt\"}");
        stored.put(catalogSchema, "{\"$id\":\"https://x/c\"}");
        edgesByEndpoint.put("root", edgesOf(candidate(prompt), candidate(catalogSchema)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(List.of(root, prompt, catalogSchema), nodeDescriptors(closure));
        assertTrue(closure.nodes().subList(1, 3).stream().allMatch(node -> node.discovery() == null));
        verify(discovery, times(1)).discover(any(ResourceTypes.class), anyString());
        assertTrue(closure.hasAsStoredBodies());
    }

    @Test
    public void testPublicApplicationDependencyIsCheckedThenReadAndFollowed() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor publicDependency = publicApplication("shared__1.0.0");
        ResourceDescriptor below = platform(ResourceTypes.APPLICATION, "below");
        givenStored(root, "root");
        givenStored(publicDependency, "shared");
        givenStored(below, "below");
        edgesByEndpoint.put("root", edgesOf(candidate(publicDependency)));
        edgesByEndpoint.put("shared", edgesOf(candidate(below)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(List.of(root, publicDependency, below), nodeDescriptors(closure));
        assertEquals(1, publicChecks.get(), "only the public dependency is checked");
    }

    @Test
    public void testPublicDependencyThatTheCallerCannotReadFailsClosedInsteadOfBeingSilentlyDropped() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor publicDependency = publicApplication("shared__1.0.0");
        givenStored(root, "root");
        givenStored(publicDependency, "shared");
        edgesByEndpoint.put("root", edgesOf(candidate(publicDependency)));
        publicReadable = false;

        HttpException denied = assertThrows(HttpException.class, () -> walker().walk(List.of(root)));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        verify(resourceService, never()).getResourceWithMetadata(eq(publicDependency), any());
    }

    private ExportClosureWalker walkerWithFileEntities(java.util.function.Function<ResourceDescriptor, com.fasterxml.jackson.databind.JsonNode> fileEntities) {
        return new ExportClosureWalker(resourceService, discovery, descriptor -> true, null, ExportLimits.defaults(), ExportLimits.Deadline.unbounded(),
                fileEntities, descriptor -> null);
    }

    private ResourceDescriptor givenRootWithFileModel() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor fileModel = ExportFileEntities.descriptorOf(ResourceTypes.MODEL, "m");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(new DiscoveredEdge("/dependencies/0", null, null,
                new EdgeTarget(ResourceTypes.MODEL, "m", fileModel, Origin.FILE, false), List.of())));
        return root;
    }

    @Test
    public void testFileEntityThatCannotBeRepresentedEndsThePlanAndIsNotSwallowed() {
        ResourceDescriptor root = givenRootWithFileModel();

        assertThrows(IllegalStateException.class, () -> walkerWithFileEntities(descriptor -> {
            throw new IllegalStateException("The entity cannot be represented");
        }).walk(List.of(root)));
    }

    @Test
    public void testFileEntityThatTheFileConfigurationNoLongerHoldsIsMissing() {
        ResourceDescriptor root = givenRootWithFileModel();

        Closure closure = walkerWithFileEntities(descriptor -> null).walk(List.of(root));

        assertEquals(1, closure.nodes().size());
        assertEquals(EdgeStatus.MISSING, statusOf(closure.edges().get(0)));
    }

    @Test
    public void testMissingRootIsNotFound() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "nobody");

        assertThrows(ResourceNotFoundException.class, () -> walker().walk(List.of(root)));
    }

    @Test
    public void testRootThatIsNotAnApplicationIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> walker().walk(List.of(platform(ResourceTypes.TOOL_SET, "t"))));
    }

    @Test
    public void testStoredApplicationWithoutContentIsAnErrorNotSkippedNode() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        stored.put(root, "");

        assertThrows(IllegalStateException.class, () -> walker().walk(List.of(root)));
    }

    @Test
    public void testDependencyOfTypeThatIsNotBoundToItsBucketIsUnsupportedAndNothingIsRead() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "root");
        ResourceDescriptor model = ResourceDescriptorFactory.fromDecoded(ResourceTypes.MODEL, "public", "public/", "m");
        givenStored(root, "root");
        edgesByEndpoint.put("root", edgesOf(candidate(model)));

        Closure closure = walker().walk(List.of(root));

        assertEquals(EdgeStatus.UNSUPPORTED, statusOf(closure.edges().get(0)));
        assertEquals(List.of(root), nodeDescriptors(closure));
        verify(resourceService, never()).getResourceWithMetadata(eq(model), any());
        assertEquals(0, publicChecks.get(), "the binding is checked before the caller's access");
    }

    private ExportClosureWalker limitedWalker(ExportLimits limits, ExportLimits.Deadline deadline) {
        return new ExportClosureWalker(resourceService, discovery, descriptor -> true, null, limits, deadline, descriptor -> null,
                descriptor -> null);
    }

    private static ExportLimits limits(int nodes, int depth, int edges, long resourceChars, long retainedChars) {
        return new ExportLimits(100, nodes, depth, edges, resourceChars, retainedChars, java.time.Duration.ofMinutes(1));
    }

    private ResourceDescriptor chainOf(int length) {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "app0");
        ResourceDescriptor previous = root;
        givenStored(root, "app0");
        for (int i = 1; i < length; i++) {
            ResourceDescriptor next = platform(ResourceTypes.APPLICATION, "app" + i);
            givenStored(next, "app" + i);
            edgesByEndpoint.put("app" + (i - 1), edgesOf(candidate(next)));
            previous = next;
        }
        return root;
    }

    private static void assertLimit(HttpStatus status, Executable action) {
        HttpException failure = assertThrows(HttpException.class, action);
        assertEquals(status, failure.getStatus());
    }

    @Test
    public void testClosureOverTheNodeLimitFailsInsteadOfBeingCut() {
        ResourceDescriptor root = chainOf(3);

        assertEquals(3, limitedWalker(limits(3, 10, 100, 1000, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)).nodes().size());
        assertLimit(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                () -> limitedWalker(limits(2, 10, 100, 1000, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)));
    }

    @Test
    public void testChainLongerThanTheDepthLimitFails() {
        ResourceDescriptor root = chainOf(4);

        assertEquals(4, limitedWalker(limits(10, 3, 100, 1000, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)).nodes().size());
        assertLimit(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                () -> limitedWalker(limits(10, 2, 100, 1000, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)));
    }

    @Test
    public void testMoreReferencesThanTheEdgeLimitFail() {
        ResourceDescriptor root = platform(ResourceTypes.APPLICATION, "app0");
        givenStored(root, "app0");
        edgesByEndpoint.put("app0", edgesOf(listed(EdgeStatus.EXTERNAL), listed(EdgeStatus.EXTERNAL), listed(EdgeStatus.EXTERNAL)));

        assertEquals(3, limitedWalker(limits(10, 10, 3, 1000, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)).edges().size());
        assertLimit(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                () -> limitedWalker(limits(10, 10, 2, 1000, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)));
    }

    @Test
    public void testBodyLongerThanTheResourceLimitFailsWhetherRootOrDependency() {
        ResourceDescriptor root = chainOf(2);
        int rootLength = stored.get(root).length();

        assertLimit(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                () -> limitedWalker(limits(10, 10, 100, rootLength - 1, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)));
        stored.put(platform(ResourceTypes.APPLICATION, "app1"), "{\"endpoint\":\"app1\",\"padding\":\"" + "x".repeat(200) + "\"}");
        assertLimit(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                () -> limitedWalker(limits(10, 10, 100, 100, 10_000), ExportLimits.Deadline.unbounded()).walk(List.of(root)));
    }

    @Test
    public void testBodiesThatEachFitButTogetherExceedTheRetainedLimitFail() {
        ResourceDescriptor root = chainOf(3);
        long total = stored.values().stream().mapToLong(String::length).sum();

        assertEquals(3, limitedWalker(limits(10, 10, 100, 1000, total), ExportLimits.Deadline.unbounded()).walk(List.of(root)).nodes().size());
        assertLimit(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                () -> limitedWalker(limits(10, 10, 100, 1000, total - 1), ExportLimits.Deadline.unbounded()).walk(List.of(root)));
    }

    @Test
    public void testExpiredDeadlineFailsBeforeAnythingIsDiscovered() {
        ResourceDescriptor root = chainOf(2);

        assertLimit(HttpStatus.GATEWAY_TIMEOUT, () -> limitedWalker(limits(10, 10, 100, 1000, 10_000),
                new ExportLimits.Deadline(System.nanoTime() - 1)).walk(List.of(root)));
        org.mockito.Mockito.verify(discovery, org.mockito.Mockito.never()).discover(any(ResourceTypes.class), anyString());
    }
}
