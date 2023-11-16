package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public abstract class FileMetadataBase {
    private String name;
    private String path;
    private FileType type;
}
