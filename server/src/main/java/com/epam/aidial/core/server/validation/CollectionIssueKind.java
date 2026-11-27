package com.epam.aidial.core.server.validation;

/** Why a marker collection could not be fully trusted: references may have been missed. */
public enum CollectionIssueKind {
    SCHEMA_NOT_FOUND,
    /** The remote part of the application type schema could not be retrieved or used. */
    SCHEMA_UNAVAILABLE,
    INSTANCE_INVALID
}
