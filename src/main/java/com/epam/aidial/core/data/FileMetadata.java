package com.epam.aidial.core.data;

import lombok.Getter;

@Getter
public class FileMetadata extends FileMetadataBase {
    String id;
    String path;
    long contentLength;
    String contentType;

    public FileMetadata(String id, String name, String path, long contentLength, String contentType) {
        super(name, FileType.FILE);
        this.id = id;
        this.path = path;
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
