package com.epam.aidial.core.storage.resource;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ResourceUtil {

    public boolean isFolder(String path) {
        return path.endsWith(ResourceDescriptor.PATH_SEPARATOR);
    }

    /**
     * Extracts the root bucket location from a given resource location.
     *
     * <p>If the location represents a nested resource (e.g., a publication bucket),
     * this method ensures that only the root bucket location is returned.
     * Otherwise, the full location is returned unchanged.
     *
     * @param location the resource location to extract the root bucket from
     * @return the root bucket location
     */
    public String getRootLocation(String location) {
        String[] elements = location.split(ResourceDescriptor.PATH_SEPARATOR);

        if (elements.length > 2) {
            return elements[0] + ResourceDescriptor.PATH_SEPARATOR
                    + elements[1] + ResourceDescriptor.PATH_SEPARATOR;
        }

        return location;
    }

}
