package com.epam.aidial.core.storage;

import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.FileMetadataBase;
import com.epam.aidial.core.data.FolderMetadata;
import com.google.common.annotations.VisibleForTesting;
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

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class BlobStorage implements Closeable {

    private final BlobStoreContext storeContext;
    private final BlobStore blobStore;
    private final String bucketName;

    public BlobStorage(Storage config) {
        this(config, null);
    }

    @VisibleForTesting
    public BlobStorage(Storage config, Properties overrides) {
        ContextBuilder builder = ContextBuilder.newBuilder(config.getProvider());
        if (config.getEndpoint() != null) {
            builder.endpoint(config.getEndpoint());
        }
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
     * Initialize s3 multipart upload
     *
     * @param fileName    name of the file, for example: data.csv
     * @param path        absolute path according to the bucket, for example: Users/user1/files/input data
     * @param contentType MIME type of the content, for example: text/csv
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public MultipartUpload initMultipartUpload(String fileName, String path, String contentType) {
        BlobMetadata metadata = buildBlobMetadata(fileName, path, contentType, bucketName);
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
     * @param fileName    name of the file, for example: data.csv
     * @param path        absolute path according to the bucket, for example: Users/user1/files/input data
     * @param contentType MIME type of the content, for example: text/csv
     * @param data        whole content data
     */
    public void store(String fileName, String path, String contentType, Buffer data) {
        String filePath = BlobStorageUtil.buildFilePath(fileName, path);
        Blob blob = blobStore.blobBuilder(filePath)
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
     * List all files/folder metadata for a given path
     *
     * @param path absolute path for a folder, for example: Users/user1/files
     */
    public List<FileMetadataBase> listMetadata(String path) {
        ListContainerOptions options = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(path));
        PageSet<? extends StorageMetadata> list = blobStore.list(bucketName, options);
        return list.stream().map(BlobStorage::buildFileMetadata).toList();
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

    private static FileMetadataBase buildFileMetadata(StorageMetadata metadata) {
        String absoluteFilePath = metadata.getName();
        String[] elements = absoluteFilePath.split(BlobStorageUtil.PATH_SEPARATOR);
        String lastElement = elements[elements.length - 1];
        String path = absoluteFilePath.substring(0, absoluteFilePath.length() - lastElement.length() - 1);

        return switch (metadata.getType()) {
            case BLOB ->
                    new FileMetadata(lastElement, path, metadata.getSize(), BlobStorageUtil.getContentType(lastElement));
            case FOLDER, RELATIVE_PATH ->
                    new FolderMetadata(BlobStorageUtil.removeLeadingAndTrailingPathSeparators(lastElement),
                            BlobStorageUtil.removeTrailingPathSeparator(path));
            case CONTAINER -> throw new IllegalArgumentException("Can't list container");
        };
    }

    private static BlobMetadata buildBlobMetadata(String fileName, String path, String contentType, String bucketName) {
        String filePath = BlobStorageUtil.buildFilePath(fileName, path);
        ContentMetadata contentMetadata = buildContentMetadata(contentType);
        return new BlobMetadataImpl(null, filePath, null, null, null, null, null, Map.of(), null, bucketName, contentMetadata, null, Tier.STANDARD);
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
