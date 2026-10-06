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
     * Runs one lookup per key for all requests asking for it. The cached future is context-free whatever the lookup
     * returns, a failed lookup leaves the cache as soon as it fails, and each caller continues on its own context.
     */
    public static <K, V> Future<V> shareLookup(ConcurrentMap<K, Future<V>> cache, K key, Supplier<Future<V>> lookup) {
        Promise<V> fresh = Promise.promise();
        Future<V> mine = fresh.future();
        Future<V> shared = cache.computeIfAbsent(key, k -> {
            lookup.get().onComplete(fresh);
            return mine;
        });
        if (shared == mine) {
            // registered by the creator only, and outside computeIfAbsent: an already failed lookup fires it at once
            shared.onFailure(error -> cache.remove(key, shared));
        }
        return continueOnCallerContext(shared);
    }
}
