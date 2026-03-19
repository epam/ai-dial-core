package com.epam.aidial.core.server.jsonrpc.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

public record ErrorMessage(int code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode data) {
}