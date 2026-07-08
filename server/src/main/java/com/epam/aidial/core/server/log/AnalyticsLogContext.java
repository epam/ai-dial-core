package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Getter
@Builder
public class AnalyticsLogContext {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    private final String project;
    private final String userHash;
    private final String conversationId;
    private final String jobTitle;
    private final boolean securedApiKey;

    private final String userId;
    private final List<String> userRoles;
    private final String userDisplayName;
    private final Map<String, List<String>> requestHeaders;

    private final String deploymentName;
    private final String parentDeployment;
    private final List<String> executionPath;

    private final String requestProtocol;
    private final String requestMethod;
    private final String requestUri;
    private final long requestTimestamp;
    private final Buffer requestBody;

    private final String upstreamEndpoint;

    private final int responseStatusCode;
    private final Buffer responseBody;
    private final String assembledStreamingResponse;

    private final TokenUsage tokenUsage;

    public static AnalyticsLogContext from(ProxyContext context) {
        Buffer responseBody = context.getResponseBody();
        return AnalyticsLogContext.builder()
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
                .requestHeaders(toHeadersMap(context.getRequest().headers()))
                .deploymentName(context.getDeployment() != null ? context.getDeployment().getName() : null)
                .parentDeployment(getParentDeployment(
                        context.getSourceDeployment(), context.getInterceptors(), context.getExecutionPath()))
                .executionPath(context.getExecutionPath())
                .requestProtocol(context.getRequest().version().alpnName().toUpperCase())
                .requestMethod(context.getRequest().method().name())
                .requestUri(context.getRequest().uri())
                .requestTimestamp(context.getRequestTimestamp())
                .requestBody(context.getRequestBody())
                .upstreamEndpoint(Optional.ofNullable(context.getUpstreamRoute())
                        .map(UpstreamRoute::get)
                        .map(Upstream::getEndpoint)
                        .orElse(null))
                .responseStatusCode(context.getResponse().getStatusCode())
                .responseBody(responseBody)
                .assembledStreamingResponse(context.getAssembledStreamingResponse())
                .tokenUsage(context.getTokenUsage())
                .build();
    }

    public static AnalyticsLogContext from(BackgroundJobRecord record, ResponsesApiClient.TerminalResult result) {
        return AnalyticsLogContext.builder()
                .traceId(record.traceId())
                .spanId(record.spanId())
                .parentSpanId(record.parentSpanId())
                .project(record.project())
                .userHash(record.userHash())
                .conversationId(record.conversationId())
                .jobTitle(record.jobTitle())
                .securedApiKey(record.securedApiKey())
                .userId(record.userId())
                .userRoles(record.userRoles())
                .userDisplayName(record.userDisplayName())
                .requestHeaders(record.requestHeaders())
                .deploymentName(record.deploymentName())
                .parentDeployment(record.parentDeployment())
                .requestProtocol(record.requestProtocol())
                .requestMethod(record.requestMethod())
                .requestUri(record.requestUri())
                .requestTimestamp(record.requestTimestamp())
                .requestBody(Buffer.buffer(record.requestBody()))
                .upstreamEndpoint(record.upstreamEndpoint())
                .responseStatusCode(result == null ? 500 : 200) // 500 when expired
                .responseBody(result == null ? null : result.body())
                .tokenUsage(result == null ? null : result.usage())
                .build();
    }

    public static Map<String, List<String>> toHeadersMap(MultiMap headers) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.getAll(name));
        }
        return map;
    }

    public static String getParentDeployment(String sourceDeployment, List<String> interceptors, List<String> executionPath) {
        if (interceptors == null) {
            return sourceDeployment;
        }
        if (executionPath == null) {
            return null;
        }
        int i = executionPath.size() - 2;
        for (int j = interceptors.size() - 1; i >= 0 && j >= 0; i--, j--) {
            String deployment = executionPath.get(i);
            String interceptor = interceptors.get(j);
            if (!deployment.equals(interceptor)) {
                log.warn("Can't find parent deployment because interceptor path doesn't match: expected - {}, actual - {}", interceptor, deployment);
                return null;
            }
        }
        return i < 0 ? null : executionPath.get(i);
    }
}
