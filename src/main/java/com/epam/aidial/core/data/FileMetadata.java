package com.epam.aidial.core.data;

import lombok.Getter;

@Getter
public class FileMetadata extends FileMetadataBase {
    long contentLength;
    String contentType;

    public FileMetadata(String name, String path, long contentLength, String contentType) {
        super(name, path, FileType.FILE);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
