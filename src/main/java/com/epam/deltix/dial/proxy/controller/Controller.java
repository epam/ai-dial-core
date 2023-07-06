package com.epam.deltix.dial.proxy.controller;

import io.vertx.core.Future;

public interface Controller {

    Future<?> handle() throws Exception;
}
