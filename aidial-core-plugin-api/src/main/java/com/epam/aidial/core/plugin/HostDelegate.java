package com.epam.aidial.core.plugin;

import io.vertx.core.Future;

/**
 * Core, as one request of a plugin sees it. It serves the calls the plugin makes to other deployments while
 * it answers that request.
 *
 * <p>Core creates one host per attempt to answer a request and hands it to {@link Plugin#handle} with that
 * request. It cannot be set up once for the plugin: it carries that request's per-request key, which authenticates
 * the calls, its {@link #cancelled()} signal, and the rule that its calls end with the request. Do not keep it past
 * the answer: a call made after the request ended is answered {@code 401}.
 */
public interface HostDelegate {

    /**
     * Calls a deployment in process, as a child pass of the request the plugin answers: Core checks, limits, charges
     * and logs it as a request that deployment's per-request key sends to Core, with no HTTP request into Core.
     *
     * <p>Call it on the context {@link Plugin#handle} was called on. The future completes there with the answer
     * once its status and headers are known, and the answer's body emits there, as an {@link Response} of a
     * plugin does. An answer of any status, {@code 404} or {@code 429} included, completes the future; it fails only
     * for a call Core cannot serve: a path outside the operations {@link Call#uri()} names, a Responses call
     * with {@code background: true}, or an answer cut before its status.
     *
     * <p>A call ends with the request it belongs to: once the plugin's request has ended, Core cancels the answers
     * still open and answers a later call {@code 401}.
     *
     * <p>On a router's {@code POST /decide} request, the calls carry the decide request's key instead, and they end
     * with the decide call: once the plugin's answer has been read, Core cancels the calls still open. A notice request
     * ({@code X-DIAL-ROUTER-NOTICE}) carries no key, so every call made while answering it is answered {@code 401}.
     * In process a plugin can call only the operations {@link Call#uri()} names; {@code GET /v1/user/info} and
     * file reads go to Core over HTTP with the {@code API-KEY} header of the request.
     */
    Future<Response> call(Call call);

    /**
     * A signal from Core to the plugin; it cancels nothing itself. It is the only way a plugin learns, before it has
     * answered, that its answer is no longer wanted, so that it can abort its own upstream work. After the plugin has
     * answered, Core calls {@link Response#cancel()} too.
     *
     * <p>Completes when Core stops waiting for this request's answer: the client went away before the plugin answered,
     * or Core moved on from the answer (the client went away while it was relayed, or a retry). It completes on the
     * context {@link Plugin#handle} was called on and never fails; on a request that ends normally it never
     * completes. A plugin still working should stop and release what it holds.
     *
     * <p>Once the client has gone, an answer that fails ends the request quietly: Core tries no other upstream and
     * writes no analytics row. An answer the plugin still gives is handled as an HTTP origin's answer after its
     * client went away: a model's answer is read to the end and charged.
     *
     * <p>On a router's {@code POST /decide} request it completes only when the plugin has not finished its answer, the
     * status and the whole body, within the router's {@code decision.timeoutMs}; Core does not watch the client of a
     * decide request, and a plugin that finished its answer, however it turned out, is never told.
     */
    Future<Void> cancelled();
}
