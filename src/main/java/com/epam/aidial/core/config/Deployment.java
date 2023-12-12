package com.epam.aidial.core.config;

import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public abstract class Deployment {
    private String name;
    private String endpoint;
    private String displayName;
    private String iconUrl;
    private String description;
    private Set<String> userRoles = Set.of();
    /**
     * Forward Http header with authorization token when request is sent to deployment.
     * Authorization token is NOT forwarded by default.
     */
    private boolean forwardAuthToken = false;
    /**
     * Forward Http header with API key when request is sent to deployment.
     * API key is forwarded by default.
     */
    private boolean forwardApiKey = true;
    private Features features;
    private List<String> inputAttachmentTypes;
    private Integer maxInputAttachments;
}