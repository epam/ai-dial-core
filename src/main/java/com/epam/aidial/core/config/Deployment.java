package com.epam.aidial.core.config;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public abstract class Deployment {
    private String name;
    private String endpoint;
    private String displayName;
    private String displayVersion;
    private String iconUrl;
    private String description;
    private Set<String> userRoles = Set.of();
    /**
     * Forward Http header with authorization token when request is sent to deployment.
     * Authorization token is NOT forwarded by default.
     */
    private boolean forwardAuthToken = false;
    private Features features;
    private List<String> inputAttachmentTypes;
    private Integer maxInputAttachments;
    /**
     * Default parameters are applied if a request doesn't contain them in OpenAI chat/completions API call.
     */
    private Map<String, Object> defaults = Map.of();
}