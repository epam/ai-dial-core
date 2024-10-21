package com.epam.aidial.core.server.upstream;

public interface LoadBalancer<T> {
    /**
     * Returns next available resource from pool
     */
    T next();
}
