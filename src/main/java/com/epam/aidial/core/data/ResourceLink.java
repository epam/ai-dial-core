package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ResourceLink(String url) {

    @JsonIgnore
    public ResourceType getResourceType() {
        if (url == null) {
            throw new RuntimeException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        return ResourceType.of(paths[0]);
    }

    @JsonIgnore
    public String getBucket() {
        if (url == null) {
            throw new RuntimeException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return paths[1];
    }
}
