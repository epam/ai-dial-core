package com.epam.aidial.core.util;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.token.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Scanner;

@Slf4j
@UtilityClass
public class ModelCostCalculator {

    public static Double calculate(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Model model)) {
            return null;
        }

        Pricing pricing = model.getPricing();
        if (pricing == null) {
            return null;
        }

        return switch (pricing.getUnit()) {
            case "token" -> calculate(context.getTokenUsage(), pricing.getPrompt(), pricing.getCompletion());
            case "char_without_whitespace" ->
                    calculate(model.getType(), context.getRequestBody(), context.getResponseBody(), pricing.getPrompt(), pricing.getCompletion());
            default -> null;
        };
    }

    private static Double calculate(TokenUsage tokenUsage, String promptRate, String completionRate) {
        if (tokenUsage == null) {
            return null;
        }
        double cost = 0.0;
        if (promptRate != null) {
            cost += tokenUsage.getPromptTokens() * Double.parseDouble(promptRate);
        }
        if (completionRate != null) {
            cost += tokenUsage.getCompletionTokens() * Double.parseDouble(completionRate);
        }
        if (promptRate != null || completionRate != null) {
            return cost;
        }
        return null;
    }

    private static Double calculate(ModelType modelType, Buffer requestBody, Buffer responseBody, String promptRate, String completionRate) {
        RequestLengthResult requestLengthResult = getRequestContentLength(modelType, requestBody);
        int responseLength = getResponseContentLength(modelType, responseBody, requestLengthResult.stream());
        double cost = 0.0;
        if (promptRate != null) {
            cost += requestLengthResult.length() * Double.parseDouble(promptRate);
        }
        if (completionRate != null) {
            cost += responseLength * Double.parseDouble(completionRate);
        }
        if (promptRate != null || completionRate != null) {
            return cost;
        }
        return null;
    }

    private static int getResponseContentLength(ModelType modelType, Buffer responseBody, boolean isStreamingResponse) {
        if (modelType == ModelType.EMBEDDING) {
            return 0;
        }
        if (isStreamingResponse) {
            try (Scanner scanner = new Scanner(new ByteBufInputStream(responseBody.getByteBuf()))) {
                scanner.useDelimiter("\n*data: *");
                int len = 0;
                while (scanner.hasNext()) {
                    String chunk = scanner.next();
                    if (chunk.startsWith("[DONE]")) {
                        break;
                    }
                    ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(chunk);
                    ArrayNode choices = (ArrayNode) tree.get("choices");
                    JsonNode contentNode = choices.get(0).get("delta").get("content");
                    if (contentNode != null) {
                        len += getLengthWithoutWhitespace(contentNode.textValue());
                    }
                }
                return len;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
                ArrayNode choices = (ArrayNode) tree.get("choices");
                JsonNode contentNode = choices.get(0).get("message").get("content");
                return getLengthWithoutWhitespace(contentNode.textValue());
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static RequestLengthResult getRequestContentLength(ModelType modelType, Buffer requestBody) {
        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            int len;
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            if (modelType == ModelType.CHAT) {
                ArrayNode messages = (ArrayNode) tree.get("messages");
                len = 0;
                for (int i = 0; i < messages.size(); i++) {
                    JsonNode message = messages.get(i);
                    len += getLengthWithoutWhitespace(message.get("content").textValue());
                }
                return new RequestLengthResult(len, tree.get("stream").asBoolean(false));
            } else {
                JsonNode input = tree.get("input");
                if (input instanceof ArrayNode array) {
                    len = 0;
                    for (int i = 0; i < array.size(); i++) {
                        len += getLengthWithoutWhitespace(array.get(i).textValue());
                    }
                } else {
                    len = getLengthWithoutWhitespace(input.textValue());
                }
            }
            return new RequestLengthResult(len, false);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static int getLengthWithoutWhitespace(String s) {
        if (s == null) {
            return 0;
        }
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != ' ') {
                len++;
            }
        }
        return len;
    }

    private record RequestLengthResult(int length, boolean stream) {

    }

}
