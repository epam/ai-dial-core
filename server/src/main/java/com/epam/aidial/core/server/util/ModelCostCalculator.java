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

    /**
     * The one representation of the response {@link #resolveCost} needs, supplied by the caller from
     * whichever form it already holds - never both. A sealed type rather than a {@code Buffer} parameter
     * sitting next to a {@code JsonNode} one: exactly one variant is ever constructed for a given call, so
     * no method here is ever handed the response in two forms at once.
     */
    public sealed interface ResponseSource {
        /**
         * For a caller that already has the parsed response - the tree it parsed for its own processing.
         */
        record Tree(JsonNode responseTree) implements ResponseSource {
        }

        /**
         * For a caller holding only the raw bytes - nothing has parsed them yet. Token pricing parses them
         * here; character pricing scans them as-is, including per-SSE-frame for a still-streaming body.
         */
        record Body(Buffer responseBody) implements ResponseSource {
        }
    }

    /**
     * Resolves a deployment's cost for one call, reading only the representation of the response its
     * pricing unit actually needs.
     *
     * @param requestBody the request bytes, needed only for character pricing - token pricing never reads it.
     */
    public static BigDecimal resolveCost(RoleBasedEntity roleBasedEntity, TokenUsage usage, Buffer requestBody,
            InterfaceType interfaceType, ResponseSource response) {
        if (!(roleBasedEntity instanceof Model model)) {
            return null;
        }
        Pricing pricing = model.getPricing();
        if (pricing == null) {
            return null;
        }
        return switch (pricing.getUnit()) {
            case "token" -> usage == null ? null
                    : calculateTokenCost(usage, pricing, interfaceType, resolveTree(response));
            case "char_without_whitespace" -> calculateCharCost(
                    model.getType(), requestBody, response, pricing.getPrompt(), pricing.getCompletion());
            default -> null;
        };
    }

    private static JsonNode resolveTree(ResponseSource response) {
        return switch (response) {
            case ResponseSource.Tree tree -> tree.responseTree();
            case ResponseSource.Body body -> body.responseBody() == null
                    ? MissingNode.getInstance() : JsonUtil.tryParse(body.responseBody().getBytes());
        };
    }

    /**
     * Legacy path: for a caller with no already-parsed response (streaming, whose live accumulated node
     * takes priority here over parsing {@code responseBody}, since a streamed body may not even be a single
     * JSON document) - unchanged by the parse-once refactor. New callers that hold exactly one representation
     * use {@link #resolveCost} instead.
     */
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
            case "token" -> {
                if (tokenUsage == null) {
                    yield null;
                }
                // streaming: already accumulated live by a per-event Fn; non-streaming: one cheap whole-body parse
                JsonNode nativeRoot = liveUsageNode != null ? liveUsageNode
                        : responseBody == null ? MissingNode.getInstance() : JsonUtil.tryParse(responseBody.getBytes());
                yield calculateTokenCost(tokenUsage, pricing, interfaceType, nativeRoot);
            }
            case "char_without_whitespace" ->
                    calculateCharCost(model.getType(), requestBody, new ResponseSource.Body(responseBody), pricing.getPrompt(), pricing.getCompletion());
            default -> null;
        };
    }

    /**
     * Cost for a model priced per token. Takes only the parsed response - never the raw body - so the caller
     * (which knows the pricing unit and therefore which representation is actually needed) never has to hand
     * this a {@code Buffer} it would otherwise ignore. See {@link #calculateCharCost} for the other unit.
     */
    public static BigDecimal calculateTokenCost(TokenUsage tokenUsage, Pricing pricing, InterfaceType interfaceType,
            JsonNode responseTree) {
        if (tokenUsage == null) {
            return null;
        }
        String promptRate = pricing.getPrompt();
        String completionRate = pricing.getCompletion();

        UsageEvalContext evalContext = UsageEvalContext.build(interfaceType, responseTree);

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

    /**
     * Cost for a model priced per character. Reads whichever representation of the response
     * {@code response} actually holds - the parsed tree directly, with no round trip, for the common
     * non-streaming case; the raw bytes otherwise (streaming, or a caller that never parsed the body) -
     * matching {@link #calculateTokenCost}'s single-representation contract for the other unit.
     */
    public static BigDecimal calculateCharCost(ModelType modelType, Buffer requestBody, ResponseSource response,
            String promptRate, String completionRate) {
        if (requestBody == null
            || response instanceof ResponseSource.Body body && body.responseBody() == null) {
            log.error("Can't calculate model cost due to missing request body.");
            return null;
        }
        RequestLengthResult requestLengthResult = getRequestContentLength(modelType, requestBody);
        Integer responseLength = getResponseContentLength(modelType, response, requestLengthResult.stream());
        if (responseLength == null) {
            log.error("Can't calculate model cost due to missing response body.");
            return null;
        }
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

    /**
     * Reads the completion length from whichever representation {@code response} holds.
     *
     * @return null when a {@code Body} caller's buffer is missing - the one case {@link #calculateCharCost}
     *         cannot price at all, matching the original null-body guard.
     */
    private static Integer getResponseContentLength(ModelType modelType, ResponseSource response, boolean isStreamingResponse) {
        if (modelType == ModelType.EMBEDDING) {
            return 0;
        }
        // the fast path: a non-streaming caller that already parsed the response reads that tree directly,
        // with no re-serialize-then-reparse round trip. A body that IS streaming can never have parsed as
        // one JsonNode in the first place (SSE framing isn't valid JSON), so a Tree here is only ever a
        // genuinely single-document response - "isStreamingResponse" true at the same time means the
        // request asked to stream but the response wasn't SSE after all; that mismatch is rare enough, and
        // already handled below exactly as it always was, that it doesn't justify holding up the common case.
        if (!isStreamingResponse && response instanceof ResponseSource.Tree tree) {
            return getResponseContentLength(tree.responseTree());
        }
        Buffer responseBody = switch (response) {
            case ResponseSource.Tree tree -> Buffer.buffer(ProxyUtil.convertToString(tree.responseTree()));
            case ResponseSource.Body body -> body.responseBody();
        };
        if (responseBody == null) {
            return null;
        }
        return getResponseContentLength(modelType, responseBody, isStreamingResponse);
    }

    /**
     * The non-streaming byte-scan's exact logic, operating on an already-parsed tree instead of
     * re-deserializing the bytes it was built from - no {@code JsonNode -> String/Buffer -> readTree}
     * round trip, but otherwise the same traversal and the same {@code RuntimeException} wrapping as the
     * {@code Buffer} branch below, so a malformed response fails identically either way.
     */
    private static int getResponseContentLength(JsonNode responseTree) {
        try {
            ObjectNode tree = (ObjectNode) responseTree;
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
