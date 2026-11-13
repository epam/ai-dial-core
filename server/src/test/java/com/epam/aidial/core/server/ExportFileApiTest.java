package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export of the Files of an Application through the real server: a public File named by a fixed reference field is copied as stored,
 * as one entry at its storage path, and never parsed or examined. A missing File, Skill or Prompt is a logged reference that does not fail
 * the export.
 */
public class ExportFileApiTest extends ResourceBaseTest {

    private static final String ROOT_URL = "applications/platform/exported";

    private Reply post(String body) throws Exception {
        return ExportApiSupport.post(serverPort, body);
    }

    private void putFile(String path, byte[] bytes, String contentType) {
        ResourceDescriptor file = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", path);
        dial.getProxy().getResourceService().putFile(file, bytes, EtagHeader.ANY, contentType, null);
    }

    private void storeApplication(String name, String json) {
        ExportApiSupport.storeJson(dial, "platform/applications/" + name, json);
    }

    private void storeRoot(String iconUrl, String dependencies) {
        storeApplication("exported", "{\"endpoint\":\"http://application.invalid/chat\","
                + (iconUrl == null ? "" : "\"icon_url\":\"" + iconUrl + "\",") + "\"dependencies\":" + dependencies + "}");
    }

    private static byte[] binary(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    @Test
    void testFilesAreCopiedAsStoredAsOneEntryEach() throws Exception {
        byte[] icon = binary(5000);
        // looks like JSON, holds an ENC[ value and names an Application: none of it may be parsed, projected or followed
        byte[] data = "{\"key\":\"ENC[abc]\",\"dependencies\":[\"applications/platform/other\"]}".getBytes(StandardCharsets.UTF_8);
        putFile("icons/a.png", icon, "image/png");
        putFile("data/x.json", data, "application/json");
        storeApplication("other", "{\"endpoint\":\"http://other.invalid/chat\"}");
        storeRoot("files/public/icons/a.png", "[\"files/public/data/x.json\"]");

        Reply reply = post(ExportApiSupport.rootsRequest(ROOT_URL));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = reply.entries();
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/exported", "public/files/data/x.json", "public/files/icons/a.png"),
                List.copyOf(entries.keySet()), "one entry per resource, and the content of a File is not examined for references");
        assertArrayEquals(icon, entries.get("public/files/icons/a.png"), "the bytes are exactly the stored ones");
        assertArrayEquals(data, entries.get("public/files/data/x.json"), "a File that looks like JSON is not projected");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the filesystem blob provider keeps the metadata read of a file that is larger than the cache open on Windows "
            + "(ResourceService.getResourceMetadata, shown by a test that only wrote the file and read its metadata), so the teardown cannot delete it; the assertions pass")
    void testFileLargerThanTheCacheIsStreamedByteForByte() throws Exception {
        byte[] large = binary(3 * 1024 * 1024 + 17);
        putFile("big/large.bin", large, "application/octet-stream");
        storeRoot("files/public/big/large.bin", "[]");

        Reply reply = post(ExportApiSupport.rootsRequest(ROOT_URL));

        assertEquals(200, reply.status());
        Map<String, byte[]> entries = reply.entries();
        assertArrayEquals(large, entries.get("public/files/big/large.bin"));
    }

    @Test
    void testMissingFileSkillAndPromptAreLoggedAndTheExportStillSucceeds() throws Exception {
        storeRoot("files/public/icons/gone.png", "[\"skills/public/gone\",\"prompts/public/absent\"]");
        Reply reply;
        List<String> warnings;
        try (Logs logs = new Logs()) {
            reply = post(ExportApiSupport.rootsRequest(ROOT_URL));
            warnings = logs.lines(Level.WARN, "Export: a reference is not exported");
        }

        assertEquals(200, reply.status(), reply.text());
        assertEquals(List.of("platform/applications/exported"), ExportApiSupport.resourceNames(reply.entries()));
        assertEquals(3, warnings.size(), warnings.toString());
        for (String pointer : List.of("/icon_url", "/dependencies/0", "/dependencies/1")) {
            assertTrue(warnings.stream().anyMatch(line -> line.contains("resource=application:platform:exported pointer=" + pointer + " reason=missing")),
                    pointer + " in " + warnings);
        }
    }

    @Test
    void testTwoApplicationsNamingTheSameFileShareOneEntry() throws Exception {
        putFile("icons/a.png", binary(64), "image/png");
        storeRoot("files/public/icons/a.png", "[]");
        storeApplication("second", "{\"endpoint\":\"http://second.invalid/chat\",\"icon_url\":\"files/public/icons/a.png\"}");

        Reply reply = post(ExportApiSupport.rootsRequest(ROOT_URL, "applications/platform/second"));

        assertEquals(200, reply.status(), reply.text());
        assertEquals(List.of("platform/applications/exported", "platform/applications/second", "public/files/icons/a.png"),
                ExportApiSupport.resourceNames(reply.entries()));
    }

    @Test
    void testCatalogMarkerNamesFileThatIsExported() throws Exception {
        String schema = "{\"$schema\":\"https://dial.epam.com/catalog_schemas/schema#\",\"$id\":\"https://dial.example/catalog/export\","
                + "\"dial:catalogEntityType\":\"application\",\"dial:catalogDisplayName\":\"Application\",\"dial:defaultLocale\":\"en\","
                + "\"type\":\"object\",\"properties\":{\"badge\":{\"type\":\"string\",\"format\":\"dial-file-encoded\",\"dial:file\":true}}}";
        verify(send(HttpMethod.PUT, "/v1/catalog_schemas/platform/export-catalog", null, schema, "authorization", "admin"), 200);
        putFile("badges/b.png", binary(200), "image/png");
        storeApplication("exported", "{\"endpoint\":\"http://application.invalid/chat\",\"catalog_schema_id\":\"https://dial.example/catalog/export\","
                + "\"catalog_properties\":{\"badge\":\"files/public/badges/b.png\"}}");

        Reply reply = post(ExportApiSupport.rootsRequest(ROOT_URL));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = reply.entries();
        assertTrue(entries.containsKey("public/files/badges/b.png"), entries.keySet().toString());
        assertTrue(ExportApiSupport.resourceNames(entries).stream().anyMatch(name -> name.startsWith("platform/catalog_schemas/")));
    }
}
