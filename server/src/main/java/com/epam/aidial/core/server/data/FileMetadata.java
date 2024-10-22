package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.resource.ResourceDescription;
import com.epam.aidial.core.server.resource.ResourceTypeRegistry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends ResourceItemMetadata {
    long contentLength;
    String contentType;

    public FileMetadata(ResourceDescription resource, long contentLength, String contentType) {
        super(resource);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
