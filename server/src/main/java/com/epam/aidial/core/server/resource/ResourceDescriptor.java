package com.epam.aidial.core.server.resource;

import com.epam.aidial.core.server.data.ResourceType;
import com.epam.aidial.core.server.storage.BlobStorageUtil;
import com.epam.aidial.core.server.util.UrlUtil;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

@Data
@AllArgsConstructor
public class ResourceDescriptor {

    public static final String PATH_SEPARATOR = "/";
    ResourceType type;
    String name;
    List<String> parentFolders;
    String originalPath;
    String bucketName;
    String bucketLocation;
    boolean isFolder;

    public String getUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(UrlUtil.encodePathSegment(type.getGroup()))
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

    public String getDecodedUrl() {
        StringBuilder builder = new StringBuilder();
        builder.append(type.getGroup())
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

    public String getAbsoluteFilePath() {
        StringBuilder builder = new StringBuilder();
        builder.append(bucketLocation)
                .append(type.getGroup())
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

    @Nullable
    public ResourceDescriptor getParent() {
        if (parentFolders.isEmpty()) {
            return null;
        }

        String parentFolderName = parentFolders.get(parentFolders.size() - 1);
        return new ResourceDescriptor(type, parentFolderName,
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
        return parentFolders.isEmpty() ? null : String.join(PATH_SEPARATOR, parentFolders);
    }

    public ResourceDescriptor resolveByUrl(String url) {
        String prefix = type.getGroup() + PATH_SEPARATOR + bucketName  + PATH_SEPARATOR;
        return resolve(prefix, url);
    }

    public ResourceDescriptor resolveByPath(String url) {
        String prefix = bucketLocation + type.getGroup() + PATH_SEPARATOR;
        return resolve(prefix, url);
    }

    private ResourceDescriptor resolve(String prefix, String url) {
        if (!isFolder) {
            throw new IllegalStateException("Resource must be folder");
        }
        if (!url.startsWith(prefix)) {
            throw new IllegalArgumentException("Incompatible description and absolute path");
        }

        String relativePath = url.substring(prefix.length());

        List<String> paths = Stream.of(relativePath.split(PATH_SEPARATOR)).map(UrlUtil::decodePath).toList();
        List<String> parentFolders = paths.size() > 1 ? paths.subList(0, paths.size() - 1) : List.of();

        String name = paths.get(paths.size() - 1);
        boolean isFolder = UrlUtil.isFolder(url);
        return new ResourceDescriptor(type, name, parentFolders, relativePath, bucketName, bucketLocation, isFolder);
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
