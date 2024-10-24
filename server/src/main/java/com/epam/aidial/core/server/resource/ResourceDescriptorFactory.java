package com.epam.aidial.core.server.resource;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.UrlUtil;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

public class ResourceDescriptorFactory {

    private static final Set<Character> INVALID_FILE_NAME_CHARS = Set.of('/', '{', '}');
    private static final int MAX_PATH_SIZE = 900;

    /**
     * @param type           resource type
     * @param bucketName     bucket name (encrypted)
     * @param bucketLocation bucket location on blob storage; bucket location must end with /
     * @param path           url encoded relative path; if url path is null or empty we treat it as user home
     */
    public static ResourceDescriptor fromEncoded(ResourceTypes type, String bucketName, String bucketLocation, String path) {
        // in case empty path - treat it as a home folder
        String urlEncodedRelativePath = StringUtils.isBlank(path) ? ResourceDescriptor.PATH_SEPARATOR : path;
        verify(bucketLocation.endsWith(ResourceDescriptor.PATH_SEPARATOR), "Bucket location must end with /");

        String[] encodedElements = urlEncodedRelativePath.split(ResourceDescriptor.PATH_SEPARATOR);
        List<String> elements = Arrays.stream(encodedElements).map(UrlUtil::decodePath).toList();
        elements.forEach(element ->
                verify(isValidFilename(element), "Invalid path provided " + urlEncodedRelativePath)
        );

        ResourceDescriptor resource = from(type, bucketName, bucketLocation, elements, ResourceUtil.isFolder(urlEncodedRelativePath));
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
    public static ResourceDescriptor fromDecoded(ResourceType type, String bucketName, String bucketLocation, String path) {
        // in case empty path - treat it as a home folder
        path = StringUtils.isBlank(path) ? ResourceDescriptor.PATH_SEPARATOR : path;
        verify(bucketLocation.endsWith(ResourceDescriptor.PATH_SEPARATOR), "Bucket location must end with /");

        List<String> elements = Arrays.asList(path.split(ResourceDescriptor.PATH_SEPARATOR));
        return from(type, bucketName, bucketLocation, elements, ResourceUtil.isFolder(path));
    }

    public static ResourceDescriptor fromPublicUrl(String url) {
        return fromUrl(url, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, null);
    }

    public static ResourceDescriptor fromPrivateUrl(String url, EncryptionService encryption) {
        ResourceDescriptor description = fromAnyUrl(url, encryption);

        if (description.isPublic()) {
            throw new IllegalArgumentException("Not private url: " + url);
        }

        return description;
    }

    public static ResourceDescriptor fromAnyUrl(String url, EncryptionService encryption) {
        return fromUrl(url, null, null, encryption);
    }

    /**
     *
     * @param url - resource url, e.g. files/bucket/folder/file.txt
     * @param expectedBucket - matched against the url's bucket if provided.
     * @param expectedLocation - used as bucket location if provided together with expected bucket.
     * @param encryptionService - used to decrypt bucket location if provided.
     */
    private static ResourceDescriptor fromUrl(String url,
                                              @Nullable String expectedBucket,
                                              @Nullable String expectedLocation,
                                              @Nullable EncryptionService encryptionService) {
        String[] parts = url.split(ResourceDescriptor.PATH_SEPARATOR);

        if (parts.length < 2) {
            throw new IllegalArgumentException("Url has less than two segments: " + url);
        }

        if (url.startsWith(ResourceDescriptor.PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Url must not start with " + ResourceDescriptor.PATH_SEPARATOR + ", but: " + url);
        }

        if (parts.length == 2 && !url.endsWith(ResourceDescriptor.PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Url must start with resource/bucket/, but: " + url);
        }

        ResourceTypes resourceType = ResourceTypes.of(UrlUtil.decodePath(parts[0]));
        String bucket = UrlUtil.decodePath(parts[1]);
        String location = null;

        if (bucket.equals(ResourceDescriptor.PUBLIC_BUCKET)) {
            location = ResourceDescriptor.PUBLIC_LOCATION;
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
    public static boolean isValidResourcePath(ResourceDescriptor resourceToUpload) {
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

    private static ResourceDescriptor from(ResourceType type, String bucketName, String bucketLocation,
                                           List<String> paths, boolean isFolder) {
        boolean isEmptyElements = paths.isEmpty();
        String name = isEmptyElements ? null : paths.get(paths.size() - 1);
        List<String> parentFolders = isEmptyElements ? List.of() : paths.subList(0, paths.size() - 1);
        return new ResourceDescriptor(type, name, parentFolders, bucketName, bucketLocation, isFolder);
    }

    private static boolean isValidFilename(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
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
