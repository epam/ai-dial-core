package com.epam.aidial.core.data;

public class FolderMetadata extends FileMetadataBase {

    public FolderMetadata(String name, String path) {
        super(name, path, FileType.FOLDER);
    }
}
