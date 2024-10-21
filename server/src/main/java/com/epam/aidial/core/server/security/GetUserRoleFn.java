package com.epam.aidial.core.server.security;

import io.vertx.core.Future;

import java.util.List;
import java.util.Map;

@FunctionalInterface
interface GetUserRoleFn {
    Future<List<String>> apply(String accessToken, Map<String, Object> claims);
}
