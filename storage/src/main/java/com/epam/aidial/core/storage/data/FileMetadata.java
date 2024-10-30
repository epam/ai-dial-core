package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends ResourceItemMetadata {
    long contentLength;
    String contentType;

    public FileMetadata(ResourceDescriptor resource, long contentLength, String contentType) {
        super(resource);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
