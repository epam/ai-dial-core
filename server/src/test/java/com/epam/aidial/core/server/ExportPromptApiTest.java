package com.epam.aidial.core.server;

import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The export of the Prompts of an Application through the real server: a public Prompt named by a stored {@code dependencies} entry is
 * exported exactly as stored and never examined.
 */
public class ExportPromptApiTest extends ResourceBaseTest {

    private static final String SECRET = "s3cr3t-prompt-value";
    /** Pretty-printed, with an escape, an unknown property and every kind of content that another type would lose or change. */
    private static final String PROMPT = "{\n  \"id\" : \"p\",\n  \"folderId\" : \"\",\n  \"name\" : \"P \\u00e9\",\n  \"content\" : \"Use key=value and ENC[abc] and "
            + "https://user:" + SECRET + "@a.invalid/x?token=" + SECRET + "#f as they are\",\n  \"default_headers\" : { \"X-Key\" : \"" + SECRET + "\" },\n"
            + "  \"application_properties\" : { \"a\" : 1.50 },\n  \"extra\" : { \"nested\" : [ 1, 2 ] }\n}";

    private Reply post(String body) throws Exception {
        return ExportApiSupport.post(serverPort, body);
    }

    private static ResourceDescriptor prompt(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROMPT, "public", "public/", path);
    }

    private void putPrompt(String path, String body) {
        dial.getProxy().getResourceService().putResource(prompt(path), body, EtagHeader.ANY, null, false);
    }

    private void storeRoot(String dependencies) {
        dial.getStorage().store("platform/applications/exported", "application/json", null, Map.of(), ("{\"endpoint\":\"http://application.invalid/chat\","
                + "\"dependencies\":" + dependencies + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testPublicPromptIsExportedExactlyAsStoredAndNotExaminedForReferences() throws Exception {
        putPrompt("p", PROMPT);
        dial.getStorage().store("platform/applications/other", "application/json", null, Map.of(),
                "{\"endpoint\":\"http://other.invalid/chat\"}".getBytes(StandardCharsets.UTF_8));
        storeRoot("[\"prompts/public/p\"]");

        Reply reply = post(ExportApiSupport.rootsRequest("applications/platform/exported"));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = reply.entries();
        assertEquals(PROMPT, new String(entries.get("public/prompts/p"), StandardCharsets.UTF_8), "the stored bytes: nothing removed, emptied or normalized");
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/exported", "public/prompts/p"), List.copyOf(entries.keySet()),
                "the Prompt is one entry, and the Application that its text names is not followed");
    }
}
