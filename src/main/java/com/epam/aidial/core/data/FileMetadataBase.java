package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Data
@NoArgsConstructor
public abstract class FileMetadataBase {
    private String name;
    private String path;
    private FileType type;
}
