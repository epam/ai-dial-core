package com.epam.aidial.core.server.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.ContextInternal;
import lombok.experimental.UtilityClass;

@UtilityClass
public class FutureUtil {

    /**
     * Continues a future shared between requests on the calling request's context.
     * Promise.promise() has no context, so the caller's context is bound explicitly (null in plain unit tests).
     */
    public static <T> Future<T> continueOnCallerContext(Future<T> shared) {
        ContextInternal caller = ContextInternal.current();
        Promise<T> promise = caller != null ? caller.promise() : Promise.promise();
        shared.onComplete(promise);
        return promise.future();
    }
}
