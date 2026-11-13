package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A File that changes while the export is being written, through the real server: a change before the File is read is the newer version,
 * written whole, and a File that is gone is a logged reference. Four Files of 700 KB fill the pipe and the client pauses after the head,
 * so that the last, small File changes before it is read. A change after the read is tested in {@code ExportArchiveTest}. The Files are
 * below the cache size of the resource service, so that the teardown can delete them on Windows.
 */
public class ExportFileChangedDuringExportApiTest extends ResourceBaseTest {

    private static final int FILLERS = 4;
    private static final int FILLER_BYTES = 700_000;
    private static final String REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/file-root\"}]}";
    private static final byte[] TAIL = bytes(100, 1);
    private static final String TAIL_ENTRY = "public/files/icons/tail.png";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject().put("stallTimeout", 30000));
    }

    private static byte[] bytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static ResourceDescriptor file(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", path);
    }

    private void putFile(String path, byte[] content) {
        dial.getProxy().getResourceService().putFile(file(path), content, EtagHeader.ANY, "application/octet-stream", null);
    }

    @BeforeEach
    void storePackage() {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < FILLERS; i++) {
            putFile("filler/" + i + ".bin", bytes(FILLER_BYTES, 10 + i));
            urls.add("\"files/public/filler/" + i + ".bin\"");
        }
        putFile("icons/tail.png", TAIL);
        dial.getStorage().store("platform/applications/file-root", "application/json", null, Map.of(), ("{\"endpoint\":\"http://root.invalid/chat\","
                + "\"icon_url\":\"files/public/icons/tail.png\",\"dependencies\":[" + String.join(",", urls) + "]}").getBytes(StandardCharsets.UTF_8));
    }

    private ExportApiSupport.PausedStream connectAndChange(Runnable change) throws Exception {
        ExportApiSupport.PausedStream stream = ExportApiSupport.connectPaused(dial.getClient(), serverPort, REQUEST);
        assertEquals(200, stream.response().statusCode());
        change.run();
        stream.response().resume();
        return stream;
    }

    @Test
    void testFileReplacedBeforeItIsReadIsWrittenWholeAsTheNewerVersion() throws Exception {
        byte[] newer = bytes(150, 2);
        List<String> warnings;
        ExportApiSupport.PausedStream stream;
        try (Logs logs = new Logs()) {
            stream = connectAndChange(() -> putFile("icons/tail.png", newer));
            ExportApiSupport.await(stream.finished());
            warnings = logs.lines(Level.WARN, "Export: a resource changed");
        }

        Map<String, byte[]> entries = ExportApiSupport.unzip(stream.received().getBytes());
        assertArrayEquals(newer, entries.get(TAIL_ENTRY), "the newer version is written whole");
        assertEquals(List.of(), warnings, "the file did not change between its read and its copy");
    }

    @Test
    void testFileThatIsGoneBeforeItIsReadIsLoggedAndTheExportGoesOn() throws Exception {
        List<String> warnings;
        ExportApiSupport.PausedStream stream;
        try (Logs logs = new Logs()) {
            stream = connectAndChange(() -> dial.getProxy().getResourceService().deleteResource(file("icons/tail.png"), EtagHeader.ANY));
            ExportApiSupport.await(stream.finished());
            warnings = logs.lines(Level.WARN, "Export: a reference is not exported");
        }

        Map<String, byte[]> entries = ExportApiSupport.unzip(stream.received().getBytes());
        assertFalse(entries.containsKey(TAIL_ENTRY));
        assertEquals(FILLERS + 2, entries.size(), "the manifest, the Application and the Files that were not deleted");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("resource=application:platform:file-root pointer=/icon_url reason=missing"), warnings.get(0));
    }
}
