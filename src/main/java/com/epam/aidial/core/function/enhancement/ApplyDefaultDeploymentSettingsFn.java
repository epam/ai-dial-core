package com.epam.aidial.core.function.enhancement;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.function.BaseFunction;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseFunction<ObjectNode> {
    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        try {
            if (applyDefaults(context, tree)) {
                context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
            }
            return null;
        } catch (Throwable e) {
            context.respond(HttpStatus.BAD_REQUEST);
            log.warn("Can't apply default parameters to deployment {}. Trace: {}. Span: {}. Error: {}",
                    context.getDeployment().getName(), context.getTraceId(), context.getSpanId(), e.getMessage());
            return e;
        }
    }

    private static boolean applyDefaults(ProxyContext context, ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        boolean applied = false;
        for (Map.Entry<String, Object> e : deployment.getDefaults().entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!tree.has(key)) {
                tree.set(key, ProxyUtil.MAPPER.convertValue(value, JsonNode.class));
                applied = true;
            }
        }

        return applied;
    }
}
