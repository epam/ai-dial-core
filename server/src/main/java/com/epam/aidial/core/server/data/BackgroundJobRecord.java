package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.LogContext;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackgroundJobRecord(
        String perRequestKey,
        Boolean isRootSpan,
        String traceId,
        String spanId,
        String parentSpanId,
        String project,
        String userHash,
        String conversationId,
        String jobTitle,
        boolean securedApiKey,
        String deploymentName,
        String parentDeployment,
        String requestProtocol,
        String requestMethod,
        String requestUri,
        long requestTimestamp,
        String requestBody,
        String upstreamEndpoint) {

    public static BackgroundJobRecord from(ProxyContext context) {
        return BackgroundJobRecord.builder()
                .perRequestKey(context.getProxyApiKeyData().getPerRequestKey())
                .isRootSpan(context.isOriginalRequest())
                .traceId(context.getTraceId())
                .spanId(context.getSpanId())
                .parentSpanId(context.getParentSpanId())
                .project(context.getProject())
                .userHash(context.getUserHash())
                .conversationId(context.getRequestHeader(Proxy.HEADER_CONVERSATION_ID))
                .jobTitle(context.getRequestHeader(Proxy.HEADER_JOB_TITLE))
                .securedApiKey(context.isSecuredApiKey())
                .deploymentName(context.getDeployment() != null ? context.getDeployment().getName() : null)
                .parentDeployment(LogContext.getParentDeployment(
                        context.getSourceDeployment(), context.getInterceptors(), context.getExecutionPath()))
                .requestProtocol(context.getRequest().version().alpnName().toUpperCase())
                .requestMethod(context.getRequest().method().name())
                .requestUri(context.getRequest().uri())
                .requestTimestamp(context.getRequestTimestamp())
                .requestBody(context.getRequestBody() != null
                        ? context.getRequestBody().toString(StandardCharsets.UTF_8) : null)
                .upstreamEndpoint(Optional.ofNullable(context.getUpstreamRoute())
                        .map(UpstreamRoute::get)
                        .map(Upstream::getEndpoint)
                        .orElse(null))
                .build();
    }
}
