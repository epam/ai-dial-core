package com.epam.aidial.core.server.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The stream of the export on a fake storage: every resource once whatever the shape of the graph, in breadth first order, with the
 * references of a resource found before it is written, the limits that cut the stream, one resource in hand at a time, and a log record
 * for each reference that is not followed.
 */
@ExtendWith(MockitoExtension.class)
public class ExportStreamerTest {

    private static final Predicate<ResourceDescriptor> READS_PUBLIC = descriptor -> true;

    @Mock
    private ResourceService resourceService;
    @Mock
    private ComplexResourceService complexResourceService;
    @Mock
    private ApplicationService applicationService;
    @Mock
    private ToolSetService toolSetService;
    @Mock
    private ApplicationSchemaService schemaService;
    @Mock
    private CatalogSchemaService catalogSchemaService;
    @Mock
    private EncryptionService encryptionService;

    /** The stored bodies by the URL of the resource, and the URLs in the order in which they were read. */
    private final Map<String, String> stored = new HashMap<>();
    private final List<String> reads = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    /** The number of complete entries of the archive when each resource was read. */
    private final List<Integer> entriesWrittenAtRead = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger streamerLog = (Logger) LoggerFactory.getLogger(ExportStreamer.class);

    @BeforeEach
    void setUp() {
        lenient().when(catalogSchemaService.collectMarkerReferences(any(), any())).thenReturn(new MarkerCollection(List.of(), List.of()));
        lenient().when(resourceService.hasResource(any())).thenAnswer(invocation -> stored.containsKey(((ResourceDescriptor) invocation.getArgument(0)).getUrl()));
        lenient().when(resourceService.getResource(any(ResourceDescriptor.class))).thenAnswer(invocation -> {
            ResourceDescriptor descriptor = invocation.getArgument(0);
            String body = stored.get(descriptor.getUrl());
            if (body == null) {
                return null;
            }
            reads.add(descriptor.getUrl());
            entriesWrittenAtRead.add(completeEntries(out.toByteArray()));
            return body;
        });
        logs.start();
        streamerLog.addAppender(logs);
    }

    @AfterEach
    void detachLog() {
        streamerLog.detachAppender(logs);
    }

    private static int completeEntries(byte[] partial) {
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(partial))) {
            while (zip.getNextEntry() != null) {
                count++;
            }
        } catch (IOException e) {
            // a truncated entry ends the count, and it is not complete
        }
        return count;
    }

    private static ResourceDescriptor rootOf(String name) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "platform", "platform/", name);
    }

    private void app(String name, String... dependencies) {
        List<String> urls = new ArrayList<>();
        for (String dependency : dependencies) {
            urls.add("\"" + (dependency.contains("/") ? dependency : "applications/platform/" + dependency) + "\"");
        }
        stored.put("applications/platform/" + name, "{\"endpoint\":\"http://" + name + ".invalid\",\"dependencies\":[" + String.join(",", urls) + "]}");
    }

    private ExportStreamer streamer() {
        return streamer(Config::new, () -> null);
    }

    private ExportStreamer streamer(Supplier<Config> configSource, Supplier<Config> fileConfigSource) {
        return new ExportStreamer(new ReentrantLock(), Map::of, configSource, fileConfigSource, resourceService, schemaService, catalogSchemaService,
                encryptionService, new ExportResourceProjector(applicationService, toolSetService), complexResourceService);
    }

    private static ExportRequest request(String... roots) {
        List<ResourceDescriptor> descriptors = new ArrayList<>();
        for (String root : roots) {
            descriptors.add(rootOf(root));
        }
        return new ExportRequest(descriptors);
    }

    private void stream(ExportStreamer streamer, ExportRequest request) throws IOException {
        streamer.stream(out, request, READS_PUBLIC, "1.2.3", Instant.parse("2026-11-02T10:15:30Z"));
    }

    private void stream(String... roots) throws IOException {
        stream(streamer(), request(roots));
    }

    private Map<String, byte[]> entries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private List<String> resourceEntries() throws IOException {
        return entries().keySet().stream().filter(name -> name.startsWith("platform/") || name.startsWith("public/")).toList();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> infos() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void testCycleIsExportedOnceAndEndsTheStream() throws IOException {
        app("a", "b");
        app("b", "a");

        stream("a");

        assertEquals(List.of("platform/applications/a", "platform/applications/b"), resourceEntries());
        assertEquals(List.of("applications/platform/a", "applications/platform/b"), reads, "each resource is read once");
    }

    @Test
    void testSelfReferenceAndDuplicateRootsAreExportedOnce() throws IOException {
        app("a", "a", "a");

        stream("a", "a");

        assertEquals(List.of("platform/applications/a"), resourceEntries());
        assertEquals(1, reads.size());
    }

    @Test
    void testDiamondIsExportedOnceInBreadthFirstOrder() throws IOException {
        app("a", "b", "c");
        app("b", "d");
        app("c", "d");
        app("d");

        stream("a");

        assertEquals(List.of("platform/applications/a", "platform/applications/b", "platform/applications/c", "platform/applications/d"),
                resourceEntries());
        assertEquals(List.of("applications/platform/a", "applications/platform/b", "applications/platform/c", "applications/platform/d"), reads);
    }

    @Test
    void testManifestIsFirstAndRootsComeInTheOrderOfTheRequestEachAsOneEntry() throws IOException {
        app("a");
        app("b");

        stream("b", "a");

        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/b", "platform/applications/a"), new ArrayList<>(entries().keySet()),
                "the manifest, then one entry per resource, and no metadata or verification entry");
    }

    @Test
    void testEntityOfTheFileConfigurationIsOneFragmentEntryAndIsNotReadFromTheStorage() throws IOException {
        Model model = new Model();
        model.setName("file-model");
        model.setEndpoint("http://model.invalid/chat");
        Config config = new Config();
        config.setModels(Map.of("file-model", model));
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"dependencies\":[\"file-model\"]}");

        stream(streamer(() -> config, () -> config), request("a"));

        Map<String, byte[]> entries = entries();
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/a", ".dial-export/config/model/file-model.json"),
                new ArrayList<>(entries.keySet()));
        assertTrue(new String(entries.get(".dial-export/config/model/file-model.json"), StandardCharsets.UTF_8).contains("\"endpoint\":\"http://model.invalid/chat\""));
        assertEquals(List.of("applications/platform/a"), reads, "an entity of the file configuration has no blob to read");
    }

    @Test
    void testPlatformBlobReplacesFileEntityOfTheSameName() throws IOException {
        Model fileModel = new Model();
        fileModel.setName("shared");
        fileModel.setEndpoint("http://file.invalid/chat");
        Config config = new Config();
        config.setModels(Map.of("shared", fileModel));
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"dependencies\":[\"shared\",\"shared\"]}");
        stored.put("models/platform/shared", "{\"type\":\"chat\",\"endpoint\":\"http://platform.invalid/chat\"}");

        stream(streamer(() -> config, () -> config), request("a"));

        Map<String, byte[]> entries = entries();
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/a", "platform/models/shared"), new ArrayList<>(entries.keySet()),
                "no fragment for the file entity");
        assertTrue(new String(entries.get("platform/models/shared"), StandardCharsets.UTF_8).contains("http://platform.invalid/chat"));
        assertFalse(new String(entries.get("platform/models/shared"), StandardCharsets.UTF_8).contains("file.invalid"));
        assertEquals(List.of("applications/platform/a", "models/platform/shared"), reads);
    }

    @Test
    void testStartAndEndAreOneInfoRecordEach() throws IOException {
        app("a", "b");
        app("b");

        stream("a");

        List<String> infos = infos();
        assertEquals(2, infos.size(), infos.toString());
        assertEquals("Export started: roots=1", infos.get(0));
        assertTrue(infos.get(1).matches("Export completed: duration=\\d+ms, resources=2"), infos.get(1));
    }

    @Test
    void testEntryIsTheProjectionOfTheBoundEntity() throws IOException {
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"display_name\":\"A\",\"description\":\"d\"}");

        stream("a");

        String entry = new String(entries().get("platform/applications/a"), StandardCharsets.UTF_8);
        assertTrue(entry.contains("\"display_name\":\"A\"") && entry.contains("\"endpoint\":\"http://a.invalid\""), entry);
        assertFalse(entry.contains("\"name\"") || entry.contains("\"status\""), "no API projection fields");
    }

    @Test
    void testNextResourceIsReadOnlyAfterThePreviousOneIsWritten() throws IOException {
        app("a", "b", "c");
        app("b", "d");
        app("c");
        app("d");

        stream("a");

        // the manifest, and an entry for each resource that was written before the read
        assertEquals(List.of(1, 2, 3, 4), entriesWrittenAtRead, "no body is held while the next one is read");
    }

    @Test
    void testReferencesOfResourceAreFoundBeforeItIsWritten() throws IOException {
        // a public dependency that the caller may not read ends the export before the Application that names it is written
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"dependencies\":[\"toolsets/public/t\"]}");
        stored.put("toolsets/public/t", "{\"endpoint\":\"http://t.invalid\"}");
        ExportStreamer streamer = streamer();

        HttpException failure = assertThrows(HttpException.class,
                () -> streamer.stream(out, request("a"), descriptor -> false, "1.2.3", Instant.now()));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY), new ArrayList<>(entries().keySet()), "the Application is not written");
        assertEquals(List.of("applications/platform/a"), reads, "the dependency is not read");
    }

    private void assertCut(HttpException failure, HttpStatus status, int entriesBefore) throws IOException {
        assertEquals(status, failure.getStatus());
        assertEquals(entriesBefore, completeEntries(out.toByteArray()), "what was written before the limit stays");
        assertFalse(new String(out.toByteArray(), StandardCharsets.ISO_8859_1).contains("PK\u0001\u0002"), "the archive has no central directory");
    }

    @Test
    void testFailureAfterTheHeadIsLoggedWithoutValueAndCutsTheStream() throws IOException {
        stored.put("applications/platform/a", "{\"endpoint\":{\"nested\":\"SECRET-IN-BODY\"}}");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> stream("a"));

        assertFalse(failure.getMessage().contains("SECRET-IN-BODY"));
        assertTrue(failure.getMessage().endsWith("(resource=application:platform:a)"), "the message holds the key: " + failure.getMessage());
        assertEquals(1, warnings().stream().filter(line -> line.startsWith("Export aborted after the response started")).count(), warnings().toString());
        assertTrue(warnings().get(0).matches("Export aborted after the response started: resource=application:platform:a, duration=\\d+ms, "
                + "IllegalStateException: A stored body cannot be bound to its type \\(resource=application:platform:a\\)"), warnings().get(0));
        assertFalse(String.join("\n", warnings()).contains("SECRET-IN-BODY"));
        assertEquals(1, completeEntries(out.toByteArray()), "only the manifest was written");
    }

    @Test
    void testReferencesThatAreNotFollowedAreOneWarningEachWithTheResourceAndThePointerAndNoValue() throws IOException {
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"dependencies\":[\"applications/platform/gone\",\"sk-SECRET-NAME\","
                + "\"https://user:SECRET-PASS@example.com/x?token=SECRET-TOKEN\",\"models/public/m\"]}");

        stream("a");

        assertEquals(List.of("platform/applications/a"), resourceEntries(), "the export goes on");
        List<String> warnings = warnings();
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.contains("Export: a reference is not exported: resource=application:platform:a pointer=/dependencies/0 reason=missing"), warnings.toString());
        assertTrue(warnings.contains("Export: a reference is not exported: resource=application:platform:a pointer=/dependencies/1 reason=missing"), warnings.toString());
        assertTrue(warnings.contains("Export: a reference is not exported: resource=application:platform:a pointer=/dependencies/3 reason=unsupported"), warnings.toString());
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains("pointer=/dependencies/2")),
                "an external link is one record at INFO");
        for (ILoggingEvent event : logs.list) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains("SECRET") || message.contains("example.com") || message.contains("sk-"), message);
        }
    }

    @Test
    void testDependencyThatIsGoneWhenItsTurnComesIsLoggedAndTheExportGoesOn() throws IOException {
        app("a", "b");
        // the existence of a dependency is not checked when it is found, only when it is read
        stream("a");

        assertEquals(List.of("platform/applications/a"), resourceEntries());
        assertEquals(List.of("Export: a reference is not exported: resource=application:platform:a pointer=/dependencies/0 reason=missing"), warnings());
    }

    @Test
    void testPromptIsWrittenExactlyAsStoredAndFileIsStreamedNotRead() throws IOException {
        String prompt = "{ \"id\" : \"p\",\n  \"content\" : \"as  stored ENC[x]\" }";
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"icon_url\":\"files/public/icons/a.png\",\"dependencies\":[\"prompts/public/p\"]}");
        stored.put("prompts/public/p", prompt);
        byte[] icon = {1, 2, 3, 4, 5};
        when(resourceService.getResourceStream(any(), eq(EtagHeader.ANY)))
                .thenReturn(new ResourceService.ResourceStream(new ByteArrayInputStream(icon), "e1", "image/png", icon.length));
        lenient().when(resourceService.getResourceMetadata(any())).thenAnswer(invocation ->
                new ResourceItemMetadata(invocation.getArgument(0)).setEtag("e1"));

        stream(streamer(), request("a"));

        Map<String, byte[]> entries = entries();
        assertEquals(List.of("platform/applications/a", "public/prompts/p", "public/files/icons/a.png"), resourceEntries());
        assertEquals(prompt, new String(entries.get("public/prompts/p"), StandardCharsets.UTF_8));
        assertArrayEquals(icon, entries.get("public/files/icons/a.png"));
        assertFalse(reads.contains("files/public/icons/a.png"), "a File is never read as text");
    }

    @Test
    void testSkillIsCopiedFromItsMarkerAndNotReadAsText() throws IOException {
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"dependencies\":[\"skills/public/s\"]}");
        FolderResourceMarker marker = new FolderResourceMarker();
        marker.setCurrentVersion("v1");
        marker.setEtag("m1");
        marker.setFileMetadata(Map.of("SKILL.md", new FolderResourceMarker.ResourceFileMetadata(4, "f1")));
        when(complexResourceService.getMarker(any())).thenReturn(marker);
        when(complexResourceService.getVersionFileStream(any(), eq("v1"), eq("SKILL.md")))
                .thenReturn(new ResourceService.ResourceStream(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), "f1", "text/markdown", 4));

        stream(streamer(), request("a"));

        assertEquals(List.of("platform/applications/a", "public/skills/s/SKILL.md"), resourceEntries());
        assertEquals(List.of("applications/platform/a"), reads);
    }

    @Test
    void testRootsThatTheCallerMayNotReadAreForbiddenAndMissingRootsAreNotFound() {
        stored.put("applications/platform/a", "{}");
        ExportStreamer streamer = streamer();
        ResourceDescriptor publicRoot = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "public", "public/", "p__1.0.0");

        streamer.checkRoots(request("a"), descriptor -> false);
        HttpException forbidden = assertThrows(HttpException.class, () -> streamer.checkRoots(new ExportRequest(List.of(publicRoot)), descriptor -> false));
        HttpException missing = assertThrows(HttpException.class, () -> streamer.checkRoots(request("no-such"), READS_PUBLIC));
        HttpException forbiddenFirst = assertThrows(HttpException.class,
                () -> streamer.checkRoots(new ExportRequest(List.of(rootOf("no-such"), publicRoot)), descriptor -> false));

        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, forbiddenFirst.getStatus(), "a root that may not be read does not tell whether it exists");
    }

    @Test
    void testRootThatIsGoneAfterTheCheckEndsTheExport() {
        // checked, but gone by the time of its turn
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> stream("gone"));

        assertEquals("A selected Application no longer exists (resource=application:platform:gone)", failure.getMessage());
        assertTrue(warnings().get(0).contains("resource=application:platform:gone, duration="), warnings().toString());
    }

    @Test
    void testFileThatIsGoneWhenItIsCopiedEndsTheExportAndTheRecordHoldsItsKey() {
        stored.put("applications/platform/a", "{\"endpoint\":\"http://a.invalid\",\"icon_url\":\"files/public/icons/a.png\"}");
        lenient().when(resourceService.getResourceMetadata(any())).thenAnswer(invocation ->
                new ResourceItemMetadata(invocation.getArgument(0)).setEtag("e1"));
        // the stream source of the default streamer finds no bytes, as for a File that was deleted after its metadata was read

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> stream("a"));

        assertEquals("A resource of the export no longer exists (resource=files:public:icons/a.png)", failure.getMessage());
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).startsWith("Export aborted after the response started: resource=files:public:icons/a.png, duration="),
                warnings().get(0));
    }

    @Test
    void testFailedExportIsNotRecordedAsCompleted() {
        assertThrows(IllegalStateException.class, () -> stream("gone"));

        assertEquals(List.of("Export started: roots=1"), infos());
    }
}
