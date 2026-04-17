package com.epam.aidial.core.server.function.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public interface RequestObject {
    String getModel();
    void setModel(String model);
    boolean isStreaming();
    Set<String> collectAttachments();
    Set<String> collectAppAttachments(List<String> paths);
    List<CacheKey> buildMessageCacheKeys();
    List<CacheKey> buildToolCacheKeys();
    void clearInterceptorSettings();
    void update(String key, Function<JsonNode, JsonNode> mapper);
    byte[] serialize() throws JsonProcessingException;
}
