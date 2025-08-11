package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.data.wellknown.ResourceMetadataData;
import com.epam.aidial.core.server.service.WellKnownResourceMetadataService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class WellKnownResourceMetadataController {

    private final WellKnownResourceMetadataService resourceMetadataService;

    public void handle(HttpServerRequest request) {
        var authorizationServers = resourceMetadataService.getAuthorizationServers();
        if (authorizationServers.isEmpty()) {
            request.response()
                    .setStatusCode(HttpStatus.NOT_FOUND.getCode())
                    .end();
            return;
        }

        try {
            ResourceMetadataData data = new ResourceMetadataData();
            data.setAuthorizationServers(authorizationServers);
            data.setResource(resourceMetadataService.resolveResource(request));

            String body = ProxyUtil.MAPPER.writeValueAsString(data);
            request.response()
                    .setStatusCode(HttpStatus.OK.getCode())
                    .putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)
                    .end(body);

        } catch (RuntimeException | JsonProcessingException e) {
            log.error("Failed to process metadata request", e);
            request.response()
                    .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR.getCode())
                    .end();
        }
    }

}
