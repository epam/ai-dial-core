package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Data
@NoArgsConstructor
public abstract class FileMetadataBase {
    private String name;
    private String parentPath;
    private String bucket;
    private String url;
    private FileType type;
}
