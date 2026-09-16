package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.config.StandardField;
import com.epam.aidial.core.server.pricing.PricingRateEvaluator;
import com.epam.aidial.core.server.pricing.UsageEvalContext;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Scanner;

@Slf4j
@UtilityClass
public class ModelCostCalculator {

    public static BigDecimal calculate(
            RoleBasedEntity roleBasedEntity, TokenUsage tokenUsage, Buffer requestBody, Buffer responseBody,
            InterfaceType interfaceType, JsonNode liveUsageNode) {
        if (!(roleBasedEntity instanceof Model model)) {
            return null;
        }

        Pricing pricing = model.getPricing();
        if (pricing == null) {
            return null;
        }

        return switch (pricing.getUnit()) {
            case "token" -> calculate(tokenUsage, pricing, interfaceType, responseBody, liveUsageNode);
            case "char_without_whitespace" ->
                    calculate(model.getType(), requestBody, responseBody, pricing.getPrompt(), pricing.getCompletion());
            default -> null;
        };
    }

    private static BigDecimal calculate(TokenUsage tokenUsage, Pricing pricing, InterfaceType interfaceType,
            Buffer responseBody, JsonNode liveUsageNode) {
        if (tokenUsage == null) {
            return null;
        }
        String promptRate = pricing.getPrompt();
        String completionRate = pricing.getCompletion();

        // streaming: already accumulated live by a per-event Fn; non-streaming: one cheap whole-body parse
        JsonNode nativeRoot = liveUsageNode != null ? liveUsageNode
                : responseBody == null ? MissingNode.getInstance() : JsonUtil.tryParse(responseBody.getBytes());
        UsageEvalContext evalContext = UsageEvalContext.build(interfaceType, nativeRoot);

        PromptTokensDetails details = tokenUsage.getPromptTokensDetails();
        long cachedTokens = evalContext.resolveCounter(StandardField.CACHED_READ_TOKENS)
                .orElseGet(() -> details == null ? 0 : details.getCachedTokens());
        long cacheWriteTokens = evalContext.resolveCounter(StandardField.CACHED_WRITE_TOKENS)
                .orElseGet(() -> details == null ? 0 : details.getCacheWriteTokens());

        String cacheReadRate = resolveRate(pricing.getCacheRead(), evalContext, promptRate);
        String cacheWriteRate = resolveRate(pricing.getCacheWrite(), evalContext, promptRate);

        BigDecimal cost = null;
        if (promptRate != null) {
            long baseTokens = tokenUsage.getPromptTokens() - cachedTokens - cacheWriteTokens;
            cost = new BigDecimal(baseTokens).multiply(new BigDecimal(promptRate));
        }
        cost = addCost(cost, completionRate, tokenUsage.getCompletionTokens());
        cost = addCost(cost, cacheReadRate, cachedTokens);
        cost = addCost(cost, cacheWriteRate, cacheWriteTokens);
        return cost;
    }

    private static BigDecimal calculate(ModelType modelType, Buffer requestBody, Buffer responseBody, String promptRate, String completionRate) {
        if (requestBody == null || responseBody == null) {
            log.error("Can't calculate model cost due to missing request or response body.");
            return null;
        }
        RequestLengthResult requestLengthResult = getRequestContentLength(modelType, requestBody);
        int responseLength = getResponseContentLength(modelType, responseBody, requestLengthResult.stream());
        BigDecimal cost = null;
        if (promptRate != null) {
            cost = new BigDecimal(requestLengthResult.length()).multiply(new BigDecimal(promptRate));
        }
        if (completionRate != null) {
            BigDecimal completionCost = new BigDecimal(responseLength).multiply(new BigDecimal(completionRate));
            if (cost == null) {
                cost = completionCost;
            } else {
                cost = cost.add(completionCost);
            }
        }
        return cost;
    }

    private static String resolveRate(PricingRate pricingRate, UsageEvalContext evalContext, String promptRate) {
        if (pricingRate == null) {
            return promptRate;
        }
        return PricingRateEvaluator.evaluate(pricingRate, evalContext).orElse(promptRate);
    }

    private static BigDecimal addCost(BigDecimal cost, String rate, long tokens) {
        if (rate == null) {
            return cost;
        }
        BigDecimal delta = new BigDecimal(tokens).multiply(new BigDecimal(rate));
        return cost == null ? delta : cost.add(delta);
    }

    private static int getResponseContentLength(ModelType modelType, Buffer responseBody, boolean isStreamingResponse) {
        if (modelType == ModelType.EMBEDDING) {
            return 0;
        }
        if (isStreamingResponse) {
            try (Scanner scanner = new Scanner(new ByteBufInputStream(responseBody.getByteBuf()))) {
                // each chunk is separated by one or multiple new lines with the prefix: 'data:' (except the first chunk)
                // chunks may contain `data:` inside chunk data, which may lead to incorrect parsing
                scanner.useDelimiter("(^data: *|\n+data: *)");
                int len = 0;
                while (scanner.hasNext()) {
                    String chunk = scanner.next();
                    if (chunk.startsWith("[DONE]")) {
                        break;
                    }
                    ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(chunk);
                    ArrayNode choices = (ArrayNode) tree.get("choices");
                    if (choices == null) {
                        // skip error message
                        continue;
                    }
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
                if (choices == null) {
                    // skip error message
                    return 0;
                }
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
                JsonNode streamNode = tree.get("stream");
                boolean isStream = streamNode != null && streamNode.asBoolean(false);
                return new RequestLengthResult(len, isStream);
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
