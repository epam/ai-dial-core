package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the router's name in the {@code model} of a decided request's answer, so the client sees the deployment it
 * called; the analytics rows name the one that served in {@code deployment}. Replaces a textual {@code model} on the root, on {@code response}
 * (Responses events) and on {@code message} (Messages {@code message_start}), and adds none.
 */
public class RouterModelFn extends BaseResponseFunction {

    private final String router;

    public RouterModelFn(Proxy proxy, ProxyContext context, String router) {
        super(proxy, context);
        this.router = router;
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        rewrite(tree, router);
        return Future.succeededFuture(tree);
    }

    /**
     * The stream functions with this one first when the request was decided, so the others see what the client gets.
     */
    public static List<BaseResponseFunction> withRouterModel(Proxy proxy, ProxyContext context, List<BaseResponseFunction> functions) {
        return withRouterModel(proxy, context, context.getDecision() == null ? null : context.getDecision().getRouter(), functions);
    }

    /**
     * The same for a stored answer, whose router its response mapping recorded; {@code router} null leaves the list.
     */
    public static List<BaseResponseFunction> withRouterModel(Proxy proxy, ProxyContext context, String router,
                                                             List<BaseResponseFunction> functions) {
        if (router == null) {
            return functions;
        }
        List<BaseResponseFunction> all = new ArrayList<>(functions.size() + 1);
        all.add(new RouterModelFn(proxy, context, router));
        all.addAll(functions);
        return all;
    }

    /**
     * A buffered answer rewritten, or the same bytes when it is not a JSON object.
     */
    public static Buffer rewrite(Buffer body, String router) {
        JsonNode tree = JsonUtil.tryParse(body.getBytes());
        if (!(tree instanceof ObjectNode)) {
            return body;
        }
        rewrite(tree, router);
        return Buffer.buffer(JsonUtil.serialize(tree));
    }

    public static void rewrite(JsonNode tree, String router) {
        if (tree instanceof ObjectNode root) {
            replace(root, router);
            replace(root.get("response"), router);
            replace(root.get("message"), router);
        }
    }

    private static void replace(JsonNode node, String router) {
        if (node instanceof ObjectNode object && object.path("model").isTextual()) {
            object.put("model", router);
        }
    }
}
