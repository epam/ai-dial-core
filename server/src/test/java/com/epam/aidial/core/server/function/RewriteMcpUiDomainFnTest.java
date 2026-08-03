package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RewriteMcpUiDomainFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @InjectMocks
    private RewriteMcpUiDomainFn fn;

    @Test
    void deploymentIsNotApplication_returnsUnchanged() throws Exception {
        when(context.getDeployment()).thenReturn(new Model());
        JsonNode input = parse(toolsListWith("{\"name\":\"tool1\"}"));

        JsonNode result = apply(input);

        assertEquals(input, result);
    }

    @Test
    void mcpIsNull_returnsUnchanged() throws Exception {
        Application app = new Application();
        when(context.getDeployment()).thenReturn(app);
        JsonNode input = parse(toolsListWith("{\"name\":\"tool1\"}"));

        JsonNode result = apply(input);

        assertEquals(input, result);
    }

    @Test
    void mcpAppsIsNull_returnsUnchanged() throws Exception {
        Application app = appWithMcp(null);
        when(context.getDeployment()).thenReturn(app);
        JsonNode input = parse(toolsListWith("{\"name\":\"tool1\"}"));

        JsonNode result = apply(input);

        assertEquals(input, result);
    }

    @Test
    void domainOverrideIsNull_returnsUnchanged() throws Exception {
        Application app = appWithMcpApps(null);
        when(context.getDeployment()).thenReturn(app);
        JsonNode input = parse(toolsListWith("{\"name\":\"tool1\"}"));

        JsonNode result = apply(input);

        assertEquals(input, result);
    }

    @Test
    void toolsNotArray_returnsUnchanged() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        JsonNode input = parse("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":\"not-an-array\"}}");

        JsonNode result = apply(input);

        assertEquals(input, result);
    }

    @Test
    void toolWithNoMeta_createsMissingMetaAndSetsDomain() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        JsonNode result = apply(parse(toolsListWith("{\"name\":\"tool1\"}")));

        assertEquals("abc.claudemcpcontent.com", result.path("result").path("tools").get(0)
                .path("_meta").path("ui").path("domain").asText());
    }

    @Test
    void toolWithMetaButNoUi_createsMissingUiAndSetsDomain() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        JsonNode result = apply(parse(toolsListWith("{\"name\":\"tool1\",\"_meta\":{}}")));

        assertEquals("abc.claudemcpcontent.com", result.path("result").path("tools").get(0)
                .path("_meta").path("ui").path("domain").asText());
    }

    @Test
    void toolWithExistingDomain_overwritesDomain() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("new.claudemcpcontent.com"));
        String tool = "{\"name\":\"tool1\",\"_meta\":{\"ui\":{\"domain\":\"old.claudemcpcontent.com\"}}}";
        JsonNode result = apply(parse(toolsListWith(tool)));

        assertEquals("new.claudemcpcontent.com", result.path("result").path("tools").get(0)
                .path("_meta").path("ui").path("domain").asText());
    }

    @Test
    void toolWithOtherUiFields_preservesOtherFields() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        String tool = "{\"name\":\"tool1\",\"_meta\":{\"ui\":{\"visibility\":[\"model\"],\"resourceUri\":\"ui://widget\"}}}";
        JsonNode result = apply(parse(toolsListWith(tool)));

        JsonNode ui = result.path("result").path("tools").get(0).path("_meta").path("ui");
        assertEquals("abc.claudemcpcontent.com", ui.path("domain").asText());
        assertEquals("model", ui.path("visibility").get(0).asText());
        assertEquals("ui://widget", ui.path("resourceUri").asText());
    }

    @Test
    void toolWithNonObjectMeta_skipsToolAndContinues() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        String tools = "{\"name\":\"bad\",\"_meta\":\"not-an-object\"},{\"name\":\"good\"}";
        JsonNode result = apply(parse(toolsListWith(tools)));

        // bad tool: _meta stays as non-object string
        assertTrue(result.path("result").path("tools").get(0).path("_meta").isTextual());
        // good tool: domain was written
        assertEquals("abc.claudemcpcontent.com", result.path("result").path("tools").get(1)
                .path("_meta").path("ui").path("domain").asText());
    }

    @Test
    void toolWithNonObjectUi_skipsToolAndContinues() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        String tools = "{\"name\":\"bad\",\"_meta\":{\"ui\":\"not-an-object\"}},{\"name\":\"good\"}";
        JsonNode result = apply(parse(toolsListWith(tools)));

        assertTrue(result.path("result").path("tools").get(0).path("_meta").path("ui").isTextual());
        assertEquals("abc.claudemcpcontent.com", result.path("result").path("tools").get(1)
                .path("_meta").path("ui").path("domain").asText());
    }

    @Test
    void multipleTools_allGetDomainSet() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        String tools = "{\"name\":\"t1\"},{\"name\":\"t2\"},{\"name\":\"t3\"}";
        JsonNode result = apply(parse(toolsListWith(tools)));

        for (int i = 0; i < 3; i++) {
            assertEquals("abc.claudemcpcontent.com", result.path("result").path("tools").get(i)
                    .path("_meta").path("ui").path("domain").asText());
        }
    }

    @Test
    void emptyToolsList_returnsWithoutError() throws Exception {
        when(context.getDeployment()).thenReturn(appWithMcpApps("abc.claudemcpcontent.com"));
        JsonNode result = apply(parse("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[]}}"));

        assertFalse(result.path("result").path("tools").isMissingNode());
        assertEquals(0, result.path("result").path("tools").size());
    }

    // --- helpers ---

    private JsonNode apply(JsonNode input) throws Exception {
        Future<JsonNode> future = fn.apply(input);
        assertTrue(future.succeeded());
        return future.result();
    }

    private static JsonNode parse(String json) throws Exception {
        return ProxyUtil.MAPPER.readTree(json);
    }

    private static String toolsListWith(String... toolJsons) {
        return "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[" + String.join(",", toolJsons) + "]}}";
    }

    private static Application appWithMcpApps(String domainOverride) {
        Application.Mcp.McpApps mcpApps = new Application.Mcp.McpApps();
        mcpApps.setDomainOverride(domainOverride);
        return appWithMcp(mcpApps);
    }

    private static Application appWithMcp(Application.Mcp.McpApps mcpApps) {
        Application.Mcp mcp = new Application.Mcp();
        mcp.setMcpApps(mcpApps);
        Application app = new Application();
        app.setMcp(mcp);
        return app;
    }
}
