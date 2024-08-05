package com.epam.aidial.core.upstream;

public interface LoadBalancer<T> {
    T get();
}
