package com.epam.aidial.core.resourceservice.resource;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ResourceUtil {

    public boolean isFolder(String path) {
        return path.endsWith(ResourceDescriptor.PATH_SEPARATOR);
    }

}
