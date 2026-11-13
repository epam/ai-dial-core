package com.epam.aidial.core.server;

import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dependency written in URL form: a URL names a type, a bucket and a name, so it is read as the stored blob, whatever the merged
 * configuration holds under the short name. Pins that a blob that a short name does not reach is exported under its own identity, and
 * that the dependencies of a ToolSet and a Model are followed.
 */
public class ExportUrlFormDependencyApiTest extends ResourceBaseTest {

    /** The live stand of a developer may hold 9876, which the other tests of the toolset use. */
    private static final int TOOLSET_PORT = 9877;
    private static final String ROOT = ExportApiSupport.rootsRequest("applications/platform/root");

    private void store(String blobPath, String json) {
        ExportApiSupport.storeJson(dial, blobPath, json);
    }

    private void storeRoot(String dependenciesJson) {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":" + dependenciesJson + "}");
    }

    private Reply export() throws Exception {
        return ExportApiSupport.post(serverPort, ROOT);
    }

    private void givenModelAndApplicationNamedShared() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/shared", null,
                "{\"type\":\"chat\",\"endpoint\":\"http://localhost:7001/openai/deployments/m/chat/completions\"}",
                "authorization", "admin", "If-None-Match", "*"), 200);
        store("platform/applications/shared", "{\"endpoint\":\"http://shared-app.invalid\"}");
        storeRoot("[\"applications/platform/shared\",\"shared\"]");
    }

    @Test
    void testUrlFormNamesTheApplicationAndTheShortNameTheModelSoBothAreExportedUnderTheirOwnIdentity() throws Exception {
        givenModelAndApplicationNamedShared();

        Reply reply = export();

        assertEquals(200, reply.status());
        List<String> names = ExportApiSupport.resourceNames(reply.entries());
        assertEquals(List.of("platform/applications/root", "platform/applications/shared", "platform/models/shared"), names,
                "the URL reached the application, the short name the model, in the order of the references");
        assertEquals(names, ExportApiSupport.resourceNames(export().entries()), "the same order on every run");
    }

    private void put(String path, String body) {
        verify(send(HttpMethod.PUT, path, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    void testToolSetAndModelAreFollowedToTheirInterceptorsAndIcon() throws Exception {
        byte[] icon = {1, 2, 3, 4};
        ResourceDescriptor file = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", "icons/t.png");
        dial.getProxy().getResourceService().putFile(file, icon, EtagHeader.ANY, "image/png", null);
        put("/v1/interceptors/platform/tool-int", "{\"endpoint\":\"http://localhost:4088/api/v1/interceptor/handle\"}");
        put("/v1/interceptors/platform/model-int", "{\"endpoint\":\"http://localhost:4088/api/v1/interceptor/handle\"}");
        put("/v1/models/platform/chain-model", "{\"type\":\"chat\",\"endpoint\":\"http://localhost:7001/chat\",\"interceptors\":[\"model-int\"]}");
        try (TestWebServer ignore = new TestWebServer(TOOLSET_PORT)) {
            put("/v1/toolsets/platform/chain-tools", "{\"endpoint\":\"http://localhost:" + TOOLSET_PORT + "\",\"transport\":\"HTTP\","
                    + "\"interceptors\":[\"tool-int\"],\"icon_url\":\"files/public/icons/t.png\"}");
        }
        storeRoot("[\"toolsets/platform/chain-tools\",\"chain-model\"]");

        Reply reply = export();

        assertEquals(200, reply.status());
        Map<String, byte[]> entries = reply.entries();
        assertEquals(List.of("platform/applications/root", "platform/toolsets/chain-tools", "platform/models/chain-model",
                "platform/interceptors/tool-int", "public/files/icons/t.png", "platform/interceptors/model-int"),
                ExportApiSupport.resourceNames(entries), "breadth first: each level after the one that refers to it");
        assertEquals(icon.length, entries.get("public/files/icons/t.png").length);
    }

}
