package com.epam.aidial.core.server.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.ContextInternal;
import lombok.experimental.UtilityClass;

@UtilityClass
public class FutureUtil {

    /**
     * Continues a future shared between requests on the calling request's context instead of the context of the
     * request that completes it. Off any Vert.x context (plain unit tests) the continuation runs where it completes.
     */
    public static <T> Future<T> continueOnCallerContext(Future<T> shared) {
        ContextInternal caller = ContextInternal.current();
        Promise<T> promise = caller != null ? caller.promise() : Promise.promise();
        shared.onComplete(promise);
        return promise.future();
    }
}
