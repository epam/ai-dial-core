package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ResourceLink(String url) {

    @JsonIgnore
    public ResourceType getResourceType() {
        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        return ResourceType.of(paths[0]);
    }

    @JsonIgnore
    public String getBucket() {
        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        return paths[1];
    }
}
