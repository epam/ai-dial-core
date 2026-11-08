package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureEdge;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExportArchiveTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final ExportLimits.Deadline UNBOUNDED = ExportLimits.Deadline.unbounded();
    private static final String SECRET = "TOPSECRET-VALUE";

    private static final ResourceDescriptor ROOT = descriptor(ResourceTypes.APPLICATION, "root");
    private static final ResourceDescriptor TOOLSET = descriptor(ResourceTypes.TOOL_SET, "tools");
    private static final ResourceDescriptor MODEL = descriptor(ResourceTypes.MODEL, "m");
    private static final ResourceDescriptor INTERCEPTOR = descriptor(ResourceTypes.INTERCEPTOR, "i");
    private static final ResourceDescriptor SCHEMA = descriptor(ResourceTypes.APP_TYPE_SCHEMA, "schema-blob");

    private static ResourceDescriptor descriptor(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", name);
    }

    private static final Map<ResourceDescriptor, String> BODIES = bodies();

    private static Map<ResourceDescriptor, String> bodies() {
        Map<ResourceDescriptor, String> bodies = new LinkedHashMap<>();
        bodies.put(ROOT, "{\"endpoint\":\"root\"}");
        bodies.put(TOOLSET, "{\"endpoint\":\"x\",\"auth_settings\":{\"client_secret\":\"" + SECRET + "\"}}");
        bodies.put(MODEL, "{\"type\":\"chat\"}");
        bodies.put(INTERCEPTOR, "{\"endpoint\":\"i\"}");
        bodies.put(SCHEMA, "{\"$id\":\"https://dial.example/schemas/quick:apps\"}");
        return bodies;
    }

    private static Closure closure() {
        return closure(new KeepingRedactor());
    }

    /** Every body is redacted when its node is made, and the node keeps the redacted bytes and no body. */
    private static Closure closure(BodyRedactor redactor) {
        List<ClosureNode> nodes = new ArrayList<>();
        BODIES.forEach((descriptor, body) -> nodes.add(ExportTestNodes.node(redactor, descriptor, descriptor.equals(ROOT), body,
                descriptor.equals(ROOT) ? new ExportDiscovery(List.of(), List.of()) : null)));
        List<ClosureEdge> edges = new ArrayList<>();
        for (ResourceDescriptor dependency : List.of(TOOLSET, MODEL, INTERCEPTOR, SCHEMA)) {
            edges.add(ClosureEdge.included(ROOT, new DiscoveredEdge("/dependencies/0", null, null, null, List.of()), dependency));
        }
        return new Closure(nodes, edges);
    }

    /** Writes the stored bytes unchanged: the redaction policy is not part of what these tests are about. */
    private static class KeepingRedactor implements BodyRedactor {
        @Override
        public byte[] redact(ExportResourceIdentity identity, String storedBody) {
            return storedBody.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Removes the client secret of a toolset, as an approved policy would. */
    private static class SecretRemovingRedactor extends KeepingRedactor {
        @Override
        public byte[] redact(ExportResourceIdentity identity, String storedBody) {
            byte[] unchanged = super.redact(identity, storedBody);
            if (!"toolset".equals(identity.type())) {
                return unchanged;
            }
            try {
                ObjectNode node = (ObjectNode) ProxyUtil.MAPPER.readTree(storedBody);
                ((ObjectNode) node.get("auth_settings")).remove("client_secret");
                return ProxyUtil.MAPPER.writeValueAsBytes(node);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static byte[] archiveOf(BodyRedactor redactor, Instant createdAt) throws IOException {
        return archiveOf(closure(redactor), createdAt);
    }

    private static byte[] archiveOf(Closure closure, Instant createdAt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive.prepare(closure, null, "1.2.3", createdAt, UNBOUNDED).writeTo(out, UNBOUNDED);
        return out.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertTrue(entry.getTime() < 347_155_200_000L, "a fixed time in 1980: " + entry.getName());
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    public void testManifestIsFirstResourcesFollowInClosureOrderAndVerificationIsLast() throws Exception {
        Map<String, byte[]> entries = unzip(archiveOf(new KeepingRedactor(), NOW));

        List<String> names = List.copyOf(entries.keySet());
        String sidecar = AdminSystemExportService.METADATA_ENTRY_PREFIX;
        assertEquals(ExportArchive.MANIFEST_ENTRY, names.get(0));
        assertEquals(ExportArchive.REGISTRY_ENTRY, names.get(1));
        assertEquals(".dial-export/verification.json", names.get(names.size() - 1));
        assertEquals(List.of(sidecar + "platform/applications/root", "platform/applications/root",
                sidecar + "platform/toolsets/tools", "platform/toolsets/tools",
                sidecar + "platform/models/m", "platform/models/m",
                sidecar + "platform/interceptors/i", "platform/interceptors/i"), names.subList(2, 10));
        assertEquals(sidecar + "platform/app_type_schemas/schema-blob", names.get(10));
        assertEquals("platform/app_type_schemas/schema-blob", names.get(11));
        assertEquals(13, names.size());
    }

    @Test
    public void testEntryNamesHoldNoColonOrPercentAndTheRegistryNamesTheEntriesOfTheArchive() throws Exception {
        Map<String, byte[]> entries = unzip(archiveOf(new KeepingRedactor(), NOW));

        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains(":") || name.contains("%")), entries.keySet().toString());
        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
        assertEquals("dial-export-dependency-registry", registry.get("kind").asText());
        List<String> registryNames = new ArrayList<>();
        registry.get("resources").forEach(resource -> registryNames.add(resource.get("entryName").asText()));
        assertEquals(registryNames, entries.keySet().stream()
                        .filter(name -> name.startsWith("platform/") || name.startsWith("public/")).toList(),
                "the registry names the entries that the archive holds");
    }

    @Test
    public void testTechnicalManifestIsTheOneOfTheSystemExportMarkedAsSelective() throws Exception {
        Map<String, byte[]> entries = unzip(archiveOf(new KeepingRedactor(), Instant.parse("2026-11-02T10:15:30.987654321Z")));

        JsonNode manifest = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.MANIFEST_ENTRY));
        assertEquals(".dial-system-export/manifest.json", ExportArchive.MANIFEST_ENTRY);
        assertEquals(List.of("formatVersion", "coreVersion", "createdAt", "mode"), List.copyOf(fieldNames(manifest)));
        assertEquals(2, manifest.get("formatVersion").asInt());
        assertEquals("1.2.3", manifest.get("coreVersion").asText());
        assertEquals("2026-11-02T10:15:30Z", manifest.get("createdAt").asText());
        assertEquals("selective", manifest.get("mode").asText());
    }

    @Test
    public void testEverySidecarPrecedesItsEntryAndHoldsOnlyTheContentTypeAndTheTimesOfTheNode() throws Exception {
        Map<String, byte[]> entries = unzip(archiveOf(new KeepingRedactor(), NOW));

        List<String> names = List.copyOf(entries.keySet());
        int sidecars = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name.startsWith(AdminSystemExportService.METADATA_ENTRY_PREFIX)) {
                sidecars++;
                assertEquals(name.substring(AdminSystemExportService.METADATA_ENTRY_PREFIX.length()), names.get(i + 1), "the entry follows its sidecar");
                JsonNode sidecar = ProxyUtil.MAPPER.readTree(entries.get(name));
                assertEquals(List.of("contentType"), List.copyOf(fieldNames(sidecar)), "no contentEncoding, no userMetadata, no dates: " + name);
                assertEquals("application/json", sidecar.get("contentType").asText());
            }
        }
        assertEquals(5, sidecars);

        // a node that knows its times: they are in the sidecar and not in the entry
        KeepingRedactor redactor = new KeepingRedactor();
        ClosureNode timedRoot = new ClosureNode(ROOT, true, ExportNodeContent.of(redactor, ROOT, BODIES.get(ROOT), 1_700_000_000_000L, 1_700_000_100_000L),
                new ExportDiscovery(List.of(), List.of()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive.prepare(new Closure(List.of(timedRoot), List.of()), null, "1.2.3", NOW, UNBOUNDED).writeTo(out, UNBOUNDED);

        Map<String, byte[]> timed = unzip(out.toByteArray());
        JsonNode timedSidecar = ProxyUtil.MAPPER.readTree(timed.get(".dial-system-export/metadata/platform/applications/root"));
        assertEquals(List.of("contentType", "creationDate", "lastModified"), List.copyOf(fieldNames(timedSidecar)));
        assertEquals("2023-11-14T22:13:20Z", timedSidecar.get("creationDate").asText());
        assertEquals("2023-11-14T22:15:00Z", timedSidecar.get("lastModified").asText());
        assertArrayEquals(BODIES.get(ROOT).getBytes(StandardCharsets.UTF_8), timed.get("platform/applications/root"),
                "the times are not part of the entry");
    }

    @Test
    public void testFileEntityIsWrittenAsFragmentWithNoSidecarAndTheRegistryHoldsItsSourcePointer() throws Exception {
        TreeKeepingRedactor redactor = new TreeKeepingRedactor();
        ResourceDescriptor fileModel = ExportFileEntities.descriptorOf(ResourceTypes.MODEL, "file-model");
        ResourceDescriptor fileSchema = ExportFileEntities.descriptorOfSchema(ResourceTypes.APP_TYPE_SCHEMA, "https://dial.example/s");
        JsonNode tree = ProxyUtil.MAPPER.readTree("{\"type\":\"CHAT\"}");
        List<ClosureNode> nodes = List.of(
                ExportTestNodes.node(redactor, ROOT, true, BODIES.get(ROOT), new ExportDiscovery(List.of(), List.of())),
                new ClosureNode(fileModel, false, ExportNodeContent.of(redactor, fileModel, tree), null),
                new ClosureNode(fileSchema, false, ExportNodeContent.of(redactor, fileSchema, tree), null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive.prepare(new Closure(nodes, List.of()), null, "v", NOW, UNBOUNDED).writeTo(out, UNBOUNDED);

        Map<String, byte[]> entries = unzip(out.toByteArray());
        assertTrue(entries.containsKey(".dial-export/config/model/file-model.json"));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains("file-model") && !name.startsWith(".dial-export/config/")),
                "no sidecar and no path under platform/ or public/: " + entries.keySet());
        assertTrue(entries.keySet().stream().noneMatch(name -> name.equals("aidial.config.json")));
        JsonNode resources = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY)).get("resources");
        JsonNode model = resources.get("model:file:file-model");
        assertEquals("/models/file-model", model.get("sourcePointer").asText());
        assertEquals(".dial-export/config/model/file-model.json", model.get("entryName").asText());
        assertFalse(resources.get("application:platform:root").has("sourcePointer"));
        String schemaPointer = resources.get("application-type-schema:file:" + ExportResourceIdentity.schemaDigest("https://dial.example/s"))
                .get("sourcePointer").asText();
        assertEquals("/applicationTypeSchemas/" + ExportResourceIdentity.schemaDigest("https://dial.example/s"), schemaPointer);
        entries.forEach((name, content) -> assertFalse(new String(content, StandardCharsets.UTF_8).contains("https://dial.example/s"), name));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- Files: copied as stored, verified by length, etag and sha256

    private static final ResourceDescriptor FILE = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", "icons/a.png");

    /** A source that serves one File: its bytes, the length it reports, and the etag at open and after the last byte. */
    private static final class OneFile implements ExportArchive.StreamSource {
        private final byte[] bytes;
        private final long reportedLength;
        private final String etagAtOpen;
        private final String etagAfter;
        private final boolean isGone;
        private boolean isClosed;

        private OneFile(byte[] bytes, long reportedLength, String etagAtOpen, String etagAfter, boolean isGone) {
            this.bytes = bytes;
            this.reportedLength = reportedLength;
            this.etagAtOpen = etagAtOpen;
            this.etagAfter = etagAfter;
            this.isGone = isGone;
        }

        static OneFile stable(byte[] bytes) {
            return new OneFile(bytes, bytes.length, "e1", "e1", false);
        }

        @Override
        public ExportArchive.StoredStream openStream(ResourceDescriptor descriptor) {
            return isGone ? null : new ExportArchive.StoredStream(new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    isClosed = true;
                    super.close();
                }
            }, etagAtOpen, reportedLength, "image/png");
        }

        @Override
        public String readEtag(ResourceDescriptor descriptor) {
            return isGone ? null : etagAfter;
        }
    }

    private static Closure closureWithFile(BodyRedactor redactor, int length) {
        List<ClosureNode> nodes = List.of(
                ExportTestNodes.node(redactor, ROOT, true, BODIES.get(ROOT), new ExportDiscovery(List.of(), List.of())),
                new ClosureNode(FILE, false, ExportNodeContent.ofStreamedFile(FILE, length, "e1", "image/png", 1_700_000_000_000L, 1_700_000_100_000L), null));
        return new Closure(nodes, List.of());
    }

    private static Map<String, byte[]> archiveWithFile(OneFile reader, int length) throws Exception {
        KeepingRedactor redactor = new KeepingRedactor();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive.prepare(closureWithFile(redactor, length), reader, "1.2.3", NOW, UNBOUNDED).writeTo(out, UNBOUNDED);
        return unzip(out.toByteArray());
    }

    private static byte[] fileBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 7 + 3);
        }
        return bytes;
    }

    @Test
    public void testFileIsCopiedAsStoredAfterItsSidecarAndVerifiedByLengthEtagAndDigest() throws Exception {
        byte[] bytes = fileBytes(100_000);

        Map<String, byte[]> entries = archiveWithFile(OneFile.stable(bytes), bytes.length);

        List<String> names = List.copyOf(entries.keySet());
        int at = names.indexOf("public/files/icons/a.png");
        assertEquals(".dial-system-export/metadata/public/files/icons/a.png", names.get(at - 1), "the sidecar precedes the entry");
        assertArrayEquals(bytes, entries.get("public/files/icons/a.png"));
        JsonNode sidecar = ProxyUtil.MAPPER.readTree(entries.get(names.get(at - 1)));
        assertEquals("image/png", sidecar.get("contentType").asText());
        assertEquals("2023-11-14T22:13:20Z", sidecar.get("creationDate").asText());
        JsonNode verification = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY));
        JsonNode listed = null;
        for (JsonNode candidate : verification.get("entries")) {
            if (candidate.get("name").asText().equals("public/files/icons/a.png")) {
                listed = candidate;
            }
        }
        assertEquals(sha256(bytes), listed.get("sha256").asText());
        assertEquals(bytes.length, listed.get("length").asInt());
        assertEquals("e1", listed.get("etag").asText());
        assertFalse(verification.has("changedDuringExport"));
        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
        assertEquals(1, registry.get("files").size());
        assertEquals(1, registry.get("summary").get("files").asInt());
    }

    @Test
    public void testFileThatChangedDuringTheExportIsListedAsChangedAndWrittenWhole() throws Exception {
        byte[] bytes = fileBytes(1000);
        byte[] newer = fileBytes(2000);

        // the etag changed during the copy
        Map<String, byte[]> changedDuringCopy = archiveWithFile(new OneFile(bytes, bytes.length, "e1", "e2", false), bytes.length);
        // the closure saw e1 with another length; the file was replaced before it was opened
        Map<String, byte[]> replacedBeforeOpen = archiveWithFile(new OneFile(newer, newer.length, "e2", "e2", false), 1000);

        assertArrayEquals(bytes, changedDuringCopy.get("public/files/icons/a.png"));
        assertArrayEquals(newer, replacedBeforeOpen.get("public/files/icons/a.png"));
        for (Map<String, byte[]> entries : List.of(changedDuringCopy, replacedBeforeOpen)) {
            JsonNode change = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY)).get("changedDuringExport").get(0);
            assertEquals("public/files/icons/a.png", change.get("name").asText());
            assertEquals("e1", change.get("etagBefore").asText());
            assertEquals("e2", change.get("etagAfter").asText());
        }
    }

    static Stream<Arguments> inconsistentFiles() {
        byte[] bytes = fileBytes(1000);
        return Stream.of(
                Arguments.of("stream ends before its length", new OneFile(bytes, 1500, "e1", "e1", false)),
                Arguments.of("stream is longer than its length", new OneFile(bytes, 600, "e1", "e1", false)),
                Arguments.of("file is gone before it is opened", new OneFile(bytes, 1000, "e1", "e1", true)),
                Arguments.of("file is gone after the copy", new OneFile(bytes, 1000, "e1", null, false)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentFiles")
    public void testInconsistentFileFailsTheExportAndWritesNoVerification(String description, OneFile reader) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        KeepingRedactor redactor = new KeepingRedactor();
        ExportArchive archive = ExportArchive.prepare(closureWithFile(redactor, 1000), reader, "1.2.3", NOW, UNBOUNDED);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> archive.writeTo(out, UNBOUNDED));

        assertEquals(!reader.isGone, reader.isClosed, "a stream that was opened is closed when the copy fails");
        assertFalse(failure.getMessage().contains("icons"), "no path in the message");
        assertFalse(partialNames(out.toByteArray()).contains(ExportArchive.VERIFICATION_ENTRY));
    }

    // ---- Skills: the files of the pinned version, in a logical folder, copied as stored

    private static final ResourceDescriptor SKILL = ResourceDescriptorFactory.fromDecoded(ResourceTypes.SKILL, "public", "public/", "s");

    /** A source that serves the files of one pinned version of a Skill and the aggregate etag of its marker. */
    private static final class OneSkill implements ExportArchive.StreamSource {
        private final Map<String, byte[]> files;
        private final Map<String, Long> reportedLengths = new LinkedHashMap<>();
        private final String markerEtagAfter;
        private final java.util.Set<String> gone;
        private final List<String> closed = new ArrayList<>();

        private OneSkill(Map<String, byte[]> files, String markerEtagAfter, java.util.Set<String> gone) {
            this.files = files;
            this.markerEtagAfter = markerEtagAfter;
            this.gone = gone;
        }

        static OneSkill stable(Map<String, byte[]> files) {
            return new OneSkill(files, "m1", java.util.Set.of());
        }

        @Override
        public ExportArchive.StoredStream openVersionFile(ResourceDescriptor skill, String versionId, String relativePath) {
            if (gone.contains(relativePath) || !"v1".equals(versionId)) {
                return null;
            }
            byte[] bytes = files.get(relativePath);
            return new ExportArchive.StoredStream(new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    closed.add(relativePath);
                    super.close();
                }
            }, "f-" + relativePath, reportedLengths.getOrDefault(relativePath, (long) bytes.length), "text/plain");
        }

        @Override
        public String readMarkerEtag(ResourceDescriptor skill) {
            return markerEtagAfter;
        }
    }

    private static Closure closureWithSkill(BodyRedactor redactor, Map<String, byte[]> files, String relativePathOverride) {
        FolderResourceMarker marker = new FolderResourceMarker();
        marker.setCurrentVersion("v1");
        marker.setEtag("m1");
        Map<String, FolderResourceMarker.ResourceFileMetadata> metadata = new LinkedHashMap<>();
        files.forEach((path, bytes) -> metadata.put(relativePathOverride != null ? relativePathOverride : path,
                new FolderResourceMarker.ResourceFileMetadata(bytes.length, "f-" + path)));
        marker.setFileMetadata(metadata);
        List<ClosureNode> nodes = List.of(
                ExportTestNodes.node(redactor, ROOT, true, BODIES.get(ROOT), new ExportDiscovery(List.of(), List.of())),
                new ClosureNode(SKILL, false, ExportNodeContent.ofComplex(SKILL, marker), null));
        return new Closure(nodes, List.of());
    }

    private static Map<String, byte[]> skillFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("scripts/run.py", "print(1)".getBytes(StandardCharsets.UTF_8));
        files.put("SKILL.md", "---\nname: s\n---\nbody".getBytes(StandardCharsets.UTF_8));
        files.put("data/config.json", "{\"key\":\"ENC[abc]\"}".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    @Test
    public void testSkillFilesAreCopiedAsStoredInLogicalFolderAndTheSkillOwnsTheirRecords() throws Exception {
        KeepingRedactor redactor = new KeepingRedactor();
        Map<String, byte[]> files = skillFiles();
        OneSkill reader = OneSkill.stable(files);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExportArchive.prepare(closureWithSkill(redactor, files, null), reader, "1.2.3", NOW, UNBOUNDED).writeTo(out, UNBOUNDED);

        Map<String, byte[]> entries = unzip(out.toByteArray());
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            assertArrayEquals(file.getValue(), entries.get("public/skills/s/" + file.getKey()), file.getKey());
            assertTrue(entries.containsKey(".dial-system-export/metadata/public/skills/s/" + file.getKey()), "a sidecar for " + file.getKey());
        }
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains(".dial-resource") || name.contains("/v/")), entries.keySet().toString());
        List<String> names = List.copyOf(entries.keySet());
        assertTrue(names.indexOf("public/skills/s/SKILL.md") < names.indexOf("public/skills/s/data/config.json")
                && names.indexOf("public/skills/s/data/config.json") < names.indexOf("public/skills/s/scripts/run.py"), "files follow the order of their paths");
        assertEquals(3, reader.closed.size(), "every stream is closed");

        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
        assertEquals(3, registry.get("files").size());
        assertEquals(3, registry.get("summary").get("files").asInt());
        assertEquals(1, registry.get("resources").size(), "the Skill is not a resource of the registry");
        JsonNode record = null;
        for (JsonNode file : registry.get("files")) {
            if (file.get("key").asText().equals("skill-file:public:s/scripts/run.py")) {
                record = file;
            }
        }
        assertEquals("skills:public:s", record.get("owner").asText());
        assertEquals("public/skills/s/scripts/run.py", record.get("entryName").asText());
        assertEquals("s/scripts/run.py", record.get("path").asText());

        JsonNode verification = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY));
        for (JsonNode listed : verification.get("entries")) {
            if (listed.get("name").asText().equals("public/skills/s/SKILL.md")) {
                assertEquals(sha256(files.get("SKILL.md")), listed.get("sha256").asText());
                assertEquals(files.get("SKILL.md").length, listed.get("length").asInt());
            }
        }
        assertFalse(verification.has("changedDuringExport"));
    }

    @Test
    public void testUnsafeStoredPathOfSkillFileFailsBeforeAnythingIsWritten() {
        KeepingRedactor redactor = new KeepingRedactor();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", new byte[] {1});

        for (String unsafe : new String[] {"a/../b", "..", "a//b", "./a"}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> ExportArchive.prepare(closureWithSkill(redactor, files, unsafe), OneSkill.stable(files), "1.2.3", NOW, UNBOUNDED),
                    unsafe);
            assertFalse(failure.getMessage().contains(unsafe), "no path in the message");
        }
    }

    @Test
    public void testSkillFileThatIsGoneFromThePinnedVersionOrHasAnotherLengthFailsTheExport() {
        Map<String, byte[]> files = skillFiles();
        KeepingRedactor redactor = new KeepingRedactor();
        OneSkill goneFile = new OneSkill(files, "m1", java.util.Set.of("scripts/run.py"));
        OneSkill otherLength = OneSkill.stable(files);
        otherLength.reportedLengths.put("SKILL.md", 3L);

        assertThrows(IllegalStateException.class,
                () -> ExportArchive.prepare(closureWithSkill(redactor, files, null), goneFile, "1.2.3", NOW, UNBOUNDED).writeTo(new ByteArrayOutputStream(), UNBOUNDED));
        assertThrows(IllegalStateException.class,
                () -> ExportArchive.prepare(closureWithSkill(redactor, files, null), otherLength, "1.2.3", NOW, UNBOUNDED).writeTo(new ByteArrayOutputStream(), UNBOUNDED));
        assertEquals(List.of("SKILL.md", "data/config.json"), goneFile.closed, "the streams that were opened are closed when the export fails");
    }

    @Test
    public void testMarkerThatChangedOrIsGoneAfterTheCopyIsListedAndDoesNotFailTheExport() throws Exception {
        Map<String, byte[]> files = skillFiles();
        KeepingRedactor redactor = new KeepingRedactor();
        for (String after : new String[] {"m2", null}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExportArchive.prepare(closureWithSkill(redactor, files, null), new OneSkill(files, after, java.util.Set.of()), "1.2.3", NOW, UNBOUNDED).writeTo(out, UNBOUNDED);

            JsonNode change = ProxyUtil.MAPPER.readTree(unzip(out.toByteArray()).get(ExportArchive.VERIFICATION_ENTRY)).get("changedDuringExport").get(0);
            assertEquals("public/skills/s", change.get("name").asText());
            assertEquals("m1", change.get("etagBefore").asText());
            assertEquals(after == null ? "null" : after, change.get("etagAfter").asText());
        }
    }

    /** The names of the entries that a partial archive holds before its failure, read as far as the stream goes. */
    private static List<String> partialNames(byte[] partial) {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(partial))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        } catch (IOException e) {
            // a truncated entry ends the listing
        }
        return names;
    }

    @Test
    public void testVerificationHoldsTheSha256OfEveryOtherEntry() throws Exception {
        Map<String, byte[]> entries = unzip(archiveOf(new KeepingRedactor(), NOW));

        JsonNode verification = ProxyUtil.MAPPER.readTree(entries.get(".dial-export/verification.json"));
        Map<String, String> listed = new LinkedHashMap<>();
        verification.get("entries").forEach(entry -> listed.put(entry.get("name").asText(), entry.get("sha256").asText()));

        List<String> others = entries.keySet().stream().filter(name -> !name.equals(".dial-export/verification.json")).toList();
        assertEquals(others, List.copyOf(listed.keySet()));
        for (String name : others) {
            assertEquals(sha256(entries.get(name)), listed.get(name), name);
        }
    }

    @Test
    public void testRemovedValueIsInNoEntryAndTheRegistryHoldsNoRedactionInformation() throws Exception {
        Map<String, byte[]> entries = unzip(archiveOf(new SecretRemovingRedactor(), NOW));

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            assertFalse(new String(entry.getValue(), StandardCharsets.UTF_8).contains(SECRET), "the value is in " + entry.getKey());
        }
        assertRegistryHoldsNoRedactionInformation(entries);
    }

    private static void assertRegistryHoldsNoRedactionInformation(Map<String, byte[]> entries) throws IOException {
        byte[] registryBytes = entries.get(ExportArchive.REGISTRY_ENTRY);
        assertFalse(new String(registryBytes, StandardCharsets.UTF_8).contains("redaction"));
        JsonNode registry = ProxyUtil.MAPPER.readTree(registryBytes);
        assertFalse(registry.get("summary").has("redactions"));
        registry.get("resources").forEach(resource -> assertFalse(resource.has("redactions")));
    }

    @Test
    public void testUnsafeIdentityIsRejectedBeforeAnythingIsWritten() {
        ResourceDescriptor unsafe = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "public", "public/", "a/../b");
        List<ClosureNode> nodes = List.of(ExportTestNodes.node(ROOT, true, "{}", new ExportDiscovery(List.of(), List.of())),
                ExportTestNodes.node(unsafe, false, "{}", null));
        KeepingRedactor redactor = new KeepingRedactor();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(IllegalStateException.class,
                () -> ExportArchive.prepare(new Closure(nodes, List.of()), null, "v", NOW, UNBOUNDED).writeTo(out, UNBOUNDED));

        assertEquals(0, out.size(), "nothing is written");
    }

    @Test
    public void testSameStateGivesTheSameBytesAndOnlyTheManifestChangesWithTheTime() throws Exception {
        byte[] first = archiveOf(new KeepingRedactor(), NOW);
        byte[] second = archiveOf(new KeepingRedactor(), NOW);
        Map<String, byte[]> earlier = unzip(first);
        Map<String, byte[]> later = unzip(archiveOf(new KeepingRedactor(), NOW.plusSeconds(60)));

        assertArrayEquals(first, second);
        for (String name : earlier.keySet()) {
            boolean isManifestOrVerification = name.equals(ExportArchive.MANIFEST_ENTRY) || name.equals(ExportArchive.VERIFICATION_ENTRY);
            assertEquals(!isManifestOrVerification, java.util.Arrays.equals(earlier.get(name), later.get(name)), name);
        }
    }

    private static ExportArchive prepared() {
        KeepingRedactor redactor = new KeepingRedactor();
        return ExportArchive.prepare(closure(redactor), null, "1.2.3", NOW, UNBOUNDED);
    }

    /** Takes the first bytes and then fails, as a connection that is lost in the middle of a response does. */
    private static final class LosingConnection extends OutputStream {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private int bytesLeft;

        LosingConnection(int bytesLeft) {
            this.bytesLeft = bytesLeft;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > bytesLeft) {
                throw new IOException("connection lost");
            }
            bytesLeft -= length;
            written.write(bytes, offset, length);
        }

        String writtenAsText() {
            return new String(written.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    public void testWriteFailureInTheMiddleOfTheStreamReachesTheCallerAndLeavesNoCentralDirectory() {
        ExportArchive archive = prepared();
        LosingConnection connection = new LosingConnection(700);

        IOException failure = assertThrows(IOException.class, () -> archive.writeTo(connection, UNBOUNDED));

        assertEquals("connection lost", failure.getMessage());
        String written = connection.writtenAsText();
        assertTrue(written.startsWith("PK"), "the stream is a ZIP that has begun");
        assertFalse(written.contains("PK"), "no end of central directory is written");
        assertFalse(written.contains("verification.json"), "the archive did not reach its last entry");
    }

    @Test
    public void testTwoResourcesWithTheSameEntryNameAreAnError() {
        List<ClosureNode> nodes = List.of(ExportTestNodes.node(ROOT, true, "{}", null), ExportTestNodes.node(ROOT, false, "{}", null));

        assertThrows(IllegalStateException.class,
                () -> ExportArchive.prepare(new Closure(nodes, List.of()), null, "v", NOW, UNBOUNDED));
    }

    /** Keeps the bytes of a text body and writes a tree as JSON, as the redactor of an entity of the file configuration does. */
    private static class TreeKeepingRedactor implements BodyRedactor {
        @Override
        public byte[] redact(ExportResourceIdentity identity, String storedBody) {
            return storedBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] redact(ExportResourceIdentity identity, JsonNode tree) {
            try {
                return ProxyUtil.MAPPER.writeValueAsBytes(tree);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    public void testNodeReadWithoutRedactorCannotBePrepared() {
        ClosureNode node = ExportTestNodes.nodeWithoutBytes(ROOT, true, "{}", null);

        assertThrows(IllegalStateException.class,
                () -> ExportArchive.prepare(new Closure(List.of(node), List.of()), null, "v", NOW, UNBOUNDED));
    }

    @Test
    public void testExpiredDeadlineFailsPreparationAndWritingBeforeAnythingIsWritten() throws Exception {
        ExportLimits.Deadline expired = new ExportLimits.Deadline(System.nanoTime() - 1);
        KeepingRedactor redactor = new KeepingRedactor();
        Closure closure = closure(redactor);

        assertThrows(HttpException.class, () -> ExportArchive.prepare(closure, null, "v", NOW, expired));

        ExportArchive archive = prepared();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(HttpException.class, () -> archive.writeTo(out, expired));
        byte[] written = out.toByteArray();
        String endOfCentralDirectory = "PK";
        assertFalse(new String(written, StandardCharsets.ISO_8859_1).contains(endOfCentralDirectory), "no central directory: the archive is unfinished");
    }
}
