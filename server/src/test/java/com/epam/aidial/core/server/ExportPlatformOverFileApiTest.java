package com.epam.aidial.core.server;

import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.service.ExportArchive;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A platform blob and an entity of the file configuration with the same type and name: the platform blob has the higher priority and is
 * the one that the export writes, at its storage path. An entity of the file configuration that no blob replaces is still exported as a
 * fragment. No secret of the file configuration reaches the archive.
 */
public class ExportPlatformOverFileApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/export-platform-over-file.json";
    private static final String ROOT_URL = "applications/platform/exported";
    private static final String SHARED_FRAGMENT = ".dial-export/config/model/shared-model.json";
    private static final String FILE_ONLY_FRAGMENT = ".dial-export/config/model/file-model.json";

    private void storeRootAndPlatformModel() {
        ExportApiSupport.storeJson(dial, "platform/applications/exported",
                "{\"endpoint\":\"http://application.invalid/chat\",\"dependencies\":[\"shared-model\",\"file-model\"]}");
        ExportApiSupport.storeJson(dial, "platform/models/shared-model",
                "{\"type\":\"chat\",\"endpoint\":\"http://platform.invalid/shared/chat/completions\","
                        + "\"upstreams\":[{\"endpoint\":\"http://platform.invalid/shared\",\"key\":\"PLATFORM-SECRET\"}]}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPlatformBlobIsExportedAndTheFileEntityOfTheSameNameIsNot() throws Exception {
        storeRootAndPlatformModel();
        Reply reply = ExportApiSupport.post(serverPort, ExportApiSupport.rootsRequest(ROOT_URL));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = reply.entries();
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/exported", "platform/models/shared-model", FILE_ONLY_FRAGMENT),
                List.copyOf(entries.keySet()));
        assertFalse(entries.containsKey(SHARED_FRAGMENT), "the file entity that a platform blob replaces is not exported");
        String platformModel = new String(entries.get("platform/models/shared-model"), StandardCharsets.UTF_8);
        assertTrue(platformModel.contains("http://platform.invalid/shared/chat/completions"), platformModel);
        assertFalse(platformModel.contains("file.invalid"), platformModel);
        String fileModel = new String(entries.get(FILE_ONLY_FRAGMENT), StandardCharsets.UTF_8);
        assertTrue(fileModel.contains("http://file.invalid/only/chat/completions"), fileModel);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNoSecretOfTheFileConfigurationIsInTheArchive() throws Exception {
        storeRootAndPlatformModel();

        Reply reply = ExportApiSupport.post(serverPort, ExportApiSupport.rootsRequest(ROOT_URL));

        assertEquals(200, reply.status(), reply.text());
        for (Map.Entry<String, byte[]> entry : reply.entries().entrySet()) {
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            assertFalse(text.contains("FILE-SECRET") || text.contains("PLATFORM-SECRET"), entry.getKey());
        }
    }
}
