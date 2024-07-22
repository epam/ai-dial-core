package com.epam.aidial.core.util;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.token.DeploymentCostStats;
import com.epam.aidial.core.token.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.math.BigDecimal;

@Slf4j
@UtilityClass
public class ModelCostCalculator {

    public static BigDecimal calculate(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Model model) || context.getDeploymentCostStats() == null) {
            return null;
        }

        Pricing pricing = model.getPricing();
        if (pricing == null) {
            return null;
        }

        return switch (pricing.getUnit()) {
            case TOKEN -> calculate(context.getTokenUsage(), pricing.getPrompt(), pricing.getCompletion());
            case CHAR_WITHOUT_WHITESPACE ->
                    calculate(context.getDeploymentCostStats(), pricing.getPrompt(), pricing.getCompletion());
        };
    }

    private static BigDecimal calculate(TokenUsage tokenUsage, String promptRate, String completionRate) {
        if (tokenUsage == null) {
            return null;
        }
        BigDecimal cost = null;
        if (promptRate != null) {
            cost = new BigDecimal(tokenUsage.getPromptTokens()).multiply(new BigDecimal(promptRate));
        }
        if (completionRate != null) {
            BigDecimal completionCost = new BigDecimal(tokenUsage.getCompletionTokens()).multiply(new BigDecimal(completionRate));
            if (cost != null) {
                cost = cost.add(completionCost);
            } else {
                cost = completionCost;
            }
        }
        return cost;
    }

    private static BigDecimal calculate(DeploymentCostStats deploymentCostStats, String promptRate, String completionRate) {
        BigDecimal cost = null;
        if (promptRate != null) {
            cost = new BigDecimal(deploymentCostStats.getRequestContentLength()).multiply(new BigDecimal(promptRate));
        }
        if (completionRate != null) {
            BigDecimal completionCost = new BigDecimal(deploymentCostStats.getResponseContentLength()).multiply(new BigDecimal(completionRate));
            if (cost == null) {
                cost = completionCost;
            } else {
                cost = cost.add(completionCost);
            }
        }
        return cost;
    }

    public static int getResponseContentLength(ModelType modelType, Buffer responseBody, boolean isStreamingResponse) {
        if (modelType == ModelType.EMBEDDING) {
            return 0;
        }
        try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            ArrayNode choices = (ArrayNode) tree.get("choices");
            if (choices == null) {
                // skip error message
                return 0;
            }
            String nodeName = isStreamingResponse ? "delta" : "message";
            JsonNode contentNode = choices.get(0).get(nodeName).get("content");
            return getLengthWithoutWhitespace(contentNode.textValue());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }


    public static int getRequestContentLength(ModelType modelType, Buffer requestBody) {
        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            return getRequestContentLength(modelType, tree);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static int getRequestContentLength(ModelType modelType, ObjectNode tree) {
        int len;
        if (modelType == ModelType.CHAT) {
            ArrayNode messages = (ArrayNode) tree.get("messages");
            len = 0;
            for (int i = 0; i < messages.size(); i++) {
                JsonNode message = messages.get(i);
                len += getLengthWithoutWhitespace(message.get("content").textValue());
            }
            return len;
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
        return len;
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

}
