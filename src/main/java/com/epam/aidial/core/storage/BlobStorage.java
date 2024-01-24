package com.epam.aidial.core.storage;

import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.FileMetadataBase;
import com.epam.aidial.core.data.FolderMetadata;
import io.vertx.core.buffer.Buffer;
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

@Slf4j
public class BlobStorage implements Closeable {

    // S3 implementation do not return a blob content type without additional head request.
    // To avoid additional request for each blob in the listing we try to recognize blob content type by its extension.
    // Default value is binary/octet-stream, see org.jclouds.s3.domain.ObjectMetadataBuilder
    private static final String DEFAULT_CONTENT_TYPE = ObjectMetadataBuilder.create().build().getContentMetadata().getContentType();

    private final BlobStoreContext storeContext;
    private final BlobStore blobStore;
    private final String bucketName;

    public BlobStorage(Storage config) {
        ContextBuilder builder = ContextBuilder.newBuilder(config.getProvider());
        if (config.getEndpoint() != null) {
            builder.endpoint(config.getEndpoint());
        }
        Properties overrides = config.getOverrides();
        if (overrides != null) {
            builder.overrides(overrides);
        }
        builder.credentials(config.getIdentity(), config.getCredential());
        this.storeContext = builder.buildView(BlobStoreContext.class);
        this.blobStore = storeContext.getBlobStore();
        this.bucketName = config.getBucket();
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
        BlobMetadata metadata = buildBlobMetadata(absoluteFilePath, contentType, bucketName);
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
        Blob blob = blobStore.blobBuilder(absoluteFilePath)
                .payload(new BufferPayload(data))
                .contentLength(data.length())
                .contentType(contentType)
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
        return blobStore.getBlob(bucketName, filePath);
    }

    /**
     * Delete file content from blob store
     *
     * @param filePath absolute file path, for example: Users/user1/files/inputs/data.csv
     */
    public void delete(String filePath) {
        blobStore.removeBlob(bucketName, filePath);
    }

    /**
     * List all files/folder metadata for a given resource
     */
    public FileMetadataBase listMetadata(ResourceDescription resource) {
        ListContainerOptions options = buildListContainerOptions(resource.getAbsoluteFilePath());
        PageSet<? extends StorageMetadata> list = blobStore.list(this.bucketName, options);
        List<FileMetadataBase> filesMetadata = list.stream().map(meta -> buildFileMetadata(resource, meta)).toList();

        // listing folder
        if (resource.isFolder()) {
            return new FolderMetadata(resource, filesMetadata);
        } else {
            // listing file
            if (filesMetadata.size() == 1) {
                return filesMetadata.get(0);
            }
            return null;
        }
    }

    @Override
    public void close() {
        storeContext.close();
    }

    private static ListContainerOptions buildListContainerOptions(String prefix) {
        return new ListContainerOptions()
                .prefix(prefix)
                .delimiter(BlobStorageUtil.PATH_SEPARATOR);
    }

    private static FileMetadataBase buildFileMetadata(ResourceDescription resource, StorageMetadata metadata) {
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
            case FOLDER, RELATIVE_PATH -> new FolderMetadata(resultResource);
            case CONTAINER -> throw new IllegalArgumentException("Can't list container");
        };
    }

    private static ResourceDescription getResourceDescription(ResourceType resourceType, String bucketName, String bucketLocation, String absoluteFilePath) {
        String relativeFilePath = absoluteFilePath.substring(bucketLocation.length() + resourceType.getFolder().length() + 1);
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
}
