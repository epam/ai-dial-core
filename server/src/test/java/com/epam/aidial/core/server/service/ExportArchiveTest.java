package com.epam.aidial.core.server.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The writer of the archive: the manifest first, then each resource as one entry at its name, a File and a Skill copied from the services.
 */
public class ExportArchiveTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final ExportLimits.Deadline UNBOUNDED = TestDeadlines.NEVER;
    private static final String CENTRAL_DIRECTORY_SIGNATURE = "PK\u0001\u0002";

    private static final byte[] ROOT_BYTES = "{\"endpoint\":\"root\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MODEL_BYTES = "{\"type\":\"CHAT\"}".getBytes(StandardCharsets.UTF_8);

    private static final ResourceDescriptor FILE = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", "icons/a.png");
    private static final ResourceDescriptor SKILL = ResourceDescriptorFactory.fromDecoded(ResourceTypes.SKILL, "public", "public/", "s");

    private static final String FILE_ENTRY = "public/files/icons/a.png";
    private static final String FILE_KEY = "files:public:icons/a.png";
    private static final String CHANGED_WARNING = "Export: a resource changed while it was copied and was written whole: resource=";

    private final Logger archiveLogger = (Logger) LoggerFactory.getLogger(ExportArchive.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    public void captureLog() {
        logged.start();
        archiveLogger.addAppender(logged);
    }

    @AfterEach
    public void releaseLog() {
        archiveLogger.detachAppender(logged);
        logged.stop();
    }

    private List<String> warnings() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The archive that a test writes: the manifest, whatever the test adds, then the end. */
    private interface Body {
        void write(ExportArchive archive) throws IOException;
    }

    private static ExportArchive newArchive(OutputStream out, ResourceService resourceService, ComplexResourceService complexResourceService,
                                            ExportLimits.Deadline deadline) {
        return new ExportArchive(out, resourceService, complexResourceService, deadline);
    }

    private static ExportArchive newArchive(OutputStream out, ResourceService resourceService, ComplexResourceService complexResourceService) {
        return newArchive(out, resourceService, complexResourceService, UNBOUNDED);
    }

    private static ExportArchive newArchive(OutputStream out) {
        return newArchive(out, mock(ResourceService.class), mock(ComplexResourceService.class));
    }

    private static byte[] write(Body body) throws IOException {
        return write(mock(ResourceService.class), mock(ComplexResourceService.class), body);
    }

    private static byte[] write(ResourceService resourceService, ComplexResourceService complexResourceService, Body body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive archive = newArchive(out, resourceService, complexResourceService);
        archive.writeManifest("1.2.3", NOW);
        body.write(archive);
        archive.finish();
        archive.discard();
        return out.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertNull(entries.put(entry.getName(), zip.readAllBytes()), "one entry per name: " + entry.getName());
            }
        }
        return entries;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void writeRootAndModel(ExportArchive archive) throws IOException {
        archive.writeJson("platform/applications/root", ROOT_BYTES);
        archive.writeJson("platform/models/m", MODEL_BYTES);
    }

    @Test
    public void testManifestIsFirstAndEachResourceIsOneEntryAtItsName() throws Exception {
        Map<String, byte[]> entries = unzip(write(ExportArchiveTest::writeRootAndModel));

        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/root", "platform/models/m"), List.copyOf(entries.keySet()));
        assertArrayEquals(ROOT_BYTES, entries.get("platform/applications/root"));
        assertArrayEquals(MODEL_BYTES, entries.get("platform/models/m"));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith(".dial-system-export/") || name.contains("verification")),
                "no metadata entry and no verification entry");
    }

    @Test
    public void testTechnicalManifestIsMarkedAsSelectiveAndHoldsVersionAndTime() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive archive = newArchive(out);
        archive.writeManifest("1.2.3", Instant.parse("2026-11-02T10:15:30.987654321Z"));
        archive.finish();

        Map<String, byte[]> entries = unzip(out.toByteArray());
        JsonNode manifest = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.MANIFEST_ENTRY));
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY), List.copyOf(entries.keySet()));
        assertEquals(".dial-export/manifest.json", ExportArchive.MANIFEST_ENTRY);
        assertEquals(List.of("formatVersion", "coreVersion", "createdAt", "mode"), fieldNames(manifest));
        assertEquals(2, manifest.get("formatVersion").asInt());
        assertEquals("1.2.3", manifest.get("coreVersion").asText());
        assertEquals("2026-11-02T10:15:30Z", manifest.get("createdAt").asText());
        assertEquals("selective", manifest.get("mode").asText());
    }

    @Test
    public void testFragmentOfTheFileConfigurationIsOneEntry() throws Exception {
        Map<String, byte[]> entries = unzip(write(archive -> archive.writeJson(".dial-export/config/model/file-model.json", MODEL_BYTES)));

        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, ".dial-export/config/model/file-model.json"), List.copyOf(entries.keySet()));
        assertArrayEquals(MODEL_BYTES, entries.get(".dial-export/config/model/file-model.json"));
    }

    @Test
    public void testTwoEntriesWithTheSameNameAreAnError() throws Exception {
        ExportArchive archive = newArchive(new ByteArrayOutputStream());
        archive.writeJson("platform/models/m", MODEL_BYTES);

        assertThrows(IOException.class, () -> archive.writeJson("platform/models/m", MODEL_BYTES));
    }

    /** One stored File: its bytes, the length it reports, and the etag at open and after the last byte. */
    private static final class OneFile {
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

        ResourceService resourceService() throws IOException {
            ResourceService service = mock(ResourceService.class);
            ResourceService.ResourceStream stream = isGone ? null : new ResourceService.ResourceStream(new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    isClosed = true;
                    super.close();
                }
            }, etagAtOpen, "image/png", reportedLength);
            when(service.getResourceStream(any(), eq(EtagHeader.ANY))).thenReturn(stream);
            ResourceItemMetadata metadata = null;
            if (!isGone && etagAfter != null) {
                metadata = new ResourceItemMetadata(FILE);
                metadata.setEtag(etagAfter);
            }
            when(service.getResourceMetadata(any())).thenReturn(metadata);
            return service;
        }
    }

    private static byte[] fileBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 7 + 3);
        }
        return bytes;
    }

    private static Map<String, byte[]> archiveWithFile(OneFile reader, String expectedEtag) throws Exception {
        return unzip(write(reader.resourceService(), mock(ComplexResourceService.class),
                archive -> archive.copyFile(FILE, FILE_ENTRY, expectedEtag)));
    }

    @Test
    public void testFileIsCopiedAsStoredAsOneEntryAndStaysSilentWhenItDidNotChange() throws Exception {
        byte[] bytes = fileBytes(100_000);
        OneFile reader = OneFile.stable(bytes);

        Map<String, byte[]> entries = archiveWithFile(reader, "e1");

        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, FILE_ENTRY), List.copyOf(entries.keySet()));
        assertArrayEquals(bytes, entries.get(FILE_ENTRY));
        assertTrue(reader.isClosed);
        assertEquals(List.of(), warnings());
    }

    @Test
    public void testFileThatChangedDuringTheCopyIsWrittenWholeAndWarnedOnce() throws Exception {
        byte[] bytes = fileBytes(1000);
        byte[] newer = fileBytes(2000);

        Map<String, byte[]> changedDuringCopy = archiveWithFile(new OneFile(bytes, bytes.length, "e1", "e2", false), "e1");
        assertEquals(List.of(CHANGED_WARNING + FILE_KEY + " etagBefore=e1 etagAfter=e2"), warnings());
        logged.list.clear();
        // the export read e1; the file was replaced before it was opened
        Map<String, byte[]> replacedBeforeOpen = archiveWithFile(new OneFile(newer, newer.length, "e2", "e2", false), "e1");
        assertEquals(List.of(CHANGED_WARNING + FILE_KEY + " etagBefore=e1 etagAfter=e2"), warnings());

        assertArrayEquals(bytes, changedDuringCopy.get(FILE_ENTRY));
        assertArrayEquals(newer, replacedBeforeOpen.get(FILE_ENTRY));
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
    public void testInconsistentFileFailsTheExportAndWritesNoCentralDirectory(String description, OneFile reader) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive archive = newArchive(out, reader.resourceService(), mock(ComplexResourceService.class));
        archive.writeManifest("1.2.3", NOW);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> archive.copyFile(FILE, FILE_ENTRY, "e1"));

        assertEquals(!reader.isGone, reader.isClosed, "a stream that was opened is closed when the copy fails");
        assertTrue(failure.getMessage().endsWith("(resource=" + FILE_KEY + ")"), "the message holds the key: " + failure.getMessage());
        assertFalse(new String(out.toByteArray(), StandardCharsets.ISO_8859_1).contains(CENTRAL_DIRECTORY_SIGNATURE));
    }

    private static final class OneSkill {
        private final Map<String, byte[]> files;
        private final Map<String, Long> reportedLengths = new LinkedHashMap<>();
        private final String markerEtagAfter;
        private final Set<String> gone;
        private final List<String> closed = new ArrayList<>();

        private OneSkill(Map<String, byte[]> files, String markerEtagAfter, Set<String> gone) {
            this.files = files;
            this.markerEtagAfter = markerEtagAfter;
            this.gone = gone;
        }

        static OneSkill stable(Map<String, byte[]> files) {
            return new OneSkill(files, "m1", Set.of());
        }

        ComplexResourceService complexResourceService() {
            ComplexResourceService service = mock(ComplexResourceService.class);
            when(service.getVersionFileStream(any(), any(), any()))
                    .thenAnswer(invocation -> openVersionFile(invocation.getArgument(1), invocation.getArgument(2)));
            FolderResourceMarker marker = null;
            if (markerEtagAfter != null) {
                marker = new FolderResourceMarker();
                marker.setEtag(markerEtagAfter);
            }
            when(service.getMarker(any())).thenReturn(marker);
            return service;
        }

        private ResourceService.ResourceStream openVersionFile(String versionId, String relativePath) {
            if (gone.contains(relativePath) || !"v1".equals(versionId)) {
                return null;
            }
            byte[] bytes = files.get(relativePath);
            return new ResourceService.ResourceStream(new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    closed.add(relativePath);
                    super.close();
                }
            }, "f-" + relativePath, "text/plain", reportedLengths.getOrDefault(relativePath, (long) bytes.length));
        }
    }

    private static FolderResourceMarker markerOf(Map<String, byte[]> files, String relativePathOverride) {
        FolderResourceMarker marker = new FolderResourceMarker();
        marker.setCurrentVersion("v1");
        marker.setEtag("m1");
        Map<String, FolderResourceMarker.ResourceFileMetadata> metadata = new LinkedHashMap<>();
        files.forEach((path, bytes) -> metadata.put(relativePathOverride != null ? relativePathOverride : path,
                new FolderResourceMarker.ResourceFileMetadata(bytes.length, "f-" + path)));
        marker.setFileMetadata(metadata);
        return marker;
    }

    private static Map<String, byte[]> skillFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("scripts/run.py", "print(1)".getBytes(StandardCharsets.UTF_8));
        files.put("SKILL.md", "---\nname: s\n---\nbody".getBytes(StandardCharsets.UTF_8));
        files.put("data/config.json", "{\"key\":\"ENC[abc]\"}".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    @Test
    public void testSkillFilesAreCopiedAsStoredInLogicalFolderOneEntryEach() throws Exception {
        Map<String, byte[]> files = skillFiles();
        OneSkill reader = OneSkill.stable(files);

        Map<String, byte[]> entries = unzip(write(mock(ResourceService.class), reader.complexResourceService(),
                archive -> archive.copySkill(SKILL, "public/skills/s", markerOf(files, null))));

        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "public/skills/s/SKILL.md", "public/skills/s/data/config.json",
                "public/skills/s/scripts/run.py"), List.copyOf(entries.keySet()), "files follow the order of their paths, with no other entry");
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            assertArrayEquals(file.getValue(), entries.get("public/skills/s/" + file.getKey()), file.getKey());
        }
        assertEquals(3, reader.closed.size(), "every stream is closed");
        assertEquals(List.of(), warnings());
    }

    @Test
    public void testUnsafeStoredPathOfSkillFileFailsBeforeAnythingOfTheSkillIsWritten() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", new byte[] {1});

        for (String unsafe : new String[] {"a/../b", "..", "a//b", "./a"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExportArchive archive = newArchive(out, mock(ResourceService.class), OneSkill.stable(files).complexResourceService());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> archive.copySkill(SKILL, "public/skills/s", markerOf(files, unsafe)), unsafe);
            assertTrue(failure.getMessage().endsWith("(resource=skills:public:s)"), "the message holds the key of the Skill: " + failure.getMessage());
            assertFalse(failure.getMessage().contains(unsafe), "the message holds the path of the file");
            assertEquals(0, out.size(), "nothing of the Skill is written");
        }
    }

    @Test
    public void testSkillFileThatIsGoneFromThePinnedVersionOrHasAnotherLengthFailsTheExport() {
        Map<String, byte[]> files = skillFiles();
        OneSkill goneFile = new OneSkill(files, "m1", Set.of("scripts/run.py"));
        OneSkill otherLength = OneSkill.stable(files);
        otherLength.reportedLengths.put("SKILL.md", 3L);

        assertThrows(IllegalStateException.class, () -> newArchive(new ByteArrayOutputStream(), mock(ResourceService.class), goneFile.complexResourceService())
                .copySkill(SKILL, "public/skills/s", markerOf(files, null)));
        assertThrows(IllegalStateException.class, () -> newArchive(new ByteArrayOutputStream(), mock(ResourceService.class), otherLength.complexResourceService())
                .copySkill(SKILL, "public/skills/s", markerOf(files, null)));
        assertEquals(List.of("SKILL.md", "data/config.json"), goneFile.closed, "the streams that were opened are closed when the export fails");
    }

    @Test
    public void testMarkerThatChangedOrIsGoneAfterTheCopyIsWarnedOnceAndDoesNotFailTheExport() throws Exception {
        Map<String, byte[]> files = skillFiles();
        for (String after : new String[] {"m2", null}) {
            logged.list.clear();
            Map<String, byte[]> entries = unzip(write(mock(ResourceService.class), new OneSkill(files, after, Set.of()).complexResourceService(),
                    archive -> archive.copySkill(SKILL, "public/skills/s", markerOf(files, null))));

            assertEquals(4, entries.size(), "the Skill is written whole");
            assertEquals(List.of(CHANGED_WARNING + "skills:public:s etagBefore=m1 etagAfter=" + after), warnings());
        }
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
        LosingConnection connection = new LosingConnection(150);
        ExportArchive archive = newArchive(connection);

        IOException failure = assertThrows(IOException.class, () -> {
            archive.writeManifest("1.2.3", NOW);
            writeRootAndModel(archive);
            archive.finish();
        });

        assertEquals("connection lost", failure.getMessage());
        String written = connection.writtenAsText();
        assertTrue(written.startsWith("PK"), "the stream is a ZIP that has begun");
        assertFalse(written.contains(CENTRAL_DIRECTORY_SIGNATURE), "the archive did not reach its end");
    }

    @Test
    public void testExpiredDeadlineStopsTheCopyOfFileAndLeavesNoCentralDirectory() throws Exception {
        ExportLimits.Deadline expired = new ExportLimits.Deadline(System.nanoTime() - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportArchive archive = newArchive(out, OneFile.stable(fileBytes(10)).resourceService(), mock(ComplexResourceService.class), expired);
        archive.writeManifest("1.2.3", NOW);

        assertThrows(HttpException.class, () -> archive.copyFile(FILE, FILE_ENTRY, "e1"));

        assertFalse(new String(out.toByteArray(), StandardCharsets.ISO_8859_1).contains(CENTRAL_DIRECTORY_SIGNATURE));
    }
}
