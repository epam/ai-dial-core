package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
     * Prefixes of the two principal bucket-location shapes, {@code Users/<sub>/} and {@code Keys/<project>/}.
     * The server-side bucket builder formats locations with them, and a storage layout recognizes principal
     * locations by them — one set of literals for both, or the two drift.
     */
    public static final String USERS_LOCATION_PREFIX = "Users" + PATH_SEPARATOR;
    public static final String KEYS_LOCATION_PREFIX = "Keys" + PATH_SEPARATOR;

    public static final String DEPLOYMENT_COST_STATS_BUCKET = "deployment_cost_stats";
    public static final String DEPLOYMENT_COST_STATS_LOCATION = DEPLOYMENT_COST_STATS_BUCKET + PATH_SEPARATOR;
    public static final String BACKGROUND_JOB_BUCKET = "background_jobs";
    public static final String BACKGROUND_JOB_LOCATION = BACKGROUND_JOB_BUCKET + PATH_SEPARATOR;
    public static final String RESPONSE_MAPPINGS_BUCKET = "response_mappings";
    public static final String RESPONSE_MAPPINGS_LOCATION = RESPONSE_MAPPINGS_BUCKET + PATH_SEPARATOR;
    public static final String API_KEY_DATA_BUCKET = "api_key_data";
    public static final String API_KEY_DATA_LOCATION = API_KEY_DATA_BUCKET + PATH_SEPARATOR;

    /**
     * Buckets holding platform-internal runtime state rather than anyone's content. They belong to no
     * principal, so a layout has to place them somewhere other than the branches it uses for users and
     * projects, and they are listed here so a layout can enumerate them.
     */
    public static final Set<String> SYSTEM_LOCATIONS = Set.of(
            DEPLOYMENT_COST_STATS_LOCATION, BACKGROUND_JOB_LOCATION, RESPONSE_MAPPINGS_LOCATION,
            API_KEY_DATA_LOCATION);

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
        return getStoragePrefix(StorageLayouts.resolveActive()) + getPathWithinType();
    }

    /**
     * The path {@link #getAbsoluteFilePath()} produces under the legacy layout, whichever layout is active.
     * Anything durable derived from a path — an identifier handed to a user, an encryption AAD — must use
     * this: a physical path is free to change when the layout does, and the stored artifact is not.
     */
    public String getStableFilePath() {
        return getStoragePrefix(LegacyStorageLayout.INSTANCE) + getPathWithinType();
    }

    private String getStoragePrefix(StorageLayout layout) {
        return layout.resolveLocationPrefix(bucketLocation) + layout.resolveTypeFolder(type.group()) + PATH_SEPARATOR;
    }

    private String getPathWithinType() {
        StringBuilder builder = new StringBuilder();

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
        String prefix = getStoragePrefix(StorageLayouts.resolveActive());
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
