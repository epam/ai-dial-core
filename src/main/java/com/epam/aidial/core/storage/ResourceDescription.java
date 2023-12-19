package com.epam.aidial.core.storage;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ResourceDescription {
    ResourceType type;
    String name;
    List<String> parentFolders;
    String originalPath;
    String bucketName;
    String bucketLocation;
    boolean isFolder;

    public String getUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(urlEncode(bucketName))
                .append(BlobStorageUtil.PATH_SEPARATOR);
        if (parentFolders != null) {
            String parentPath = parentFolders.stream()
                    .map(ResourceDescription::urlEncode)
                    .collect(Collectors.joining(BlobStorageUtil.PATH_SEPARATOR));
            builder.append(parentPath)
                    .append(BlobStorageUtil.PATH_SEPARATOR);
        }
        if (name != null && !isHomeFolder(name)) {
            builder.append(urlEncode(name));

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

    /**
     * Creates resource for the given parameters
     *
     * @param type           resource type
     * @param bucketName     bucket name (encrypted)
     * @param bucketLocation bucket location on blob storage; bucket location must end with /
     * @param path           url encoded relative path; url path is null or empty we treat it as user home
     */
    public static ResourceDescription from(ResourceType type, String bucketName, String bucketLocation, String path) {
        // in case empty path - treat it as a home folder
        String urlEncodedRelativePath = StringUtils.isBlank(path) ? BlobStorageUtil.PATH_SEPARATOR : path;
        verify(bucketLocation.endsWith(BlobStorageUtil.PATH_SEPARATOR), "Bucket location must end with /");

        String[] encodedElements = urlEncodedRelativePath.split(BlobStorageUtil.PATH_SEPARATOR);
        List<String> elements = Arrays.stream(encodedElements).map(ResourceDescription::urlDecode).toList();
        elements.forEach(element ->
                verify(isValidFilename(element), "Invalid path provided " + urlEncodedRelativePath)
        );
        List<String> parentFolders = null;
        String name = "/";
        if (!elements.isEmpty()) {
            name = elements.get(elements.size() - 1);
        }
        if (elements.size() > 1) {
            String parentPath = urlEncodedRelativePath.substring(0, urlEncodedRelativePath.length() - name.length() - 1);
            if (!parentPath.isEmpty() && !parentPath.equals(BlobStorageUtil.PATH_SEPARATOR)) {
                parentFolders = List.of(parentPath.split(BlobStorageUtil.PATH_SEPARATOR));
            }
        }

        return new ResourceDescription(type, name, parentFolders, urlEncodedRelativePath, bucketName, bucketLocation,
                BlobStorageUtil.isFolder(urlEncodedRelativePath));
    }

    private static boolean isHomeFolder(String path) {
        return path.equals(BlobStorageUtil.PATH_SEPARATOR);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isValidFilename(String value) {
        return !value.contains(BlobStorageUtil.PATH_SEPARATOR);
    }

    private static void verify(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
