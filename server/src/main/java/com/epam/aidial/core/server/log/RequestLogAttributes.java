package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.budget.CostObject;
import lombok.experimental.UtilityClass;

import java.util.Map;

/**
 * The request attributes every log line of a request carries, on both channels: the console JSON line and the OTLP
 * log record. A value may be null: the console line writes it as null, the OTLP record leaves it out.
 */
@UtilityClass
public class RequestLogAttributes {

    public static void putRequestAttributes(ProxyContext proxyContext, Map<String, Object> attributes) {
        attributes.put("user.project", proxyContext.getProject());
        attributes.put("user.cost_object", proxyContext.getCostObject());
        CostObject.Source costObjectSource = proxyContext.getCostObjectSource();
        if (costObjectSource != null) {
            attributes.put("user.cost_object.source", costObjectSource.toValue());
        }
        attributes.put("user.id", proxyContext.getUserId());
        if (proxyContext.getRequest() != null) {
            attributes.put("request.method", proxyContext.getRequest().method().name());
            attributes.put("request.uri", proxyContext.getRequest().uri());
        }
        if (proxyContext.getResponse().ended()) {
            attributes.put("response.status", proxyContext.getResponse().getStatusMessage());
            attributes.put("response.status.code", proxyContext.getResponse().getStatusCode());
        }
        Route route = proxyContext.getRoute();
        if (route != null) {
            attributes.put("dial.route.name", route.getName());
            attributes.put("dial.route.type", proxyContext.getRouteType().getValue());
        }
    }
}
