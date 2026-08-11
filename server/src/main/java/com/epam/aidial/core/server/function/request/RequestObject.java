package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Deployment;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;
import java.util.Set;

/**
 * Common interface for Chat Completions API and Responses API requests.
 */
public interface RequestObject {
    /**
     * Returns the model name used by this request.
     *
     * @return the model name
     */
    String getModel();

    /**
     * Sets the model name for this request.
     *
     * @param model the model name to set
     */
    void setModel(String model);

    /**
     * Indicates whether this request is configured for streaming.
     *
     * @return {@code true} if streaming is enabled; {@code false} otherwise
     */
    boolean isStreaming();

    /**
     * Collects image and file URLs present in the request body.
     *
     * @return a set of attachment URLs found in the request body
     */
    Set<String> collectAttachments();

    /**
     * Collects image and file URLs located at the specified paths.
     *
     * @param paths the paths to inspect
     * @return a set of attachment URLs found at the specified paths
     */
    Set<String> collectAppAttachments(List<String> paths);

    /**
     * Builds upstream cache keys for the given node order (e.g. {@code prefix.body.tools},
     * {@code prefix.body.messages}), one rolling hash cumulative across all nodes in order.
     *
     * @param nodeOrder the node designators to hash, in order
     * @return a list of pathed cache keys, or an empty list if this request shape has no cacheable nodes
     */
    default List<CacheKey> buildCacheKeys(List<String> nodeOrder) {
        return List.of();
    }

    /**
     * Clears interceptor-related settings from this request.
     */
    void clearInterceptorSettings();

    /**
     * Applies default values from the specified deployment configuration to this request.
     *
     * @param deployment the deployment configuration containing default values
     */
    void applyDefaults(Deployment deployment);

    /**
     * Serializes this request to a byte array.
     *
     * @return the serialized request
     * @throws JsonProcessingException if the request cannot be serialized
     */
    byte[] serialize() throws JsonProcessingException;

    /**
     * Indicates whether the response should be stored server-side.
     *
     * @return {@code true} if the response should be stored; {@code false} otherwise
     */
    default boolean isStore() {
        return false;
    }

    /**
     * Indicates whether the request should be processed in the background (asynchronously).
     *
     * @return {@code true} if background processing is requested; {@code false} otherwise
     */
    default boolean isBackground() {
        return false;
    }
}
