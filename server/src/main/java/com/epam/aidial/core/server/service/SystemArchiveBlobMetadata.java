package com.epam.aidial.core.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The metadata entry of one blob in a system export archive, written by {@link AdminSystemExportService} and read by
 * the system import.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record SystemArchiveBlobMetadata(String contentType, String contentEncoding, String contentDisposition, String contentLanguage,
                                 String cacheControl, String creationDate, String lastModified, Map<String, String> userMetadata) {

    static final String ENTRY_PREFIX = ".dial-system-export/metadata/";
}
