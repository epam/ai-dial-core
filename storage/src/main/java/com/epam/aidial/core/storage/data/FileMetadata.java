package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends ResourceItemMetadata {
    long contentLength;
    String contentType;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ResourceReference resource;

    public record ResourceReference(String url, String etag) {
    }

    public FileMetadata(ResourceDescriptor resource, long contentLength, String contentType) {
        super(resource);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
