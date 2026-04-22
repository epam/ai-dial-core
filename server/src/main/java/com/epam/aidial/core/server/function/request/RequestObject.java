package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Deployment;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;
import java.util.Set;

public interface RequestObject {
    String getModel();
    void setModel(String model);
    boolean isStreaming();
    Set<String> collectAttachments();
    Set<String> collectAppAttachments(List<String> paths);
    List<CacheKey> buildMessageCacheKeys();
    List<CacheKey> buildToolCacheKeys();
    void clearInterceptorSettings();
    void applyDefaults(Deployment deployment);
    byte[] serialize() throws JsonProcessingException;
}
