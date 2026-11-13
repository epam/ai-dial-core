package com.epam.aidial.core.server.validation;

/**
 * A problem met while collecting markers; references may have been missed.
 *
 * @param pointer RFC 6901 pointer relative to {@code application_properties}; empty for the root
 */
public record CollectionIssue(CollectionIssueKind kind, String pointer) {
}
