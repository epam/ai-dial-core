package com.epam.aidial.core.server.mcp;

import com.epam.aidial.core.credentials.service.metadata.AuthorizationChallengeProvider;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Draws the authorization challenge out of an MCP server by opening a connection to it with the
 * MCP Java SDK ({@code io.modelcontextprotocol.sdk:mcp-core}) - the {@code initialize} any MCP
 * client starts with - and reading the 401 that refuses it.
 *
 * <p>Letting the SDK make the request, rather than composing one, keeps it indistinguishable from a
 * real client's: strict servers reject anything else as malformed without issuing the challenge.
 */
@Slf4j
public class McpAuthorizationChallengeProvider implements AuthorizationChallengeProvider {

    // matches the request timeout discovery has always used, so a slow server that challenged before still does
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SDK_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    private static final int UNAUTHORIZED = 401;

    private final McpHttpClientBuilder httpClientBuilder;
    private final Duration timeout;

    public McpAuthorizationChallengeProvider(McpHttpClientBuilder httpClientBuilder) {
        this(httpClientBuilder, TIMEOUT);
    }

    /**
     * @param timeout bounds every request discovery makes to the MCP server, redirects included
     */
    public McpAuthorizationChallengeProvider(McpHttpClientBuilder httpClientBuilder, Duration timeout) {
        this.httpClientBuilder = httpClientBuilder;
        this.timeout = timeout;
    }

    /**
     * Discovery falls back to the well-known locations only when the server answered without a usable
     * challenge - it let the request in, refused it some other way, or replied with something the SDK
     * could not use. A server that could not be reached, or that sent no HTTP response at all, fails
     * discovery instead: the pointer this request exists to collect may be the only thing that names
     * the right authorization server, and falling back without it can silently pick another product's
     * on a host that serves several.
     *
     * <p>Every request the SDK makes here is bounded by the timeout, not only the calls it waits on.
     * After a failed {@code initialize} the SDK also opens a listening stream, and a server that
     * accepts that request without ever answering would otherwise leave it in flight on the shared
     * client indefinitely. The timeout covers the request's redirects too, and the SDK's own waits - for the
     * initialize handshake as well as for replies - get a margin over it, so a missing HTTP response is reported
     * as such rather than as the SDK giving up on a reply it never received.
     */
    @Override
    @SneakyThrows
    public Optional<String> challenge(String resourceEndpoint) {
        try {
            Duration sdkTimeout = timeout.plus(SDK_TIMEOUT_MARGIN);
            McpClientUtils.withSyncClient(resourceEndpoint, sdkTimeout, sdkTimeout, httpClientBuilder.httpClientBuilder(),
                    (builder, body) -> builder.timeout(timeout), client -> null);
            return Optional.empty();
        } catch (Exception e) {
            McpHttpClientTransportAuthorizationException authError =
                    ExceptionUtils.throwableOfType(e, McpHttpClientTransportAuthorizationException.class);
            if (authError != null) {
                HttpResponse.ResponseInfo response = authError.getResponseInfo();
                List<String> challenges = response.headers().allValues("WWW-Authenticate");
                return response.statusCode() == UNAUTHORIZED && !challenges.isEmpty()
                        ? Optional.of(String.join(", ", challenges))
                        : Optional.empty();
            }
            throwIfUnreachable(resourceEndpoint, e);
            throwIfUnanswered(resourceEndpoint, e);
            log.debug("No authorization challenge from {}: {}", resourceEndpoint, e.getMessage());
            return Optional.empty();
        }
    }

    private static void throwIfUnreachable(String resourceEndpoint, Exception e) throws Exception {
        if (ExceptionUtils.throwableOfType(e, UnresolvedAddressException.class) != null) {
            throw new IllegalArgumentException(
                    "Connection failed: The specified endpoint '%s' is invalid or unreachable.".formatted(resourceEndpoint));
        }
        HttpConnectTimeoutException connectTimeout = ExceptionUtils.throwableOfType(e, HttpConnectTimeoutException.class);
        if (connectTimeout != null) {
            throw connectTimeout;
        }
        if (ExceptionUtils.throwableOfType(e, ConnectException.class) != null) {
            throw new ConnectException("Cannot connect to %s".formatted(resourceEndpoint));
        }
    }

    /**
     * The response carries only a short message, and an {@link HttpException} reaches the client without being
     * logged, so the underlying failure - a TLS handshake, a proxy refusing the tunnel - is logged here and kept as
     * the cause; otherwise nothing would record why discovery failed.
     */
    private static void throwIfUnanswered(String resourceEndpoint, Exception e) {
        if (ExceptionUtils.throwableOfType(e, HttpTimeoutException.class) != null) {
            log.warn("MCP server {} did not answer the authorization challenge request", resourceEndpoint, e);
            throw new HttpException(HttpStatus.GATEWAY_TIMEOUT, "MCP server did not answer: %s".formatted(resourceEndpoint), e);
        }
        if (ExceptionUtils.throwableOfType(e, IOException.class) != null) {
            log.warn("Connection to MCP server {} failed during the authorization challenge request", resourceEndpoint, e);
            throw new HttpException(HttpStatus.BAD_GATEWAY, "MCP server connection failed: %s".formatted(resourceEndpoint), e);
        }
    }
}
