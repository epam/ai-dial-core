package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.validation.CollectionIssueKind;

import java.util.List;
import java.util.Set;

/**
 * What was found in the body of one resource that the export examined: its edges and the problems met. It does not depend
 * on the type of the resource, so a closure and a registry can be built over any type that has a
 * discovery.
 *
 * @param issues the problems, including the schema kinds; use {@link #reportedIssues()} for the ones that are shown
 */
public record ExportDiscovery(List<DiscoveredEdge> edges, List<DiscoveryIssue> issues) {

    private static final Set<CollectionIssueKind> SHOWN_BY_SCHEMA_EDGES =
            Set.of(CollectionIssueKind.SCHEMA_NOT_FOUND, CollectionIssueKind.SCHEMA_UNAVAILABLE);

    /**
     * A problem in finding the references of one resource. It carries no message and no value, because both can hold a
     * credential.
     *
     * @param pointer RFC 6901 pointer into the body of the resource
     */
    public record DiscoveryIssue(CollectionIssueKind kind, String pointer) {
    }

    /**
     * @return whether there is no issue, so no reference could have been missed
     */
    public boolean isComplete() {
        return issues.isEmpty();
    }

    /**
     * @return the issues that the registry lists: every issue but the two schema kinds, which the schema edge shows, so that a
     *         condition is never shown twice
     */
    public List<DiscoveryIssue> reportedIssues() {
        return issues.stream().filter(issue -> !SHOWN_BY_SCHEMA_EDGES.contains(issue.kind())).toList();
    }
}
