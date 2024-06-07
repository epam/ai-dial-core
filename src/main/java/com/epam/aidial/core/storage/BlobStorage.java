package com.epam.aidial.core.storage;

import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.credential.CredentialProvider;
import com.epam.aidial.core.storage.credential.CredentialProviderFactory;
import io.vertx.core.buffer.Buffer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.Tier;
import org.jclouds.blobstore.domain.internal.BlobMetadataImpl;
import org.jclouds.blobstore.domain.internal.MutableStorageMetadataImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.io.ContentMetadata;
import org.jclouds.io.ContentMetadataBuilder;
import org.jclouds.io.payloads.BaseMutableContentMetadata;
import org.jclouds.s3.domain.ObjectMetadataBuilder;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Slf4j
public class BlobStorage implements Closeable {

    // S3 implementation do not return a blob content type without additional head request.
    // To avoid additional request for each blob in the listing we try to recognize blob content type by its extension.
    // Default value is binary/octet-stream, see org.jclouds.s3.domain.ObjectMetadataBuilder
    private static final String DEFAULT_CONTENT_TYPE = ObjectMetadataBuilder.create().build().getContentMetadata().getContentType();

    private final BlobStoreContext storeContext;
    private final BlobStore blobStore;
    private final String bucketName;

    // defines a root folder for all resources in bucket
    @Getter
    @Nullable
    private final String prefix;

    public BlobStorage(Storage config) {
        String provider = config.getProvider();
        ContextBuilder builder = ContextBuilder.newBuilder(provider);
        if (config.getEndpoint() != null) {
            builder.endpoint(config.getEndpoint());
        }
        Properties overrides = config.getOverrides();
        if (overrides != null) {
            builder.overrides(overrides);
        }
        CredentialProvider credentialProvider = CredentialProviderFactory.create(provider, config.getIdentity(), config.getCredential());
        builder.credentialsSupplier(credentialProvider::getCredentials);
        this.storeContext = builder.buildView(BlobStoreContext.class);
        this.blobStore = storeContext.getBlobStore();
        this.bucketName = config.getBucket();
        this.prefix = config.getPrefix();
        createBucketIfNeeded(config);
    }

    /**
     * Initialize multipart upload
     *
     * @param absoluteFilePath absolute path according to the bucket, for example: Users/user1/files/input/file.txt
     * @param contentType      MIME type of the content, for example: text/csv
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public MultipartUpload initMultipartUpload(String absoluteFilePath, String contentType) {
        String storageLocation = getStorageLocation(absoluteFilePath);
        BlobMetadata metadata = buildBlobMetadata(storageLocation, contentType, bucketName);
        return blobStore.initiateMultipartUpload(bucketName, metadata, PutOptions.NONE);
    }

    /**
     * Upload part/chunk of the file
     *
     * @param multipart MultipartUpload that chunk related to
     * @param part      chunk number, starting from 1
     * @param buffer    data
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public MultipartPart storeMultipartPart(MultipartUpload multipart, int part, Buffer buffer) {
        return blobStore.uploadMultipartPart(multipart, part, new BufferPayload(buffer));
    }

    /**
     * Commit multipart upload.
     * This method must be called after all parts/chunks uploaded
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public void completeMultipartUpload(MultipartUpload multipart, List<MultipartPart> parts) {
        blobStore.completeMultipartUpload(multipart, parts);
    }

    /**
     * Abort multipart upload.
     * This method must be called if something was wrong during upload to clean up uploaded parts/chunks
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public void abortMultipartUpload(MultipartUpload multipart) {
        blobStore.abortMultipartUpload(multipart);
    }

    /**
     * Upload file in a single request
     *
     * @param absoluteFilePath absolute path according to the bucket, for example: Users/user1/files/input/file.txt
     * @param contentType      MIME type of the content, for example: text/csv
     * @param data             whole content data
     */
    public void store(String absoluteFilePath, String contentType, Buffer data) {
        String storageLocation = getStorageLocation(absoluteFilePath);
        Blob blob = blobStore.blobBuilder(storageLocation)
                .payload(new BufferPayload(data))
                .contentLength(data.length())
                .contentType(contentType)
                .build();

        blobStore.putBlob(bucketName, blob);
    }

    /**
     * Upload file in a single request
     *
     * @param absoluteFilePath absolute path according to the bucket, for example: Users/user1/files/input/file.txt
     * @param contentType      MIME type of the content, for example: text/csv
     * @param contentEncoding  content encoding, e.g. gzip/brotli/deflate
     * @param data             whole content data
     */
    public void store(String absoluteFilePath,
                      String contentType,
                      String contentEncoding,
                      Map<String, String> metadata,
                      byte[] data) {
        String storageLocation = getStorageLocation(absoluteFilePath);
        Blob blob = blobStore.blobBuilder(storageLocation)
                .payload(data)
                .contentLength(data.length)
                .contentType(contentType)
                .contentEncoding(contentEncoding)
                .userMetadata(metadata)
                .build();

        blobStore.putBlob(bucketName, blob);
    }

    /**
     * Load file content from blob store
     *
     * @param filePath absolute file path, for example: Users/user1/files/inputs/data.csv
     * @return Blob instance if file was found, null - otherwise
     */
    public Blob load(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        return blobStore.getBlob(bucketName, storageLocation);
    }

    public boolean exists(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        return blobStore.blobExists(bucketName, storageLocation);
    }

    public BlobMetadata meta(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        return blobStore.blobMetadata(bucketName, storageLocation);
    }

    /**
     * Delete file content from blob store
     *
     * @param filePath absolute file path, for example: Users/user1/files/inputs/data.csv
     */
    public void delete(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        blobStore.removeBlob(bucketName, storageLocation);
    }

    public boolean copy(String fromPath, String toPath) {
        blobStore.copyBlob(bucketName, getStorageLocation(fromPath), bucketName, getStorageLocation(toPath), CopyOptions.NONE);
        return true;
    }

    /**
     * List all files/folder metadata for a given resource
     */
    public MetadataBase listMetadata(ResourceDescription resource, String afterMarker, int maxResults, boolean recursive) {
        ListContainerOptions options = buildListContainerOptions(resource.getAbsoluteFilePath(), maxResults, recursive, afterMarker);
        PageSet<? extends StorageMetadata> list = blobStore.list(this.bucketName, options);
        List<MetadataBase> filesMetadata = list.stream().map(meta -> buildFileMetadata(resource, meta)).toList();

        // listing folder
        if (resource.isFolder()) {
            boolean isEmpty = filesMetadata.isEmpty() && !resource.isRootFolder();
            return isEmpty ? null : new ResourceFolderMetadata(resource, filesMetadata, list.getNextMarker());
        } else {
            // listing file
            if (filesMetadata.size() == 1) {
                return filesMetadata.get(0);
            }
            return null;
        }
    }

    public PageSet<? extends StorageMetadata> list(String absoluteFilePath, String afterMarker, int maxResults, boolean recursive) {
        ListContainerOptions options = buildListContainerOptions(absoluteFilePath, maxResults, recursive, afterMarker);

        PageSet<? extends StorageMetadata> originalSet = blobStore.list(bucketName, options);
        if (prefix == null) {
            return originalSet;
        }
        // if prefix defined - subtract it from blob key
        String nextMarker = originalSet.getNextMarker();
        Set<MutableStorageMetadataImpl> resultSet = originalSet.stream()
                .map(MutableStorageMetadataImpl::new)
                .map(metadata -> {
                    metadata.setName(removePrefix(metadata.getName()));
                    return metadata;
                })
                .collect(Collectors.toSet());

        return new PageSetImpl<>(resultSet, nextMarker);
    }

    private String removePrefix(String path) {
        if (prefix == null) {
            return path;
        }
        return path.substring(prefix.length() + 1);
    }

    @Override
    public void close() {
        storeContext.close();
    }

    private ListContainerOptions buildListContainerOptions(String absoluteFilePath, int maxResults, boolean recursive, String afterMarker) {
        String storageLocation = getStorageLocation(absoluteFilePath);
        ListContainerOptions options = new ListContainerOptions()
                .prefix(storageLocation)
                .maxResults(maxResults);

        if (recursive) {
            options.recursive();
        } else {
            options.delimiter(BlobStorageUtil.PATH_SEPARATOR);
        }

        if (afterMarker != null) {
            options.afterMarker(afterMarker);
        }
        return options;
    }

    private MetadataBase buildFileMetadata(ResourceDescription resource, StorageMetadata metadata) {
        String bucketName = resource.getBucketName();
        ResourceDescription resultResource = getResourceDescription(resource.getType(), bucketName,
                resource.getBucketLocation(), metadata.getName());

        return switch (metadata.getType()) {
            case BLOB -> {
                String blobContentType = ((BlobMetadata) metadata).getContentMetadata().getContentType();
                if (blobContentType != null && blobContentType.equals(DEFAULT_CONTENT_TYPE)) {
                    blobContentType = BlobStorageUtil.getContentType(metadata.getName());
                }

                yield new FileMetadata(resultResource, metadata.getSize(), blobContentType);
            }
            case FOLDER, RELATIVE_PATH -> new ResourceFolderMetadata(resultResource);
            case CONTAINER -> throw new IllegalArgumentException("Can't list container");
        };
    }

    private ResourceDescription getResourceDescription(ResourceType resourceType, String bucketName, String bucketLocation, String absoluteFilePath) {
        // bucketLocation + resourceType + /
        int bucketAndResourceCharsLength = bucketLocation.length() + resourceType.getGroup().length() + 1;
        // bucketAndResourceCharsLength or bucketAndResourceCharsLength + prefix + /
        int charsToSkip = prefix == null ? bucketAndResourceCharsLength : prefix.length() + 1 + bucketAndResourceCharsLength;
        String relativeFilePath = absoluteFilePath.substring(charsToSkip);
        return ResourceDescription.fromDecoded(resourceType, bucketName, bucketLocation, relativeFilePath);
    }

    private static BlobMetadata buildBlobMetadata(String absoluteFilePath, String contentType, String bucketName) {
        ContentMetadata contentMetadata = buildContentMetadata(contentType);
        return new BlobMetadataImpl(null, absoluteFilePath, null, null, null, null, null, Map.of(), null, bucketName, contentMetadata, null, Tier.STANDARD);
    }

    private static ContentMetadata buildContentMetadata(String contentType) {
        ContentMetadata contentMetadata = ContentMetadataBuilder.create()
                .contentType(contentType)
                .build();
        return BaseMutableContentMetadata.fromContentMetadata(contentMetadata);
    }

    private void createBucketIfNeeded(Storage config) {
        if (config.isCreateBucket() && !storeContext.getBlobStore().containerExists(bucketName)) {
            storeContext.getBlobStore().createContainerInLocation(null, bucketName);
        }
    }

    /**
     * Adds a storage prefix if any.
     *
     * @param absoluteFilePath - absolute file path that contains a user bucket location, resource type and relative resource path
     * @return a full storage path
     */
    private String getStorageLocation(String absoluteFilePath) {
        return BlobStorageUtil.toStoragePath(prefix, absoluteFilePath);
    }
}
