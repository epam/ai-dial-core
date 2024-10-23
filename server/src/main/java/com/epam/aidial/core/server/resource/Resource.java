package com.epam.aidial.core.server.resource;

/**
 * Please ignore the class.
 */
public interface Resource {
    String getUrl();

    String getDecodedUrl();

    String getAbsoluteFilePath();

    Resource getParent();

    boolean isRootFolder();

    boolean isPublic();

    boolean isPrivate();

    String getParentPath();

    boolean isFolder();

    ResourceType getType();

    String getBucketName();

    String getName();
}
