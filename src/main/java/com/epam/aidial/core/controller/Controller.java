package com.epam.aidial.core.controller;

import io.vertx.core.Future;

public interface Controller {

    Future<?> handle() throws Exception;
}
