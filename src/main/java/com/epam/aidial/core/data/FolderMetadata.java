package com.epam.aidial.core.data;

public class FolderMetadata extends FileMetadataBase {

    public FolderMetadata(String name) {
        super(name, FileType.FOLDER);
    }
}
