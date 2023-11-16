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
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.Tier;
import org.jclouds.blobstore.domain.internal.BlobMetadataImpl;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.io.ContentMetadata;
import org.jclouds.io.ContentMetadataBuilder;
import org.jclouds.io.payloads.BaseMutableContentMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class BlobStorage {

    private final BlobStoreContext storeContext;
    private final BlobStore blobStore;
    private final String bucketName;

    public BlobStorage(Storage config) {
        ContextBuilder builder = ContextBuilder.newBuilder(config.getProvider());
        if (config.getEndpoint() != null) {
            builder.endpoint(config.getEndpoint());
        }
        builder.credentials(config.getIdentity(), config.getCredential());
        this.storeContext = builder.buildView(BlobStoreContext.class);
        this.blobStore = storeContext.getBlobStore();
        this.bucketName = config.getBucket();
        createBucketIfNeeded(config);
    }

    public MultipartUpload initMultipartUpload(String fileName, String path, String contentType) {
        BlobMetadata metadata = buildBlobMetadata(fileName, path, contentType, bucketName);
        return blobStore.initiateMultipartUpload(bucketName, metadata, PutOptions.NONE);
    }

    public MultipartPart storeMultipartPart(MultipartUpload multipart, int part, Buffer buffer) {
        return blobStore.uploadMultipartPart(multipart, part, new BufferPayload(buffer));
    }

    public void completeMultipartUpload(MultipartUpload multipart, List<MultipartPart> parts) {
        String etag = blobStore.completeMultipartUpload(multipart, parts);
        log.info("Stored etag: " + etag);
    }

    public void abortMultipartUpload(MultipartUpload multipart) {
        blobStore.abortMultipartUpload(multipart);
    }

    public void store(FileMetadata metadata, Buffer data) {
        String fileName = metadata.getName();
        String contentType = metadata.getContentType();
        String path = metadata.getPath();
        String filePath = BlobStorageUtil.buildFilePath(fileName, path);
        Blob blob = blobStore.blobBuilder(filePath)
                .payload(new BufferPayload(data))
                .contentLength(data.length())
                .contentType(contentType)
                .build();

        String etag = blobStore.putBlob(bucketName, blob);
        log.info("Stored etag: " + etag);
    }

    public Blob load(String absoluteFilePath) {
        return blobStore.getBlob(bucketName, absoluteFilePath);
    }

    public void delete(String absoluteFilePath) {
        blobStore.removeBlob(bucketName, absoluteFilePath);
    }

    public List<FileMetadataBase> listMetadata(String path) {
        List<FileMetadataBase> metadata = new ArrayList<>();
        ListContainerOptions options = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(path));
        PageSet<? extends StorageMetadata> list = blobStore.list(bucketName, options);
        list.forEach(meta -> {
            StorageType objectType = meta.getType();
            switch (objectType) {
                case BLOB -> metadata.add(buildFileMetadata(meta));
                case FOLDER, RELATIVE_PATH -> metadata.add(buildFolderMetadata(meta));
                default -> throw new IllegalArgumentException("Can't list container");
            }
        });

        return metadata;
    }

    public void close() {
        storeContext.close();
    }

    private static ListContainerOptions buildListContainerOptions(String prefix) {
        return new ListContainerOptions()
                .prefix(prefix)
                .delimiter(BlobStorageUtil.PATH_SEPARATOR);
    }

    private static FileMetadata buildFileMetadata(StorageMetadata metadata) {
        String absoluteFilePath = metadata.getName();
        String[] elements = absoluteFilePath.split(BlobStorageUtil.PATH_SEPARATOR);
        String fileName = elements[elements.length - 1];
        String path = absoluteFilePath.substring(0, absoluteFilePath.length() - fileName.length() - 1);
        return new FileMetadata(fileName, path, metadata.getSize(), BlobStorageUtil.getContentType(fileName));
    }

    private static FolderMetadata buildFolderMetadata(StorageMetadata metadata) {
        String absoluteFolderPath = metadata.getName();
        String[] elements = absoluteFolderPath.split(BlobStorageUtil.PATH_SEPARATOR);
        String lastFolderName = elements[elements.length - 1];
        String path = absoluteFolderPath.substring(0, absoluteFolderPath.length() - lastFolderName.length() - 1);
        return new FolderMetadata(BlobStorageUtil.removeLeadingAndTrailingPathSeparators(lastFolderName), path);
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
        if (config.getProvider().equals("google-cloud-storage")) {
            // GCP service account do not have permissions to get bucket :(
            return;
        }
        if (!storeContext.getBlobStore().containerExists(bucketName)) {
            storeContext.getBlobStore().createContainerInLocation(null, bucketName);
        }
    }
}
