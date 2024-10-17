package com.epam.aidial.core.controller;

import io.vertx.core.Future;

import java.io.Serializable;

/**
 *  Common interface for HTTP controllers.
 *  <p>
 *      Note. The interface must extends {@link Serializable} for exposing internal lambda fields in Unit tests.
 *  </p>
 */
public interface Controller extends Serializable {

    /**
     * The controller must return non-null instance of {@link Future}.
     */
    Future<?> handle() throws Exception;
}
