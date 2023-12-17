package com.epam.aidial.core.storage;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ResourceDescription {
    ResourceType type;
    String name;
    List<String> parentFolders;
    String bucketName;
    String bucketLocation;
    boolean isFolder;

    public String getUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(encodeToUrl(bucketName))
                .append(BlobStorageUtil.PATH_SEPARATOR);
        if (parentFolders != null) {
            String parentPath = parentFolders.stream()
                    .map(ResourceDescription::encodeToUrl)
                    .collect(Collectors.joining(BlobStorageUtil.PATH_SEPARATOR));
            builder.append(parentPath)
                    .append(BlobStorageUtil.PATH_SEPARATOR);
        }
        if (name != null && !isHomeFolder(name)) {
            builder.append(encodeToUrl(name));

            if (isFolder) {
                builder.append(BlobStorageUtil.PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

    public String getAbsoluteFilePath() {
        StringBuilder builder = new StringBuilder();
        if (parentFolders != null) {
            builder.append(getParentPath())
                    .append(BlobStorageUtil.PATH_SEPARATOR);
        }
        if (name != null && !isHomeFolder(name)) {
            builder.append(name);

            if (isFolder) {
                builder.append(BlobStorageUtil.PATH_SEPARATOR);
            }
        }

        return BlobStorageUtil.buildAbsoluteFilePath(type, bucketLocation, builder.toString());
    }

    public String getParentPath() {
        return parentFolders == null ? null : String.join(BlobStorageUtil.PATH_SEPARATOR, parentFolders);
    }

    public static ResourceDescription from(ResourceType type, String bucketName, String bucketLocation, String relativeFilePath) {
        verify(bucketLocation.endsWith(BlobStorageUtil.PATH_SEPARATOR), "Bucket location must end with /");
        verify(!StringUtils.isBlank(relativeFilePath), "Invalid relative path: " + relativeFilePath);

        String[] elements = relativeFilePath.split(BlobStorageUtil.PATH_SEPARATOR);
        List<String> parentFolders = null;
        String name = "/";
        if (elements.length > 0) {
            name = elements[elements.length - 1];
        }
        if (elements.length > 1) {
            String parentPath = relativeFilePath.substring(0, relativeFilePath.length() - name.length() - 1);
            if (!parentPath.isEmpty() && !parentPath.equals(BlobStorageUtil.PATH_SEPARATOR)) {
                parentFolders = List.of(parentPath.split(BlobStorageUtil.PATH_SEPARATOR));
            }
        }

        return new ResourceDescription(type, name, parentFolders, bucketName, bucketLocation, BlobStorageUtil.isFolder(relativeFilePath));
    }

    private static String encodeToUrl(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isHomeFolder(String path) {
        return path.equals(BlobStorageUtil.PATH_SEPARATOR);
    }

    private static void verify(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
