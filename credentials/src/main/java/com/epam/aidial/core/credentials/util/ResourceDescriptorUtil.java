package com.epam.aidial.core.credentials.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResourceDescriptorUtil {

    public static final String PATH_SEPARATOR = "/";

    public String getDecryptedUrl(ResourceDescriptor resourceDescriptor) {
        StringBuilder builder = new StringBuilder();
        builder.append(resourceDescriptor.getType().group())
                .append(PATH_SEPARATOR)
                .append(resourceDescriptor.getBucketLocation())
                .append(PATH_SEPARATOR);

        if (!resourceDescriptor.getParentFolders().isEmpty()) {
            String parentPath = String.join(PATH_SEPARATOR, resourceDescriptor.getParentFolders());
            builder.append(parentPath)
                    .append(PATH_SEPARATOR);
        }

        if (resourceDescriptor.getName() != null) {
            builder.append(resourceDescriptor.getName());

            if (resourceDescriptor.isFolder()) {
                builder.append(PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

}
