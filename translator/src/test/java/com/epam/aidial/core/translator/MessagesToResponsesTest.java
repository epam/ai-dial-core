package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesToResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode translate(String json) throws Exception {
        // the upstream model comes from the caller, never from the body's own model
        return MessagesToResponses.translateRequest(MAPPER.readTree(json), "upstream", new ToolNames());
    }

    private static String refusal(String json) {
        return assertThrows(UntranslatableRequestException.class, () -> translate(json)).getMessage();
    }

    @Test
    void testTextConversation() throws Exception {
        JsonNode result = translate("""
                {"model":"m","max_tokens":100,"stream":true,"temperature":0.5,"top_p":0.9,
                 "system":[{"type":"text","text":"one"},{"type":"text","text":""},{"type":"text","text":"two"}],
                 "messages":[
                   {"role":"user","content":"hi"},
                   {"role":"assistant","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]},
                   {"role":"user","content":[{"type":"text","text":"more"}]}]}""");

        // stream is the caller's to set: Core is the transport
        assertEquals(MAPPER.readTree("""
                {"model":"upstream",
                 "input":[
                   {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                   {"type":"message","role":"assistant","content":"a\\nb"},
                   {"type":"message","role":"user","content":[{"type":"input_text","text":"more"}]}],
                 "instructions":"one\\n\\ntwo",
                 "store":false,"include":["reasoning.encrypted_content"],"max_output_tokens":100,"temperature":0.5,"top_p":0.9}"""), result);
    }

    @Test
    void testSystemTurnExpiresAfterLaterUserTurn() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"user","content":"1"},
                   {"role":"system","content":"expired","clear_at":"next_user_message"},
                   {"role":"system","content":"kept"},
                   {"role":"user","content":"2"},
                   {"role":"system","content":"current","clear_at":"next_user_message"}]}""");

        JsonNode input = result.path("input");
        assertEquals(4, input.size());
        assertEquals("kept", input.path(1).path("content").path(0).path("text").asText());
        assertEquals("system", input.path(3).path("role").asText());
        assertEquals("current", input.path(3).path("content").path(0).path("text").asText());
    }

    @Test
    void testOutputBudgetIsRaisedToTheMinimumButZeroIsKept() throws Exception {
        assertEquals(16, translate("{\"model\":\"m\",\"max_tokens\":1,\"messages\":[]}").path("max_output_tokens").asInt());
        assertEquals(0, translate("{\"model\":\"m\",\"max_tokens\":0,\"messages\":[]}").path("max_output_tokens").asInt());
        assertFalse(translate("{\"model\":\"m\",\"messages\":[]}").has("max_output_tokens"));
    }

    @Test
    void testUserIdIsSentOnlyWithinTheLimit() throws Exception {
        assertEquals("u1", translate("{\"model\":\"m\",\"messages\":[],\"metadata\":{\"user_id\":\"u1\"}}").path("user").asText());
        String longId = "x".repeat(65);
        assertFalse(translate("{\"model\":\"m\",\"messages\":[],\"metadata\":{\"user_id\":\"" + longId + "\"}}").has("user"));
    }

    @Test
    void testServiceTierIsMappedThroughClosedTable() throws Exception {
        assertEquals("auto", translate("{\"model\":\"m\",\"messages\":[],\"service_tier\":\"auto\"}").path("service_tier").asText());
        assertEquals("default", translate("{\"model\":\"m\",\"messages\":[],\"service_tier\":\"standard_only\"}").path("service_tier").asText());
        assertFalse(translate("{\"model\":\"m\",\"messages\":[],\"service_tier\":\"priority\"}").has("service_tier"));
    }

    @Test
    void testFieldsWithNoCounterpartAreDropped() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[],"top_k":5,"stop_sequences":["x"],"context_management":{},
                 "thinking":{"type":"adaptive"},"ignore_eos":true}""");

        assertFalse(result.has("top_k"));
        assertFalse(result.has("stop_sequences"));
        assertFalse(result.has("context_management"));
        assertFalse(result.has("thinking"));
        assertFalse(result.has("ignore_eos"));
    }

    @Test
    void testToolBlocksInTheWrongTurnAreRefused() {
        String toolUse = refusal("""
                {"model":"m","messages":[{"role":"user","content":[{"type":"tool_use","id":"t","name":"n","input":{}}]}]}""");
        String toolResult = refusal("""
                {"model":"m","messages":[{"role":"assistant","content":[{"type":"tool_result","tool_use_id":"t","content":"x"}]}]}""");

        assertTrue(toolUse.contains("'tool_use' in the user turn"), toolUse);
        assertTrue(toolResult.contains("'tool_result' in the assistant turn"), toolResult);
    }

    @Test
    void testNonTextBlockInSystemTurnIsDropped() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"system","content":[{"type":"text","text":"a"},
                     {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]},
                   {"role":"user","content":"hi"}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"system","content":[{"type":"input_text","text":"a"}]},
                 {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}]"""), result.path("input"));
    }

    @Test
    void testMidConversationBlockInUserTurnPrecedesIt() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"user","content":[{"type":"text","text":"carry on"},{"type":"mid_conv_system","content":"injected"}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"system","content":[{"type":"input_text","text":"injected"}]},
                 {"type":"message","role":"user","content":[{"type":"input_text","text":"carry on"}]}]"""), result.path("input"));
    }

    @Test
    void testMidConversationBlockInAssistantTurnPrecedesIt() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"user","content":"hi"},
                   {"role":"assistant","content":[{"type":"text","text":"hello"},
                     {"type":"mid_conv_system","content":[{"type":"text","text":"be brief"}]}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                 {"type":"message","role":"system","content":[{"type":"input_text","text":"be brief"}]},
                 {"type":"message","role":"assistant","content":"hello"}]"""), result.path("input"));
    }

    @Test
    void testCompactionWithoutSummarySendsNothing() throws Exception {
        // a null content is a failed compaction, which Anthropic treats as a no-op
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"assistant","content":[{"type":"compaction","content":null}]},
                   {"role":"user","content":[{"type":"compaction","content":"Earlier: we fixed X."}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"user","content":[{"type":"input_text","text":"Earlier: we fixed X."}]}]"""),
                result.path("input"));
    }

    @Test
    void testCustomToolBecomesFunctionTool() throws Exception {
        JsonNode request = MAPPER.readTree("""
                {"model":"m","messages":[],"tools":[
                   {"name":"Read","description":"Read a file","input_schema":{"$schema":"http://json-schema.org/draft-07/schema#",
                     "type":"object","properties":{"file_path":{"type":"string"}}}},
                   {"type":"custom","name":"Bash","description":"","input_schema":{"type":"object"},"strict":true},
                   {"input_schema":{"type":"object"},"strict":"yes"}]}""");

        JsonNode result = MessagesToResponses.translateRequest(request, "upstream", new ToolNames());

        assertEquals(MAPPER.readTree("""
                [{"type":"function","name":"Read","description":"Read a file",
                  "parameters":{"type":"object","properties":{"file_path":{"type":"string"}}},"strict":false},
                 {"type":"function","name":"Bash","parameters":{"type":"object"},"strict":true},
                 {"type":"function","name":null,"parameters":{"type":"object"},"strict":false}]"""), result.path("tools"));
        // $schema is left out of a new object, never removed from the client's own schema
        assertTrue(request.path("tools").path(0).path("input_schema").has("$schema"));
    }

    @Test
    void testToolWithoutSchemaGetsAnEmptyObjectSchema() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[],"tools":[{"name":"ping"}]}""");

        assertEquals(MAPPER.readTree("""
                {"type":"object","properties":{}}"""), result.path("tools").path(0).path("parameters"));
    }

    @Test
    void testEmptyToolListSendsNoTools() throws Exception {
        assertFalse(translate("{\"model\":\"m\",\"messages\":[],\"tools\":[]}").has("tools"));
    }

    @Test
    void testOtherToolTypesAreForwardedAsSent() throws Exception {
        String tools = """
                [{"type":"bash_20250124","name":"bash"},
                 {"type":"mcp_toolset","mcp_server_name":"github","default_config":{"enabled":false}},
                 {"type":"future_tool_20990101","name":"x y","cache_control":{"type":"ephemeral"}}]""";

        JsonNode result = translate("{\"model\":\"m\",\"messages\":[],\"tools\":" + tools + "}");

        assertEquals(MAPPER.readTree(tools), result.path("tools"));
    }

    @Test
    void testWebSearchToolsAreRefused() {
        String versioned = refusal("""
                {"model":"m","messages":[],"tools":[{"type":"web_search_20250305","name":"web_search"}]}""");
        String builtin = refusal("""
                {"model":"m","messages":[],"tools":[{"name":"Read"},{"type":"web_search"}]}""");

        assertTrue(versioned.contains("'web_search_20250305'"), versioned);
        assertTrue(builtin.contains("'web_search'"), builtin);
    }

    @Test
    void testMalformedToolFieldsAreRefused() {
        String toolsObject = refusal("{\"model\":\"m\",\"messages\":[],\"tools\":{\"name\":\"Read\"}}");
        String stringEntry = refusal("{\"model\":\"m\",\"messages\":[],\"tools\":[{\"name\":\"Read\"},\"Bash\"]}");
        String numberName = refusal("{\"model\":\"m\",\"messages\":[],\"tools\":[{\"name\":1}]}");
        String numberType = refusal("{\"model\":\"m\",\"messages\":[],\"tools\":[{\"type\":1,\"name\":\"Read\"}]}");
        String stringChoice = refusal("{\"model\":\"m\",\"messages\":[],\"tool_choice\":\"auto\"}");

        assertTrue(toolsObject.contains("'tools'"), toolsObject);
        assertTrue(stringEntry.contains("'tools[1]'"), stringEntry);
        assertTrue(numberName.contains("'tools[0].name'"), numberName);
        assertTrue(numberType.contains("'tools[0].type'"), numberType);
        assertTrue(stringChoice.contains("'tool_choice'"), stringChoice);
    }

    @Test
    void testToolChoiceIsTranslated() throws Exception {
        assertEquals("auto", translate("{\"model\":\"m\",\"messages\":[],\"tool_choice\":{\"type\":\"auto\"}}").path("tool_choice").asText());
        assertEquals("required", translate("{\"model\":\"m\",\"messages\":[],\"tool_choice\":{\"type\":\"any\"}}").path("tool_choice").asText());
        assertEquals("none", translate("{\"model\":\"m\",\"messages\":[],\"tool_choice\":{\"type\":\"none\"}}").path("tool_choice").asText());

        JsonNode forced = translate("""
                {"model":"m","messages":[],"tool_choice":{"type":"tool","name":"Read","disable_parallel_tool_use":true}}""");
        assertEquals(MAPPER.readTree("{\"type\":\"function\",\"name\":\"Read\"}"), forced.path("tool_choice"));
        assertFalse(forced.path("parallel_tool_calls").asBoolean(true));

        JsonNode unknown = translate("""
                {"model":"m","messages":[],"tool_choice":{"type":"allowed","disable_parallel_tool_use":false}}""");
        assertFalse(unknown.has("tool_choice"));
        assertTrue(unknown.path("parallel_tool_calls").asBoolean(false));

        JsonNode nameless = translate("{\"model\":\"m\",\"messages\":[],\"tool_choice\":{\"type\":\"tool\"}}");
        assertEquals(MAPPER.readTree("{\"type\":\"function\",\"name\":null}"), nameless.path("tool_choice"));
        assertFalse(nameless.has("parallel_tool_calls"));
    }

    @Test
    void testToolUseBecomesFunctionCall() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[{"role":"assistant","content":[
                   {"type":"text","text":"Reading it."},
                   {"type":"tool_use","id":"toolu_1","name":"Read","input":{"city":"Kyiv"}},
                   {"type":"tool_use","id":"toolu_2","name":"Read"},
                   {"type":"tool_use","id":"toolu_3","name":"Read","input":{}},
                   {"type":"tool_use","id":"toolu_4","name":"Read","input":{"a":[1,{"b":"é"}]}}]}]}""");

        // spelled as Python's json.dumps spells it: ", " and ": " separators, non-ASCII as a lowercase escape
        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"assistant","content":"Reading it."},
                 {"type":"function_call","call_id":"toolu_1","name":"Read","arguments":"{\\"city\\": \\"Kyiv\\"}"},
                 {"type":"function_call","call_id":"toolu_2","name":"Read","arguments":"{}"},
                 {"type":"function_call","call_id":"toolu_3","name":"Read","arguments":"{}"},
                 {"type":"function_call","call_id":"toolu_4","name":"Read","arguments":"{\\"a\\": [1, {\\"b\\": \\"\\\\u00e9\\"}]}"}]"""),
                result.path("input"));
    }

    @Test
    void testTextAroundToolUseIsSplit() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[{"role":"assistant","content":[
                   {"type":"text","text":"Reading it."},
                   {"type":"tool_use","id":"toolu_1","name":"Read","input":{}},
                   {"type":"text","text":"Done."}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"assistant","content":"Reading it."},
                 {"type":"function_call","call_id":"toolu_1","name":"Read","arguments":"{}"},
                 {"type":"message","role":"assistant","content":"Done."}]"""), result.path("input"));
    }

    @Test
    void testToolResultBecomesFunctionCallOutput() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[{"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"t1","content":"42"},
                   {"type":"tool_result","tool_use_id":"t2","content":[{"type":"text","text":""},{"type":"text","text":"a"},
                     {"type":"tool_reference","tool_name":"Read"},{"type":"text","text":"b"}]},
                   {"type":"tool_result","tool_use_id":"t3"},
                   {"type":"tool_result","tool_use_id":"t4","content":"file not found","is_error":true}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"function_call_output","call_id":"t1","output":"42"},
                 {"type":"function_call_output","call_id":"t2","output":"a\\nb"},
                 {"type":"function_call_output","call_id":"t3","output":""},
                 {"type":"function_call_output","call_id":"t4","output":"Error: file not found"}]"""), result.path("input"));
    }

    @Test
    void testNonTextBlockInToolResultIsRefused() {
        String image = refusal("""
                {"model":"m","messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":[
                   {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]}]}]}""");
        String unknown = refusal("""
                {"model":"m","messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":[
                   {"type":"chart","data":[]}]}]}]}""");

        assertTrue(image.contains("'image'"), image);
        assertTrue(unknown.contains("'chart'"), unknown);
    }

    @Test
    void testToolResultsPrecedeMidConversationTextAndUserMessage() throws Exception {
        // the order the Python translator gives, recorded in fixture cc99849f4693ddae
        JsonNode result = translate("""
                {"model":"m","messages":[{"role":"user","content":[
                   {"type":"text","text":"carry on"},
                   {"type":"mid_conv_system","content":[{"type":"text","text":"injected"}]},
                   {"type":"tool_result","tool_use_id":"toolu_1","content":"42"}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"function_call_output","call_id":"toolu_1","output":"42"},
                 {"type":"message","role":"system","content":[{"type":"input_text","text":"injected"}]},
                 {"type":"message","role":"user","content":[{"type":"input_text","text":"carry on"}]}]"""), result.path("input"));
    }

    @Test
    void testMessagesMustBeAnArray() {
        assertThrows(UntranslatableRequestException.class, () -> translate("""
                {"model":"m","messages":{"role":"user","content":"x"}}"""));
    }

    @Test
    void testUnknownRoleIsRefused() {
        assertThrows(UntranslatableRequestException.class, () -> translate("""
                {"model":"m","messages":[{"role":"tool","content":"x"}]}"""));
    }
}
