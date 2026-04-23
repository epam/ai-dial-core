package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

public class CollectResponseResponsesAttachmentsFn extends CollectResponseAttachmentsFn {

    public static final List<String> STREAMING_PATHS = List.of(
            "$[?(@.type == 'response.output_item.done' && @.item.type == 'code_interpreter_call')]"
                    + ".item.outputs[?(@.type == 'image')].url",
            "$[?(@.type == 'response.output_item.done' && (@.item.type == 'custom_tool_call_output' || @.item.type == 'function_call_output'))]"
                    + ".item.output[?(@.type == 'input_image')].image_url",
            "$[?(@.type == 'response.output_item.done' && (@.item.type == 'custom_tool_call_output' || @.item.type == 'function_call_output'))]"
                    + ".item.output[?(@.type == 'input_file')].file_url");
    public static final List<String> NON_STREAMING_PATHS = List.of(
            "$.output[?(@.type == 'code_interpreter_call')].outputs[?(@.type == 'image')].url",
            // custom_tool_call_output/function_call_output do not seem to make sense as model outputs.
            // They are included to adhere to the documentation https://developers.openai.com/api/reference/resources/responses/methods/create
            "$.output[?(@.type == 'custom_tool_call_output' || @.type == 'function_call_output')].output[?(@.type == 'input_image')].image_url",
            "$.output[?(@.type == 'custom_tool_call_output' || @.type == 'function_call_output')].output[?(@.type == 'input_file')].file_url");

    public CollectResponseResponsesAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(ObjectNode tree) {
        List<String> paths = context.isStreamingRequest()
                ? STREAMING_PATHS
                : NON_STREAMING_PATHS;
        return ProxyUtil.collectAttachments(tree, paths);
    }
}
