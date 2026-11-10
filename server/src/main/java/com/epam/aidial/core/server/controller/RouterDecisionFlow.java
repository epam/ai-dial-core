package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.RouterDecision;
import com.epam.aidial.core.server.function.CollectRequestAttachmentsFn;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.MessagesApiRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.RouterDecisionClient;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.upstream.ProxyResponse;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * A pass's side of a router in decision mode: asking the router, walking its candidates, falling back from a failed
 * one or asking again, telling the router of an answer that broke in front of the client, and the router's analytics
 * row. The decision itself lives on the context; a controller serves each candidate through its own resolve, which it
 * hands over as the re-entry when it decides.
 */
@Slf4j
public class RouterDecisionFlow {

    private final Proxy proxy;
    private final ProxyContext context;

    RouterDecisionFlow(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    /**
     * Asks the router for a decision when the context's deployment is a router in decision mode serving
     * {@code type}, and stores it on the context. True when it decided: the caller then serves
     * {@code context.getDecision().current()} through its own resolve, as if the client had called it.
     *
     * @param body the client's body bytes, read only when the router asks for them or the pass has no bytes of its own
     * @param tree the client's body as the caller parsed it, not yet changed by any candidate; null where the caller
     *             parses only after the decision (deployments-POST)
     * @param reenter serves the decision's current candidate from the start of the caller's flow, the resolve
     *                included: run for each candidate that replaces a failed one
     */
    public Future<Boolean> decideIfRouter(InterfaceType type, Future<Buffer> body, @Nullable ObjectNode tree, Runnable reenter) {
        if (!(context.getDeployment() instanceof Router router)) {
            return Future.succeededFuture(false);
        }
        Future<String> properties = Future.succeededFuture();
        if (router.getApplicationTypeSchemaId() != null) {
            // only a schema not cached yet is downloaded and compiled, which blocks; a cached one is read here
            properties = proxy.getApplicationSchemaService().isSchemaReady(router.getApplicationTypeSchemaId())
                    ? properties.map(ignore -> proxy.getApplicationSchemaService().routerPropertiesHeader(router))
                    : proxy.getTaskExecutor().submit(() -> proxy.getApplicationSchemaService().routerPropertiesHeader(router));
        }
        return properties.compose(header -> {
            context.setRouterPropertiesHeader(header);
            return DeploymentEndpointUtil.decides(router, type)
                    ? decideWith(router, type, body, tree, reenter)
                    : Future.succeededFuture(false);
        });
    }

    private Future<Boolean> decideWith(Router router, InterfaceType type, Future<Buffer> body, @Nullable ObjectNode tree, Runnable reenter) {
        // only a pass below this router's own decision has it in its path: a deployment it chose calls it back
        List<String> path = context.getApiKeyData().getExecutionPath();
        if (path != null && path.contains(router.getName())) {
            return Future.failedFuture(new HttpException(HttpStatus.BAD_GATEWAY, "Router %s re-entered".formatted(router.getName())));
        }
        boolean bytesNeeded = RouterDecisionClient.withBody(router) || inProcessCall(tree);
        // the span first: calls the router makes with the decide key roll up into it, and the candidate's pass re-enters it
        return (bytesNeeded ? body : Future.<Buffer>succeededFuture(null))
                .compose(bytes -> proxy.getTokenStatsTracker().startSpan(context)
                        .compose(ignore -> proxy.getTaskExecutor().submit(() -> prepare(router, type, bytes, tree)))
                        .compose(prepared -> decide(router, type, prepared, reenter)));
    }

    /**
     * An origin plugin's in-process call on an API that names the deployment in the body: a pass with a tree and no
     * bytes of its own.
     */
    private boolean inProcessCall(@Nullable ObjectNode tree) {
        return tree != null && context.isChildPass();
    }

    /**
     * What the first decide request carries, made off the event loop before any candidate changes the client's tree.
     * An in-process call's tree is serialized once and stands in for the client's bytes from here on: the decide body,
     * and the bytes every fallback candidate is served from.
     */
    @SneakyThrows
    private Prepared prepare(Router router, InterfaceType type, @Nullable Buffer read, @Nullable ObjectNode tree) {
        Buffer bytes = read;
        if (inProcessCall(tree) && (bytes == null || bytes.length() == 0)) {
            bytes = Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree));
            context.setOriginalRequestBody(bytes);
        }
        if (!RouterDecisionClient.withBody(router)) {
            return new Prepared(null, null, mintDecideKey(router, type, null));
        }
        // the caller's tree is still the client's here; deployments-POST has none yet and parses the bytes once
        ObjectNode parsed = tree != null ? tree : parse(bytes);
        ApiKeyData key = mintDecideKey(router, type, parsed);
        boolean embedded = DeploymentEndpointUtil.resolvePlugin(router, type) != null;
        // the plugin's own copy, which it may change: the caller's tree is the one the candidate is served from
        ObjectNode pluginTree = !embedded ? null : tree != null ? tree.deepCopy() : parsed;
        return new Prepared(bytes, pluginTree, key);
    }

    /**
     * A decide request's body, the plugin's own copy of the parsed body when the router is embedded, and its key.
     */
    private record Prepared(@Nullable Buffer body, @Nullable ObjectNode tree, ApiKeyData key) {
    }

    private static ObjectNode parse(Buffer bytes) {
        try {
            return ProxyUtil.parseObject(bytes);
        } catch (IOException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * The key the router may call Core with while it decides: the caller's identity and rights, the router last in
     * its path, and read access to the files the request attaches when the router gets the body. It lives only as
     * long as the decide call, so it is not the context's proxy key.
     *
     * @param tree the client's body when the router gets it, read for the attachments; null otherwise
     */
    private ApiKeyData mintDecideKey(Router router, InterfaceType type, @Nullable ObjectNode tree) {
        ApiKeyData key = new ApiKeyData();
        ApiKeyData.initFromContext(key, context);
        if (tree != null) {
            for (String attachment : requestObject(type, tree).collectAttachments()) {
                CollectRequestAttachmentsFn.shareAttachment(proxy, context, attachment, key);
            }
        }
        proxy.getApiKeyStore().assignPerRequestApiKey(key, RouterDecisionClient.keyTtl(router));
        return key;
    }

    private static RequestObject requestObject(InterfaceType type, ObjectNode tree) {
        return switch (type) {
            case OPENAI_RESPONSES -> new ResponsesApiRequest(tree);
            case ANTHROPIC_MESSAGES -> new MessagesApiRequest(tree);
            case OPENAI_CHAT_COMPLETIONS, OPENAI_EMBEDDINGS -> new ChatCompletionRequest(tree);
        };
    }

    private Future<Boolean> decide(Router router, InterfaceType type, Prepared prepared, Runnable reenter) {
        long start = System.currentTimeMillis();
        return askRouter(router, type, prepared.body(), prepared.tree(), prepared.key(), List.of())
                .map(candidates -> {
                    RouterDecision decision = new RouterDecision(router, type, candidates, System.currentTimeMillis() - start);
                    decision.setFirstDecideKey(prepared.key());
                    decision.setReenter(reenter);
                    decision.setHop(routerHop(router));
                    // Core's own headers (CORS, trace ids), restored when a candidate's half-copied answer is dropped
                    decision.setResponseHeaders(MultiMap.caseInsensitiveMultiMap().addAll(context.getResponse().headers()));
                    context.setDecision(decision);
                    return true;
                });
    }

    /**
     * The router row's place: a span of its own under the request's parent, and the parent and path a direct call to
     * the router has. The incoming key's path, as the key minted for this pass does not exist yet.
     */
    private RouterDecision.Hop routerHop(Router router) {
        List<String> incoming = context.getApiKeyData().getExecutionPath();
        List<String> path = new ArrayList<>(incoming == null ? List.of() : incoming);
        path.add(router.getName());
        String parent = AnalyticsLogContext.getParentDeployment(context.getSourceDeployment(), context.getInterceptors(), path);
        return new RouterDecision.Hop(IdGenerator.random().generateSpanId(), context.getParentSpanId(), parent, List.copyOf(path));
    }

    /**
     * One decide request with its own key, invalidated when the call ends; a router named as a candidate fails it.
     */
    private Future<List<String>> askRouter(Router router, InterfaceType type, @Nullable Buffer bytes, @Nullable ObjectNode tree,
                                           ApiKeyData key, List<RouterDecision.Attempt> attempts) {
        return RouterDecisionClient.decide(context, router, type, bytes, tree, key, attempts)
                .onComplete(ignore -> proxy.getApiKeyStore().invalidatePerRequestApiKey(key)
                        .onFailure(error -> log.error("error occurred on invalidating the decide request key", error)))
                .map(candidates -> {
                    for (String candidate : candidates) {
                        if (context.getConfig().getRouters().containsKey(candidate)) {
                            throw new HttpException(HttpStatus.BAD_GATEWAY, "A router cannot be a routing candidate: " + candidate);
                        }
                    }
                    return candidates;
                });
    }

    /**
     * Every candidate so far failed: asks the router again with the attempts, and re-enters with the new verdict's
     * first candidate. When no new verdict can be had (the router fails, answers no usable verdict, or the client
     * left), marks the decision exhausted and runs {@code whenExhausted}, which answers the last failure.
     */
    private void redecide(Runnable whenExhausted) {
        RouterDecision decision = context.getDecision();
        Router router = decision.getRouter();
        Buffer bytes = RouterDecisionClient.withBody(router) ? context.getOriginalRequestBody() : null;
        // the candidates changed the caller's tree: an embedded router's copy comes from the bytes, which nothing changes
        boolean embedded = bytes != null && DeploymentEndpointUtil.resolvePlugin(router, decision.getApi()) != null;
        long start = System.currentTimeMillis();
        proxy.getTokenStatsTracker().unnameSpan(context)
                .recover(error -> {
                    log.warn("Router {} decides again in a span named after the failed candidate", decision.getRouterName(), error);
                    return Future.succeededFuture();
                })
                .compose(ignore -> proxy.getTaskExecutor().submit(() -> new Prepared(bytes, embedded ? parse(bytes) : null,
                        mintKeyLike(decision.getFirstDecideKey(), router))))
                .compose(prepared -> askRouter(router, decision.getApi(), prepared.body(), prepared.tree(), prepared.key(),
                        decision.getAttempts()))
                .onComplete(result -> {
                    if (result.succeeded() && !context.getResponse().closed()) {
                        decision.redecided(result.result(), System.currentTimeMillis() - start);
                        decision.getReenter().run();
                        return;
                    }
                    if (result.failed()) {
                        log.warn("Router {} failed to decide again: {}", decision.getRouterName(), result.cause().getMessage());
                    }
                    decision.exhaust();
                    whenExhausted.run();
                });
    }

    /**
     * A new decide request key with the first one's identity, path and grants: the context now names the failed
     * candidate, so building the key from it again would put the candidate after the router.
     */
    private ApiKeyData mintKeyLike(ApiKeyData first, Router router) {
        ApiKeyData key = ProxyUtil.convertToObject(ProxyUtil.convertToString(first), ApiKeyData.class);
        key.setPerRequestKey(null);
        proxy.getApiKeyStore().assignPerRequestApiKey(key, RouterDecisionClient.keyTtl(router));
        return key;
    }

    private boolean canRedecide(RouterDecision decision) {
        return decision.canRedecide(RouterDecisionClient.maxDecisions(decision.getRouter()));
    }

    /**
     * True when the context's decision has a candidate left, or may ask the router again.
     */
    boolean hasFallback() {
        RouterDecision decision = context.getDecision();
        return decision != null && (decision.hasNext() || canRedecide(decision));
    }

    /**
     * The chosen candidate has interceptors: the request goes to them with {@code request} as its body, and the
     * candidate is called from another pass, which this one cannot fall back from.
     */
    @SneakyThrows
    void handOffToInterceptors(RequestObject request, String candidate) {
        RouterDecision decision = context.getDecision();
        decision.setResolving(false);
        decision.close();
        context.setRequestBody(Buffer.buffer(request.serialize()));
        context.setInitialDeployment(candidate);
    }

    /**
     * A refusal of the candidate being resolved, before anything was sent to it, skips to the next candidate, or
     * after the last one asks the router again: answers null then, and {@code answer} gets the error if no new
     * verdict can be had. Otherwise answers the error to respond with; an unknown last candidate is the router's
     * fault, not the caller's, and is answered 502.
     */
    public Throwable nextCandidateOrError(Throwable error, Consumer<Throwable> answer) {
        RouterDecision decision = context.getDecision();
        if (decision == null || !decision.isResolving()) {
            return error;
        }
        int status;
        String reason;
        if (error instanceof PermissionDeniedException) {
            status = HttpStatus.FORBIDDEN.getCode();
            reason = "forbidden";
        } else if (error instanceof ResourceNotFoundException) {
            status = HttpStatus.NOT_FOUND.getCode();
            reason = "not_found";
        } else if (error instanceof HttpException httpException) {
            status = httpException.getStatus().getCode();
            reason = status == HttpStatus.TOO_MANY_REQUESTS.getCode() ? "limit" : "unavailable";
        } else {
            return error;
        }
        if (!context.getResponse().closed()) {
            // the next candidate starts from the client's bytes, as after a failed call: for an in-process call, the
            // serialization, not the call's tree the refused candidate may have changed
            context.setRequestBody(context.getOriginalRequestBody());
            if (decision.hasNext()) {
                log.warn("Candidate {} of router {} skipped: {}", decision.current(), decision.getRouterName(), error.getMessage());
                decision.skip(status, reason);
                decision.getReenter().run();
                return null;
            }
            if (canRedecide(decision)) {
                log.warn("Candidate {} of router {} skipped, deciding again: {}", decision.current(), decision.getRouterName(), error.getMessage());
                decision.fail(status, reason, false);
                redecide(() -> answer.accept(error));
                return null;
            }
        }
        return error instanceof ResourceNotFoundException
                ? new HttpException(HttpStatus.BAD_GATEWAY, "Router %s chose an unknown deployment".formatted(decision.getRouterName()))
                : error;
    }

    /**
     * After the current candidate's call failed (a connect failure, a retriable status once its own upstreams are
     * exhausted, or a 200 answer that broke before the whole of it arrived), re-enters with the next candidate, or
     * after the last one asks the router again, while no byte has reached the client and the client is still there.
     * True when it did; {@code answerOriginal} then answers the failure if no new verdict can be had.
     */
    boolean retryWithNextCandidate(int status, String reason, Runnable answerOriginal) {
        RouterDecision decision = context.getDecision();
        HttpServerResponse response = context.getResponse();
        if (decision == null || response.headWritten() || response.closed()) {
            return false;
        }
        ProxyResponse answered = context.getProxyResponse();
        // an answer counts as broken only when its 200 body never fully arrived; a background job's accepted job,
        // or a response mapping already saved, would keep running beside the next candidate's
        if (answered != null && (answered.statusCode() != HttpStatus.OK.getCode()
                || context.isUpstreamBodyReceived() || context.isBackgroundJob())) {
            return false;
        }
        boolean next = decision.hasNext();
        if (!next && !canRedecide(decision)) {
            return false;
        }
        boolean received = answered != null;
        if (received) {
            // drop the half-read upstream and the headers its answer already copied to the client response
            context.getProxyRequest().reset();
            context.setProxyResponse(null);
            response.headers().clear().addAll(decision.getResponseHeaders());
            response.setChunked(false);
        }
        // the failed pass ends without answering, so its key would otherwise live until its TTL
        ApiKeyData failedKey = context.getProxyApiKeyData();
        if (failedKey != null && failedKey.getPerRequestKey() != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(failedKey)
                    .onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
        context.setProxyApiKeyData(null);
        // an origin plugin's in-process calls carry the failed candidate's key without a lookup: they end with it
        context.closeChildPasses();
        context.setCacheBreakpointContext(null);
        context.setUpstreamBodyReceived(false);
        context.setSpanUsageStats(null);
        // the failed answer's usage, which a stream can carry before it breaks, is not the next candidate's
        context.setTokenUsage(null);
        context.setUsagePerModel(null);
        context.setRequestBody(context.getOriginalRequestBody());
        if (next) {
            decision.skip(status, reason, received);
            decision.getReenter().run();
        } else {
            decision.fail(status, reason, received);
            redecide(answerOriginal);
        }
        return true;
    }

    /**
     * The chosen deployment's answer broke after bytes of it reached the client: it cannot be replaced, so the router
     * only learns of it.
     */
    void noticeRouter() {
        RouterDecision decision = context.getDecision();
        ProxyResponse answered = context.getProxyResponse();
        List<RouterDecision.Attempt> attempts = new ArrayList<>(decision.getAttempts());
        attempts.add(new RouterDecision.Attempt(decision.current(), answered == null ? HttpStatus.BAD_GATEWAY.getCode() : answered.statusCode(),
                "stream", true, true));
        RouterDecisionClient.notice(context, decision.getRouter(), decision.getApi(), attempts);
    }

    /**
     * The router row of a decided request, written once by the pass that asked the router. Its cost is what this
     * pass's span rolled up: the deployment that served and everything below it, calls made while deciding included.
     */
    Future<Void> writeRouterRow() {
        RouterDecision decision = context.getDecision();
        if (decision == null || !decision.takeRow()) {
            return Future.succeededFuture();
        }
        // the stats the serving report returned, read before the response ended; a pass whose root may end first
        // must not read them after it
        TokenStatsTracker.UsageStats reported = context.getSpanUsageStats();
        Future<TokenStatsTracker.UsageStats> stats = reported != null
                ? Future.succeededFuture(reported) : proxy.getTokenStatsTracker().getUsageStats(context);
        return stats
                .otherwise(error -> {
                    log.warn("Router {} row is written without its cost", decision.getRouterName(), error);
                    return TokenStatsTracker.UsageStats.EMPTY;
                })
                .map(usage -> {
                    AnalyticsLogContext serving = AnalyticsLogContext.from(context, null);
                    proxy.getLogStore().save(AnalyticsLogContext.routerRow(serving, decision.toAnalyticsDecision(), decision.getHop(), usage));
                    return null;
                });
    }
}
