package com.epam.aidial.core.function.enhancement;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.function.BaseFunction;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnhanceModelRequestFn extends BaseFunction<ObjectNode> {
    public EnhanceModelRequestFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Model) {
            try {
                context.setRequestBody(enhanceModelRequest(context, tree));
            } catch (Throwable e) {
                context.respond(HttpStatus.BAD_REQUEST);
                log.warn("Can't enhance model request. Trace: {}. Span: {}. Error: {}",
                        context.getTraceId(), context.getSpanId(), e.getMessage());
                return e;
            }
        }
        return null;
    }

    private static Buffer enhanceModelRequest(ProxyContext context, ObjectNode tree) throws Exception {
        Model model = (Model) context.getDeployment();
        String overrideName = model.getOverrideName();
        Buffer requestBody = context.getRequestBody();

        if (overrideName == null) {
            return requestBody;
        }

        tree.remove("model");
        tree.put("model", overrideName);

        return Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree));
    }
}
