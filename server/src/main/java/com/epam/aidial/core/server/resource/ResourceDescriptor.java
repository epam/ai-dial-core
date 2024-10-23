package com.epam.aidial.core.server.resource;

import com.epam.aidial.core.server.util.UrlUtil;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Getter
@EqualsAndHashCode
@AllArgsConstructor
public class ResourceDescriptor {

    public static final String PATH_SEPARATOR = "/";
    public static final String PUBLIC_BUCKET = "public";
    public static final String PUBLIC_LOCATION = PUBLIC_BUCKET + PATH_SEPARATOR;

    ResourceType type;
    /**
     *  Resource's name or empty if the resource is a folder
     */
    String name;
    /**
     * List of parent folders if any
     */
    List<String> parentFolders;
    /**
     * Encrypted or uglified path to bucket. Usually it's used to return a client to hide a real path to the resource.
     */
    String bucketName;
    /**
     * Decrypted path to bucket. The real path to bucket is applied when the resource is saved to a persistent storage.
     */
    String bucketLocation;
    /**
     * The flag determines if the resource is a folder
     */
    boolean isFolder;

    /**
     * Returns percent encoded url to the resource with encrypted path to the bucket.
     */
    public String getUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(UrlUtil.encodePathSegment(type.group()))
                .append(PATH_SEPARATOR)
                .append(UrlUtil.encodePathSegment(bucketName))
                .append(PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            String parentPath = parentFolders.stream()
                    .map(UrlUtil::encodePathSegment)
                    .collect(Collectors.joining(PATH_SEPARATOR));
            builder.append(parentPath)
                    .append(PATH_SEPARATOR);
        }

        if (name != null) {
            builder.append(UrlUtil.encodePathSegment(name));

            if (isFolder) {
                builder.append(PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

    /**
     * Returns decoded url to the resource with encrypted path to the bucket.
     */
    public String getDecodedUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(type.group())
                .append(PATH_SEPARATOR)
                .append(bucketName)
                .append(PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            String parentPath = String.join(PATH_SEPARATOR, parentFolders);
            builder.append(parentPath)
                    .append(PATH_SEPARATOR);
        }

        if (name != null) {
            builder.append(name);

            if (isFolder) {
                builder.append(PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

    /**
     * Returns an absolute path to the resource in a persistent storage.
     */
    public String getAbsoluteFilePath() {
        StringBuilder builder = new StringBuilder();
        builder.append(bucketLocation)
                .append(type.group())
                .append(PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            builder.append(getParentPath())
                    .append(PATH_SEPARATOR);
        }

        if (name != null) {
            builder.append(name);

            if (isFolder) {
                builder.append(PATH_SEPARATOR);
            }
        }

        return builder.toString();
    }

    /**
     *  Returns the parent resource if any.
     */
    @Nullable
    public ResourceDescriptor getParent() {
        if (parentFolders.isEmpty()) {
            return null;
        }

        String parentFolderName = parentFolders.get(parentFolders.size() - 1);
        return new ResourceDescriptor(type, parentFolderName,
                parentFolders.subList(0, parentFolders.size() - 1), bucketName, bucketLocation, true);
    }

    public boolean isRootFolder() {
        return isFolder && name == null;
    }

    public boolean isPublic() {
        return bucketLocation.equals(PUBLIC_LOCATION);
    }

    public boolean isPrivate() {
        return !isPublic();
    }

    /**
     *  Returns the parent path of the resource if any.
     */
    public String getParentPath() {
        return parentFolders.isEmpty() ? null : String.join(PATH_SEPARATOR, parentFolders);
    }

    /**
     * If the current resource is a folder the method tries to resolve the given URL to a new resource.
     *
     * @param url - to the resource with encrypted bucket
     */
    public ResourceDescriptor resolveEncryptedUrl(String url) {
        String prefix = type.group() + PATH_SEPARATOR + bucketName  + PATH_SEPARATOR;
        return resolve(prefix, url);
    }

    /**
     * If the current resource is a folder the method tries to resolve the given URL to a new resource.
     *
     * @param url - to the resource with decrypted bucket
     */
    public ResourceDescriptor resolveDecryptedUrl(String url) {
        String prefix = bucketLocation + type.group() + PATH_SEPARATOR;
        return resolve(prefix, url);
    }

    private ResourceDescriptor resolve(String prefix, String url) {
        if (!isFolder) {
            throw new IllegalStateException("Resource must be a folder");
        }
        if (!url.startsWith(prefix)) {
            throw new IllegalArgumentException("Incompatible description and absolute path");
        }

        String relativePath = url.substring(prefix.length());

        String[] encodedSegments = relativePath.split(ResourceDescriptor.PATH_SEPARATOR);
        List<String> segments = Arrays.stream(encodedSegments).map(UrlUtil::decodePath).toList();

        boolean isEmptySegments = segments.isEmpty();
        String name = isEmptySegments ? null : segments.get(segments.size() - 1);
        List<String> parentFolders = isEmptySegments ? List.of() : segments.subList(0, segments.size() - 1);

        boolean isFolder = UrlUtil.isFolder(url);
        return new ResourceDescriptor(type, name, parentFolders, bucketName, bucketLocation, isFolder);
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
