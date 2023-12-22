package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends FileMetadataBase {
    long contentLength;
    String contentType;

    public FileMetadata(String bucket, String name, String path, String url, long contentLength, String contentType) {
        super(name, path, bucket, url, FileType.FILE);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }

    public FileMetadata(ResourceDescription resource, long contentLength, String contentType) {
        this(resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl(), contentLength, contentType);
    }
}
