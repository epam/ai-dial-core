package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends ResourceItemMetadata {
    long contentLength;
    String contentType;

    public FileMetadata(
            String bucket,
            String name,
            String path,
            String url,
            long contentLength,
            String contentType,
            Set<ResourceAccessType> permissions) {
        super(ResourceType.FILE, bucket, name, path, url, permissions);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }

    public FileMetadata(
            ResourceDescription resource, long contentLength, String contentType, Set<ResourceAccessType> permissions) {
        super(resource, permissions);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
