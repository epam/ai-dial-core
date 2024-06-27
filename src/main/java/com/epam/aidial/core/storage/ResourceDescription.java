package com.epam.aidial.core.storage;

import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.util.UrlUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ResourceDescription {

    private static final Set<Character> INVALID_FILE_NAME_CHARS = Set.of('/', '{', '}');
    private static final int MAX_PATH_SIZE = 900;

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

    public String getDecodedUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(type.getGroup())
                .append(BlobStorageUtil.PATH_SEPARATOR)
                .append(bucketName)
                .append(BlobStorageUtil.PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            String parentPath = String.join(BlobStorageUtil.PATH_SEPARATOR, parentFolders);
            builder.append(parentPath)
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

    @Nullable
    public ResourceDescription getParent() {
        if (parentFolders.isEmpty()) {
            return null;
        }

        String parentFolderName = parentFolders.get(parentFolders.size() - 1);
        return new ResourceDescription(type, parentFolderName,
                parentFolders.subList(0, parentFolders.size() - 1), originalPath, bucketName, bucketLocation, true);
    }

    public boolean isRootFolder() {
        return isFolder && name == null;
    }

    public boolean isPublic() {
        return bucketLocation.equals(BlobStorageUtil.PUBLIC_LOCATION);
    }

    public boolean isPrivate() {
        return !isPublic();
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

        ResourceDescription resource = from(type, bucketName, bucketLocation, urlEncodedRelativePath, elements, BlobStorageUtil.isFolder(urlEncodedRelativePath));
        verify(resource.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8).length <= MAX_PATH_SIZE,
                "Resource path exceeds max allowed size: " + MAX_PATH_SIZE);

        return resource;
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

    public static ResourceDescription fromPublicUrl(String url) {
        return fromUrl(url, BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PUBLIC_LOCATION, null);
    }

    /**
     * @param url      must contain the same bucket and location as resource.
     * @param resource to take bucket and location from.
     */
    public static ResourceDescription fromPrivateUrl(String url, ResourceDescription resource) {
        return fromUrl(url, resource.getBucketName(), resource.getBucketLocation(), null);
    }

    public static ResourceDescription fromPrivateUrl(String url, EncryptionService encryption) {
        ResourceDescription description = fromAnyUrl(url, encryption);

        if (description.isPublic()) {
            throw new IllegalArgumentException("Not private url: " + url);
        }

        return description;
    }

    public static ResourceDescription fromAnyUrl(String url, EncryptionService encryption) {
        return fromUrl(url, null, null, encryption);
    }

    @Override
    public String toString() {
        return getUrl();
    }

    /**
     *
     * @param url - resource url, e.g. files/bucket/folder/file.txt
     * @param expectedBucket - matched against the url's bucket if provided.
     * @param expectedLocation - used as bucket location if provided together with expected bucket.
     * @param encryptionService - used to decrypt bucket location if provided.
     */
    private static ResourceDescription fromUrl(String url,
                                               @Nullable String expectedBucket,
                                               @Nullable String expectedLocation,
                                               @Nullable EncryptionService encryptionService) {
        String[] parts = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (parts.length < 2) {
            throw new IllegalArgumentException("Url has less than two segments: " + url);
        }

        if (url.startsWith(BlobStorageUtil.PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Url must not start with " + BlobStorageUtil.PATH_SEPARATOR + ", but: " + url);
        }

        if (parts.length == 2 && !url.endsWith(BlobStorageUtil.PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Url must start with resource/bucket/, but: " + url);
        }

        ResourceType resourceType = ResourceType.of(UrlUtil.decodePath(parts[0]));
        String bucket = UrlUtil.decodePath(parts[1]);
        String location = null;

        if (bucket.equals(BlobStorageUtil.PUBLIC_BUCKET)) {
            location = BlobStorageUtil.PUBLIC_LOCATION;
        } else if (expectedBucket != null) {
            location = expectedLocation;
        } else if (encryptionService != null) {
            location = encryptionService.decrypt(bucket);
        }

        if (expectedBucket != null && !expectedBucket.equals(bucket)) {
            throw new IllegalArgumentException("Url bucket does not match: " + url);
        }

        if (location == null) {
            throw new IllegalArgumentException("Url has invalid bucket: " + url);
        }

        String relativePath = url.substring(parts[0].length() + parts[1].length() + 2);
        return fromEncoded(resourceType, bucket, location, relativePath);
    }

    /**
     * Azure blob storage do not support files with .(dot) (or sequence of dots) at the end of the file name or folder
     * <a href="https://learn.microsoft.com/en-us/rest/api/storageservices/Naming-and-Referencing-Containers--Blobs--and-Metadata?redirectedfrom=MSDN#blob-names">reference</a>
     *
     * @param resourceToUpload resource to upload
     * @return true if provided resource has valid path to store, otherwise - false
     */
    public static boolean isValidResourcePath(ResourceDescription resourceToUpload) {
        String resourceName = resourceToUpload.getName();

        if (resourceName.endsWith(".")) {
            return false;
        }

        for (String element : resourceToUpload.getParentFolders()) {
            if (element.endsWith(".")) {
                return false;
            }
        }

        return true;
    }

    private static ResourceDescription from(ResourceType type, String bucketName, String bucketLocation,
                                            String originalPath, List<String> paths, boolean isFolder) {
        boolean isEmptyElements = paths.isEmpty();
        String name = isEmptyElements ? null : paths.get(paths.size() - 1);
        List<String> parentFolders = isEmptyElements ? List.of() : paths.subList(0, paths.size() - 1);
        return new ResourceDescription(type, name, parentFolders, originalPath, bucketName, bucketLocation, isFolder);
    }

    private static boolean isValidFilename(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || INVALID_FILE_NAME_CHARS.contains(c)) {
                return false;
            }
        }
        return true;
    }

    private static void verify(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
