package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.resource.ResourceDescriptor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends ResourceItemMetadata {
    long contentLength;
    String contentType;

    public FileMetadata(String bucket, String name, String path, String url, long contentLength, String contentType) {
        super(ResourceTypes.FILE, bucket, name, path, url);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }

    public FileMetadata(ResourceDescriptor resource, long contentLength, String contentType) {
        super(resource);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
