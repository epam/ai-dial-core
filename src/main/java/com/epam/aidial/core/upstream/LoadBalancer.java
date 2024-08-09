package com.epam.aidial.core.upstream;

public interface LoadBalancer<T> {
    /**
     * Returns next available resource from pool
     */
    T next();
}
