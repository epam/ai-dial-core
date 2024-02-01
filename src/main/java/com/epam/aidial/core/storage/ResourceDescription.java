package com.epam.aidial.core.storage;

import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.util.UrlUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

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
        builder.append(UrlUtil.encodePath(type.getGroup()))
                .append(BlobStorageUtil.PATH_SEPARATOR)
                .append(UrlUtil.encodePath(bucketName))
                .append(BlobStorageUtil.PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            String parentPath = parentFolders.stream()
                    .map(UrlUtil::encodePath)
                    .collect(Collectors.joining(BlobStorageUtil.PATH_SEPARATOR));
            builder.append(parentPath)
                    .append(BlobStorageUtil.PATH_SEPARATOR);
        }

        if (name != null) {
            builder.append(UrlUtil.encodePath(name));

            if (isFolder) {
                builder.append(BlobStorageUtil.PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

    public String getAbsoluteFilePath() {
        StringBuilder builder = new StringBuilder();
        builder.append(bucketLocation)
                .append(type.getGroup())
                .append(BlobStorageUtil.PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            builder.append(getParentPath())
                    .append(BlobStorageUtil.PATH_SEPARATOR);
        }

        if (name != null) {
            builder.append(name);

            if (isFolder) {
                builder.append(BlobStorageUtil.PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

    public boolean isRootFolder() {
        return isFolder && name == null;
    }

    public String getParentPath() {
        return parentFolders.isEmpty() ? null : String.join(BlobStorageUtil.PATH_SEPARATOR, parentFolders);
    }

    /**
     * @param type           resource type
     * @param bucketName     bucket name (encrypted)
     * @param bucketLocation bucket location on blob storage; bucket location must end with /
     * @param path           url encoded relative path; if url path is null or empty we treat it as user home
     */
    public static ResourceDescription fromEncoded(ResourceType type, String bucketName, String bucketLocation, String path) {
        // in case empty path - treat it as a home folder
        String urlEncodedRelativePath = StringUtils.isBlank(path) ? BlobStorageUtil.PATH_SEPARATOR : path;
        verify(bucketLocation.endsWith(BlobStorageUtil.PATH_SEPARATOR), "Bucket location must end with /");

        String[] encodedElements = urlEncodedRelativePath.split(BlobStorageUtil.PATH_SEPARATOR);
        List<String> elements = Arrays.stream(encodedElements).map(UrlUtil::decodePath).toList();
        elements.forEach(element ->
                verify(isValidFilename(element), "Invalid path provided " + urlEncodedRelativePath)
        );

        return from(type, bucketName, bucketLocation, urlEncodedRelativePath, elements, BlobStorageUtil.isFolder(urlEncodedRelativePath));
    }

    /**
     * @param type           resource type
     * @param bucketName     bucket name (encrypted)
     * @param bucketLocation bucket location on blob storage; bucket location must end with /
     * @param path           url decoded relative path; if url path is null or empty we treat it as user home
     */
    public static ResourceDescription fromDecoded(ResourceType type, String bucketName, String bucketLocation, String path) {
        // in case empty path - treat it as a home folder
        path = StringUtils.isBlank(path) ? BlobStorageUtil.PATH_SEPARATOR : path;
        verify(bucketLocation.endsWith(BlobStorageUtil.PATH_SEPARATOR), "Bucket location must end with /");

        List<String> elements = Arrays.asList(path.split(BlobStorageUtil.PATH_SEPARATOR));
        return from(type, bucketName, bucketLocation, path, elements, BlobStorageUtil.isFolder(path));
    }

    public static ResourceDescription fromDecoded(ResourceDescription description, String absolutePath) {
        String prefix = description.getBucketLocation() + description.getType().getGroup() + "/";
        if (!absolutePath.startsWith(prefix)) {
            throw new IllegalArgumentException("Incompatible description and absolute path");
        }

        String relativePath = absolutePath.substring(prefix.length());
        return fromDecoded(description.getType(), description.getBucketName(), description.getBucketLocation(), relativePath);
    }

    public static ResourceDescription fromLink(String link) {
        String[] parts = link.split(BlobStorageUtil.PATH_SEPARATOR);

        ResourceType resourceType = ResourceType.of(parts[0]);
        String bucket = parts[1];

        return fromEncoded(resourceType, bucket, "fake/location/", link.substring(bucket.length() + parts[0].length() + 2));
    }

    private static ResourceDescription from(ResourceType type, String bucketName, String bucketLocation,
                                            String originalPath, List<String> paths, boolean isFolder) {
        boolean isEmptyElements = paths.isEmpty();
        String name = isEmptyElements ? null : paths.get(paths.size() - 1);
        List<String> parentFolders = isEmptyElements ? List.of() : paths.subList(0, paths.size() - 1);
        return new ResourceDescription(type, name, parentFolders, originalPath, bucketName, bucketLocation, isFolder);
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
