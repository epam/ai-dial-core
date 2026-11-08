package com.epam.aidial.core.server.validation;

/**
 * A problem met while collecting markers. Any problem means that references may have been missed.
 *
 * @param kind    what happened
 * @param pointer RFC 6901 pointer relative to {@code application_properties}; empty for the root
 * @param message internal text for diagnosis; it is never exported or logged by the export
 */
public record CollectionIssue(CollectionIssueKind kind, String pointer, String message) {

    @Override
    public String toString() {
        return "CollectionIssue[kind=" + kind + ", pointer=" + pointer + "]";
    }
}
