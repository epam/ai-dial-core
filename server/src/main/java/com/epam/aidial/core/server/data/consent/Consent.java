package com.epam.aidial.core.server.data.consent;

import com.epam.aidial.core.config.ResourceAccessType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class Consent {

    private Map<String, Deployment> deployments = new HashMap<>();

    /**
     * The resource half of the consent document: one entry per declared resource dependency
     * (§6.5). Present only for applications that declare dependencies — the field stays null
     * otherwise, so consent documents of non-declaring apps are byte-identical to before.
     * Lombok {@code @Data} folds it into the content-binding compare automatically.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ResourceEntry> resources;

    @Data
    public static class Deployment {
        private boolean consentRequired;
    }

    /** A declared target as consented to: the path exactly as declared (placeholders unresolved), and the access rights. */
    @Data
    public static class ResourceEntry {
        private String url;
        private Set<ResourceAccessType> access = Set.of();
    }
}
