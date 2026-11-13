package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.validation.CollectionIssueKind;

import java.util.List;
import java.util.Set;

/**
 * What was found in the body of one Deployment: its edges and the problems met. The export logs each shown issue with the resource
 * and the pointer, never a value.
 *
 * @param issues the problems, including the schema kinds; {@link #listReportedIssues()} gives the ones that are shown
 */
public record ExportDiscovery(List<DiscoveredEdge> edges, List<DiscoveryIssue> issues) {

    private static final Set<CollectionIssueKind> SHOWN_BY_SCHEMA_EDGES =
            Set.of(CollectionIssueKind.SCHEMA_NOT_FOUND, CollectionIssueKind.SCHEMA_UNAVAILABLE);

    /** A problem in finding the references of one resource. It carries no message and no value, because both can hold a credential. */
    public record DiscoveryIssue(CollectionIssueKind kind, String pointer) {
    }

    /**
     * @return every issue but the two schema kinds, which the schema edge shows, so that a condition is never shown twice
     */
    public List<DiscoveryIssue> listReportedIssues() {
        return issues.stream().filter(issue -> !SHOWN_BY_SCHEMA_EDGES.contains(issue.kind())).toList();
    }
}
