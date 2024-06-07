package com.epam.aidial.core.function.enhancement;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Addon;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.controller.DeploymentController;
import com.epam.aidial.core.function.BaseFunction;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class EnhanceAssistantRequestFn extends BaseFunction<ObjectNode> {
    public EnhanceAssistantRequestFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Assistant) {
            try {
                Map.Entry<Buffer, Map<String, String>> enhancedRequest = enhanceAssistantRequest(context, tree);
                context.setRequestBody(enhancedRequest.getKey());
                context.setRequestHeaders(enhancedRequest.getValue());
            } catch (HttpException e) {
                context.respond(e.getStatus(), e.getMessage());
                log.warn("Can't enhance assistant request. Trace: {}. Span: {}. Error: {}",
                        context.getTraceId(), context.getSpanId(), e.getMessage());
                return e;
            } catch (Throwable e) {
                context.respond(HttpStatus.BAD_REQUEST);
                log.warn("Can't enhance assistant request. Trace: {}. Span: {}. Error: {}",
                        context.getTraceId(), context.getSpanId(), e.getMessage());
                return e;
            }
        }
        return null;
    }

    private static Map.Entry<Buffer, Map<String, String>> enhanceAssistantRequest(ProxyContext context, ObjectNode tree)
            throws Exception {
        Config config = context.getConfig();
        Assistant assistant = (Assistant) context.getDeployment();


        ArrayNode messages = (ArrayNode) tree.get("messages");
        if (assistant.getPrompt() != null) {
            deletePrompt(messages);
            insertPrompt(messages, assistant.getPrompt());
        }

        Set<String> names = new LinkedHashSet<>(assistant.getAddons());
        ArrayNode addons = (ArrayNode) tree.get("addons");

        if (addons == null) {
            addons = tree.putArray("addons");
        }

        for (JsonNode addon : addons) {
            String name = addon.get("name").asText("");
            names.add(name);
        }

        addons.removeAll();
        Map<String, String> headers = new HashMap<>();
        int addonIndex = 0;
        for (String name : names) {
            Addon addon = config.getAddons().get(name);
            if (addon == null) {
                throw new HttpException(HttpStatus.NOT_FOUND, "No addon: " + name);
            }

            if (!DeploymentController.hasAccess(context, addon)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden addon: " + name);
            }

            String url = addon.getEndpoint();
            addons.addObject().put("url", url).put("name", name);
            if (addon.getToken() != null && !addon.getToken().isBlank()) {
                headers.put("x-addon-token-" + addonIndex, addon.getToken());
            }
            ++addonIndex;
        }

        String name = tree.get("model").asText(null);
        Model model = config.getModels().get(name);

        if (model == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No model: " + name);
        }

        if (!DeploymentController.hasAccess(context, model)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden model: " + name);
        }

        Buffer updatedBody = Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree));
        return Map.entry(updatedBody, headers);
    }

    private static void deletePrompt(ArrayNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            String role = message.get("role").asText("");

            if ("system".equals(role)) {
                messages.remove(i);
            }
        }
    }

    private static void insertPrompt(ArrayNode messages, String prompt) {
        messages.insertObject(0)
                .put("role", "system")
                .put("content", prompt);
    }
}
