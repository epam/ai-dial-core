package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.server.validation.CollectionIssueKind;

import java.util.List;

/**
 * What was found in the body of one Deployment: its edges and the problems met. The walk logs each issue with the resource and the
 * pointer, never a value.
 *
 * @param issues the values that do not match their schema and the schemas that exist but cannot be evaluated, so that references may have
 *               been missed; a schema that is missing is not here, because the status of its edge shows it
 */
record DiscoveryResult(List<DiscoveredEdge> edges, List<DiscoveryIssue> issues) {

    /** A problem in finding the references of one resource. It carries no message and no value, because both can hold a credential. */
    record DiscoveryIssue(CollectionIssueKind kind, String pointer) {
    }
}
