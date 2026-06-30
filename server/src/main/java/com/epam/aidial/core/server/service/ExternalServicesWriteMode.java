package com.epam.aidial.core.server.service;

/**
 * How an application write should treat the inline {@code external_services} field.
 *
 * <p>The "field omitted" vs "field sent empty" distinction is only visible in the raw request body — the
 * deserialized {@link com.epam.aidial.core.config.Application} defaults the field to an empty map and cannot
 * tell them apart. So the caller (which sees the body) chooses the mode.
 */
public enum ExternalServicesWriteMode {

    /** The map on the application is the desired state: validate, encrypt at rest, and remove dropped services. */
    OVERRIDE,

    /** The request omitted {@code external_services}; carry the stored services forward untouched. */
    PRESERVE_IF_OMITTED
}
