package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class Route extends RoleBasedEntity {

    public static final AttachmentPath EMPTY_ATTACHMENT_PATHS = new AttachmentPath();

    @JsonAlias({"response", "dial:response"})
    private Response response;
    @JsonAlias({"rewritePath", "dial:rewritePath"})
    private boolean rewritePath;
    @JsonAlias({"paths", "dial:paths"})
    private List<Pattern> paths = List.of();
    @JsonAlias({"methods", "dial:methods"})
    private Set<String> methods = Set.of();
    @JsonAlias({"upstreams", "dial:upstreams"})
    private List<Upstream> upstreams = List.of();
    /**
     * Indicated max retry attempts to route a single user request.
     */
    @JsonAlias({"maxRetryAttempts", "dial:maxRetryAttempts"})
    private int maxRetryAttempts = 1;
    /**
     * Determines the order the route is resolved. The lower value means the higher priority.
     */
    @Min(value = 0, message = "Order can't be negative")
    @JsonAlias({"order", "dial:order"})
    private int order = Integer.MAX_VALUE;
    @JsonAlias({"permissions", "dial:permissions"})
    private Set<ResourceAccessType> permissions = Set.of();
    @JsonAlias({"attachmentPaths", "dial:attachmentPaths"})
    private AttachmentPath attachmentPaths = EMPTY_ATTACHMENT_PATHS;

    @Data
    public static class Response {
        private int status = 200;
        private String body = "";
    }

    /**
     * The class describes metadata where Core needs to find attachments for auto-sharing.
     */
    @Data
    public static class AttachmentPath {
        /**
         * List of JSON paths in the HTTP request body.
         */
        private List<String> requestBody = List.of();

        /**
         * List of JSON paths in the HTTP response body.
         */
        private List<String> responseBody = List.of();
    }
}
