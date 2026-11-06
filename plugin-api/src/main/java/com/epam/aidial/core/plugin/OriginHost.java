package com.epam.aidial.core.plugin;

import io.vertx.core.Future;

/**
 * Core, as one request of an origin plugin sees it. It serves the calls the plugin makes to other deployments while
 * it answers that request.
 */
public interface OriginHost {

    /**
     * Calls a deployment in process, as a child pass of the request the plugin answers: Core checks, limits, charges
     * and logs it as a request that deployment's per-request key sends to Core, with no HTTP request into Core.
     *
     * <p>Call it on the context {@link OriginPlugin#handle} was called on. The future completes there with the answer
     * once its status and headers are known, and the answer's body emits there, as an {@link OriginResponse} of a
     * plugin does. An answer of any status, {@code 404} or {@code 429} included, completes the future; it fails only
     * for a call Core cannot serve: a path outside the operations {@link OriginCall#uri()} names, a Responses call
     * with {@code background: true}, or an answer cut before its status.
     *
     * <p>A call ends with the request it belongs to: once the plugin's request has ended, Core cancels the answers
     * still open and answers a later call {@code 401}.
     */
    Future<OriginResponse> call(OriginCall call);

    /**
     * Completes when Core stops waiting for this request's answer: the client went away before the plugin answered,
     * or Core moved on from the answer (the client went away while it was relayed, or a retry). It completes on the
     * context {@link OriginPlugin#handle} was called on and never fails; on a request that ends normally it never
     * completes. A plugin still working should stop and release what it holds.
     *
     * <p>Once the client has gone, an answer that fails ends the request quietly: Core tries no other upstream and
     * writes no analytics row. An answer the plugin still gives is handled as an HTTP origin's answer after its
     * client went away: a model's answer is read to the end and charged.
     */
    Future<Void> cancelled();
}
