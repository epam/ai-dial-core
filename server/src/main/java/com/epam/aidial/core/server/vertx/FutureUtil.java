package com.epam.aidial.core.server.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.ContextInternal;
import lombok.experimental.UtilityClass;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@UtilityClass
public class FutureUtil {

    /**
     * Continues a future shared between requests on the calling request's context instead of the context of the
     * request that completes it. Called from a worker or virtual thread that dispatches a context, the continuation
     * moves to that context's event loop. Off any Vert.x context (plain unit tests) it runs where it completes.
     */
    public static <T> Future<T> continueOnCallerContext(Future<T> shared) {
        ContextInternal caller = ContextInternal.current();
        Promise<T> promise = caller != null ? caller.promise() : Promise.promise();
        shared.onComplete(promise);
        return promise.future();
    }

    /**
     * Shares one lookup between all requests asking for the same key. The lookup must return a context-less future
     * (a cached future bound to a request's context would pin that context for as long as it is cached). A failed
     * lookup leaves the cache in the same step as the failure, so no later request picks the failure up, and each
     * caller continues on its own context.
     */
    public static <K, V> Future<V> shared(ConcurrentMap<K, Future<V>> cache, K key, Supplier<Future<V>> lookup) {
        Future<V> shared = cache.computeIfAbsent(key, k -> lookup.get());
        // outside computeIfAbsent on purpose: an already failed future would fire this inside the mapping function
        shared.onFailure(error -> cache.remove(key, shared));
        return continueOnCallerContext(shared);
    }
}
