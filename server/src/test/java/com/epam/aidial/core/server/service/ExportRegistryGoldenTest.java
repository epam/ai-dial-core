package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeTarget;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureEdge;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Skipped;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CollectionIssue;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the serialized registry of an Application export, byte for byte, as it is written to the second entry of the ZIP. A
 * change of a field name, nesting, value, null handling, order, timestamp
 * format or constant makes these tests fail. The test goes through {@code ExportArchive.prepare(...).writeTo(...)}, the path of
 * production, and reads the entry back from the archive.
 *
 * <p>The full fixture holds two roots (one platform, one public with a folder and a version), a dependency that both roots
 * reach, every kind of resource that can be exported, an edge of every status that the registry lists, two
 * skipped entities, issues that do and do not hide references, the two warnings that the fixture raises and a timestamp with nanoseconds.
 * The minimal fixture is one root with no edges, so that empty collections and the constant fields are pinned on their own.
 */
public class ExportRegistryGoldenTest {

    private static final String COMPLETE_GOLDEN = "/export-registry-golden.json";
    private static final String MINIMAL_GOLDEN = "/export-registry-minimal-golden.json";
    private static final String CORE_VERSION = "1.2.3";
    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05.678901234Z");
    private static final MarkerCollection NO_MARKERS = new MarkerCollection(List.of(), List.of());

    private static final ResourceDescriptor ROOT_A = descriptor(ResourceTypes.APPLICATION, "platform", "root-a");
    private static final ResourceDescriptor ROOT_P = descriptor(ResourceTypes.APPLICATION, "public", "folder/root-p__1.0.0");
    private static final ResourceDescriptor SHARED = descriptor(ResourceTypes.APPLICATION, "platform", "shared");
    private static final ResourceDescriptor TOOLS = descriptor(ResourceTypes.TOOL_SET, "platform", "tools");
    private static final ResourceDescriptor MODEL = descriptor(ResourceTypes.MODEL, "platform", "model-m");
    private static final ResourceDescriptor SCHEMA = descriptor(ResourceTypes.APP_TYPE_SCHEMA, "platform", "schema-blob");
    private static final ResourceDescriptor CATALOG = descriptor(ResourceTypes.CATALOG_SCHEMA, "platform", "catalog-blob");
    private static final ResourceDescriptor INTERCEPTOR = descriptor(ResourceTypes.INTERCEPTOR, "platform", "icpt");
    private static final ResourceDescriptor OTHER_PUBLIC = descriptor(ResourceTypes.APPLICATION, "public", "other");

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, bucket + "/", path);
    }

    private static ClosureEdge included(ResourceDescriptor from, String pointer, ResourceDescriptor to) {
        EdgeTarget target = new EdgeTarget((ResourceTypes) to.getType(), to.getName(), to, Origin.PLATFORM, false);
        return ClosureEdge.included(from, new DiscoveredEdge(pointer, null, null, target, List.of()), to);
    }

    private static ClosureEdge listed(ResourceDescriptor from, String pointer, EdgeStatus status, ReferenceForm kind, EdgeTarget target,
                                      List<Skipped> skipped) {
        return ClosureEdge.unresolved(from, new DiscoveredEdge(pointer, status, kind, target, skipped), status);
    }

    private static CollectionIssue issue(CollectionIssueKind kind, String pointer) {
        return new CollectionIssue(kind, pointer, "internal text that is never exported");
    }

    private static Closure completeClosure() {
        EdgeTarget fileModel = new EdgeTarget(ResourceTypes.MODEL, "file-model", null, Origin.FILE, false);
        EdgeTarget schemaTarget = new EdgeTarget(ResourceTypes.APP_TYPE_SCHEMA, "https://dial.example/schemas/quick:apps", SCHEMA,
                Origin.PLATFORM, false);
        EdgeTarget otherPublic = new EdgeTarget(ResourceTypes.APPLICATION, "other", OTHER_PUBLIC, null, false);
        List<Skipped> skipped = List.of(
                new Skipped(ResourceTypes.MODEL, "models/platform/m", "api", "reason that is never exported"),
                new Skipped(ResourceTypes.TOOL_SET, "toolsets/platform/x", "file", "another reason that is never exported"));

        List<ClosureEdge> edges = new ArrayList<>(List.of(
                included(ROOT_A, "/application_type_schema_id", SCHEMA),
                included(ROOT_A, "/dependencies/0", SHARED),
                included(ROOT_A, "/catalog_properties/tool", TOOLS),
                included(ROOT_A, "/dependencies/1", MODEL),
                included(ROOT_A, "/catalog_schema_id", CATALOG),
                included(ROOT_A, "/interceptors/0", INTERCEPTOR),
                listed(ROOT_A, "/dependencies/2", EdgeStatus.MISSING, ReferenceForm.SHORT_NAME, null, skipped),
                listed(ROOT_A, "/interceptors/1", EdgeStatus.UNSUPPORTED, null, fileModel, List.of()),
                listed(ROOT_A, "/catalog_properties/folder", EdgeStatus.UNSUPPORTED, null, null, List.of()),
                listed(ROOT_A, "/catalog_properties/url", EdgeStatus.EXTERNAL, ReferenceForm.EXTERNAL_URL, null, List.of()),
                listed(ROOT_A, "/catalog_properties/name", EdgeStatus.UNRESOLVED_SHORT_NAME, ReferenceForm.SHORT_NAME, null, List.of()),
                listed(ROOT_A, "/catalog_properties/bad", EdgeStatus.UNRESOLVED_SHORT_NAME, ReferenceForm.MALFORMED, null, List.of()),
                listed(ROOT_A, "/dependencies/3", EdgeStatus.ORIGIN_UNKNOWN, ReferenceForm.SHORT_NAME, null, List.of()),
                included(ROOT_P, "/dependencies/0", SHARED),
                listed(ROOT_P, "/application_type_schema_id", EdgeStatus.SCHEMA_UNAVAILABLE, null, schemaTarget, List.of()),
                listed(ROOT_P, "/dependencies/1", EdgeStatus.UNSUPPORTED, null, otherPublic, List.of())));

        MarkerCollection catalogMarkersOfRootA = new MarkerCollection(List.of(), List.of(
                issue(CollectionIssueKind.INSTANCE_INVALID, "/b"), issue(CollectionIssueKind.INSTANCE_INVALID, "/a"),
                issue(CollectionIssueKind.INSTANCE_INVALID, "/c")));
        MarkerCollection markersOfRootP = MarkerCollection.ofIssue(CollectionIssueKind.SCHEMA_UNAVAILABLE);

        List<ClosureNode> nodes = List.of(
                FIXTURE.add(ROOT_A, true, "{\"endpoint\":\"a\"}",
                        DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, catalogMarkersOfRootA)),
                FIXTURE.add(ROOT_P, true, "{\"endpoint\":\"p\"}", DeploymentEdgeDiscovery.toDiscovery(List.of(), markersOfRootP, NO_MARKERS)),
                FIXTURE.add(SHARED, false, "{\"endpoint\":\"shared\"}", DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, NO_MARKERS)),
                FIXTURE.add(TOOLS, false, "{\"endpoint\":\"tools\"}", null),
                FIXTURE.add(MODEL, false, "{\"endpoint\":\"model\"}", null),
                FIXTURE.add(SCHEMA, false, "{\"$id\":\"https://dial.example/schemas/quick:apps\"}", null),
                FIXTURE.add(CATALOG, false, "{\"$id\":\"https://dial.example/catalog/main\"}", null),
                FIXTURE.add(INTERCEPTOR, false, "{\"endpoint\":\"icpt\"}", null));
        return new Closure(nodes, edges);
    }

    private static Closure minimalClosure() {
        return new Closure(List.of(FIXTURE.add(ROOT_A, true, "{\"endpoint\":\"a\"}",
                DeploymentEdgeDiscovery.toDiscovery(List.of(), NO_MARKERS, NO_MARKERS))), List.of());
    }

    /** Keeps every body as it is: the registry holds no redaction information, so the golden files do not depend on what is removed. */
    private static final ExportTestNodes FIXTURE = new ExportTestNodes(ExportTestNodes.KEEPS_THE_BODY);

    private static byte[] registryEntryOf(Closure closure) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive.prepare(closure, ExportTestNodes.NO_CONTENT, CORE_VERSION, CREATED_AT, ExportLimits.Deadline.unbounded())
                .writeTo(out, ExportLimits.Deadline.unbounded());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals(ExportArchive.MANIFEST_ENTRY, zip.getNextEntry().getName(), "the technical manifest is the first entry");
            ZipEntry second = zip.getNextEntry();
            assertEquals(ExportArchive.REGISTRY_ENTRY, second.getName(), "the registry is the second entry");
            return zip.readAllBytes();
        }
    }

    private static byte[] golden(String resource) throws IOException {
        try (InputStream in = ExportRegistryGoldenTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    private static void assertSameBytes(String resource, byte[] actual) throws IOException {
        byte[] expected = golden(resource);
        // the text first, so that a difference is readable; the bytes are the check
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8), resource);
        assertArrayEquals(expected, actual, resource);
    }

    @Test
    public void testRegistryOfTheCompleteFixtureIsByteForByteTheGoldenFile() throws IOException {
        assertSameBytes(COMPLETE_GOLDEN, registryEntryOf(completeClosure()));
    }

    @Test
    public void testRegistryOfOneRootWithNoDependenciesIsByteForByteTheMinimalGoldenFile() throws IOException {
        assertSameBytes(MINIMAL_GOLDEN, registryEntryOf(minimalClosure()));
    }
}
