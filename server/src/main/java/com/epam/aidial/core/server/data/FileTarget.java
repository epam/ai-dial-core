package com.epam.aidial.core.server.data;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public record FileTarget(ResourceDescriptor descriptor, ResourceDescriptor resource, String filePath) {
    private static final Set<String> HIDDEN_SEGMENTS = Set.of(".dial-resource", ".dial-folder", "v");

    public boolean isComplexFile() {
        return filePath != null;
    }

    public boolean isFile() {
        return descriptor.getType() == ResourceTypes.FILE || isComplexFile();
    }

    public void validatePath() {
        for (String segment : Stream.concat(descriptor.getParentFolders().stream(), Stream.of(descriptor.getName()))
                .filter(Objects::nonNull).toList()) {
            if (isComplexFile() && HIDDEN_SEGMENTS.contains(segment)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "File not found: " + descriptor.getUrl());
            }
            if (segment.contains("\\") || segment.endsWith(".")) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid file path: " + descriptor.getUrl());
            }
        }
    }

    public void requireFile() {
        if (!isFile() || descriptor.isFolder() || "".equals(filePath)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Target must name a file: " + descriptor.getUrl());
        }
    }
}
