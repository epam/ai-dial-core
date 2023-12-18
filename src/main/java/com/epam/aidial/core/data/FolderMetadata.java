package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class FolderMetadata extends FileMetadataBase {

    private List<? extends FileMetadataBase> files;

    public FolderMetadata(String bucket, String name, String path, String url, List<FileMetadataBase> files) {
        super(name, path, bucket, url, FileType.FOLDER);
        this.files = files;
    }

    public FolderMetadata(String bucket, String name, String path, String url) {
        this(bucket, name, path, url, null);
    }

    public FolderMetadata(ResourceDescription resource) {
        this(resource, null);
    }

    public FolderMetadata(ResourceDescription resource, List<FileMetadataBase> files) {
        this(resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl(), files);
    }
}
