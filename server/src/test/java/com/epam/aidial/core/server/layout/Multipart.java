package com.epam.aidial.core.server.layout;

/**
 * Turns a step's body into a file upload. Files are the bulk of what customers actually store, so the corpus
 * would be missing its largest content type without this.
 */
public record Multipart(String filename, String contentType) {
}
