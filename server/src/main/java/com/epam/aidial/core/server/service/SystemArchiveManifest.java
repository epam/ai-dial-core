package com.epam.aidial.core.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The manifest entry of a system export archive, written by {@link AdminSystemExportService} and read by the system
 * import.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record SystemArchiveManifest(Integer formatVersion, String coreVersion, String createdAt) {

    static final String ENTRY = ".dial-system-export/manifest.json";
    static final int FORMAT_VERSION = 1;
}
