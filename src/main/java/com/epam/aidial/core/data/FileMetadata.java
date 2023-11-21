package com.epam.aidial.core.data;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FileMetadata extends FileMetadataBase {
    long contentLength;
    String contentType;

    public FileMetadata(String name, String path, long contentLength, String contentType) {
        super(name, path, FileType.FILE);
        this.contentLength = contentLength;
        this.contentType = contentType;
    }
}
