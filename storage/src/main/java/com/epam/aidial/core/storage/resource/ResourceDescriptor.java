package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

@Getter
@EqualsAndHashCode
@AllArgsConstructor
public class ResourceDescriptor {

    public static final String PATH_SEPARATOR = "/";
    public static final String PUBLIC_BUCKET = "public";
    public static final String PUBLIC_LOCATION = PUBLIC_BUCKET + PATH_SEPARATOR;
    public static final String PLATFORM_BUCKET = "platform";
    public static final String PLATFORM_LOCATION = PLATFORM_BUCKET + PATH_SEPARATOR;

    /**
     * Layout in force for physical paths. Swapped once the tenant-rooted layout is enabled.
     */
    private static final StorageLayout LAYOUT = LegacyStorageLayout.INSTANCE;

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
        builder.append(UrlUtil.encodePathSegment(type.urlSegment()))
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
        builder.append(type.urlSegment())
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
        builder.append(getStoragePrefix());

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
     * Returns the layout-dependent prefix every physical path of this resource starts with: the bucket
     * location followed by the resource-type folder.
     */
    private String getStoragePrefix() {
        return LAYOUT.resolveLocationPrefix(bucketLocation) + LAYOUT.resolveTypeFolder(type.group()) + PATH_SEPARATOR;
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
     * Checks if a resource is a published application system resource that should be hidden from users.
     *
     * <p>Published application system resources are identified by:
     * - Folders that start with a dot (.) - these are hidden folders containing published app versions
     * - Files located within any folder that starts with a dot
     * - Examples: ".quick_app_name_0.0.1/", ".mind_map_name_0.0.2/document.json"
     *
     * <p>These resources should only be accessible by applications (deployments), not by regular users.
     *
     * @return true if this is a published application system resource that should be hidden from users
     */
    public boolean isHidden() {
        return Stream.concat(getParentFolders().stream(), Stream.of(getName()))
                .filter(Objects::nonNull)
                .anyMatch(folder -> folder.startsWith("."));
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
    public ResourceDescriptor resolveByUrl(String url) {
        String prefix = type.group() + PATH_SEPARATOR + bucketName  + PATH_SEPARATOR;
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

    /**
     * If the current resource is a folder the method tries to resolve the given URL to a new resource.
     *
     * @param path - to the resource with decrypted bucket
     */
    public ResourceDescriptor resolveByPath(String path) {
        String prefix = getStoragePrefix();
        if (!isFolder) {
            throw new IllegalStateException("Resource must be a folder");
        }
        if (!path.startsWith(prefix)) {
            throw new IllegalArgumentException("Incompatible description and absolute path");
        }

        String relativePath = path.substring(prefix.length());

        List<String> segments = Arrays.asList(relativePath.split(ResourceDescriptor.PATH_SEPARATOR));

        boolean isEmptySegments = segments.isEmpty();
        String name = isEmptySegments ? null : segments.get(segments.size() - 1);
        List<String> parentFolders = isEmptySegments ? List.of() : segments.subList(0, segments.size() - 1);

        boolean isFolder = UrlUtil.isFolder(path);
        return new ResourceDescriptor(type, name, parentFolders, bucketName, bucketLocation, isFolder);
    }

    /**
     * Calculates the relative path of a file within a folder.
     *
     * @param fileDescriptor The file descriptor to calculate the relative path for.
     * @return The relative path of the file within the folder.
     */
    public String getRelativePath(ResourceDescriptor fileDescriptor) {
        if (!this.isFolder) {
            throw new IllegalStateException("Current resource must be a folder to calculate relative paths.");
        }
        if (!fileDescriptor.getBucketName().equals(this.bucketName)) {
            throw new IllegalArgumentException("File descriptor must belong to the same bucket as the folder.");
        }

        String folderPath = this.getAbsoluteFilePath();
        String filePath = fileDescriptor.getAbsoluteFilePath();

        if (!filePath.startsWith(folderPath)) {
            throw new IllegalArgumentException("File descriptor is not within the folder.");
        }

        return filePath.substring(folderPath.length());
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
