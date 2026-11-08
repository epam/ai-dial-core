package com.epam.aidial.core.server.validation;

/**
 * Why a marker collection could not be fully trusted. Every issue means that references may have been missed.
 */
public enum CollectionIssueKind {
    /** The application type schema does not exist. */
    SCHEMA_NOT_FOUND,
    /** The remote part of the application type schema could not be retrieved or used. */
    SCHEMA_UNAVAILABLE,
    /** The properties do not conform to the schema. */
    INSTANCE_INVALID
}
