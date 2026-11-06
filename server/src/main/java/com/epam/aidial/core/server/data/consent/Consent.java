package com.epam.aidial.core.server.data.consent;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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

    /**
     * One entry per declared dependency, in declaration order — part of the content binding, so a reordered
     * section re-requires consent; null entries (config-file apps bypass validation) are skipped.
     */
    public static List<ResourceEntry> toResourceEntries(@Nullable List<ResourceDependency> declaration) {
        if (declaration == null || declaration.isEmpty()) {
            return List.of();
        }
        List<ResourceEntry> entries = new ArrayList<>(declaration.size());
        for (ResourceDependency dependency : declaration) {
            if (dependency == null) {
                continue;
            }
            ResourceEntry entry = new ResourceEntry();
            entry.setUrl(dependency.getTarget() == null ? null : dependency.getTarget().getPath());
            entry.setAccess(dependency.getAccess() == null ? Set.of() : dependency.getAccess());
            entries.add(entry);
        }
        return entries;
    }

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
