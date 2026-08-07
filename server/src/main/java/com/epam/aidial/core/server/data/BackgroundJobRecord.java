package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
        String userId,
        List<String> userRoles,
        String userDisplayName,
        ObjectNode userClaims,
        Map<String, List<String>> requestHeaders,
        String deploymentName,
        String parentDeployment,
        String requestProtocol,
        String requestMethod,
        String requestUri,
        long requestTimestamp,
        String requestBody,
        String upstreamEndpoint) {

    public static BackgroundJobRecord from(ProxyContext context, UnaryOperator<String> keyEncryptor) {
        return BackgroundJobRecord.builder()
                .perRequestKey(keyEncryptor.apply(context.getProxyApiKeyData().getPerRequestKey()))
                .isRootSpan(context.isOriginalRequest())
                .traceId(context.getTraceId())
                .spanId(context.getSpanId())
                .parentSpanId(context.getParentSpanId())
                .project(context.getProject())
                .userHash(context.getUserHash())
                .conversationId(context.getRequestHeader(Proxy.HEADER_CONVERSATION_ID))
                .jobTitle(context.getRequestHeader(Proxy.HEADER_JOB_TITLE))
                .securedApiKey(context.isSecuredApiKey())
                .userId(context.getUserId())
                .userRoles(context.getUserRoles())
                .userDisplayName(context.getUserDisplayName())
                .userClaims(context.getUserClaims())
                .requestHeaders(AnalyticsLogContext.toHeadersMap(context.getRequest().headers()))
                .deploymentName(context.getDeployment() != null ? context.getDeployment().getName() : null)
                .parentDeployment(AnalyticsLogContext.getParentDeployment(
                        context.getSourceDeployment(), context.getInterceptors(), context.getExecutionPath()))
                .requestProtocol(context.getRequest().version().alpnName().toUpperCase())
                .requestMethod(context.getRequest().method().name())
                .requestUri(context.getRequest().uri())
                .requestTimestamp(context.getRequestTimestamp())
                .requestBody(context.getRequestBody().toString(StandardCharsets.UTF_8))
                .upstreamEndpoint(Optional.ofNullable(context.getUpstreamRoute())
                        .map(UpstreamRoute::get)
                        .map(Upstream::getEndpoint)
                        .orElse(null))
                .build();
    }
}
