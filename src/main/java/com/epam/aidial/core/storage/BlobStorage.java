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

    private static final String FILE_NAME_METADATA_KEY = "file_name";
    private static final String CONTENT_TYPE_METADATA_KEY = "content_type";

    private final BlobStoreContext storeContext;
    private final BlobStore blobStore;
    private final StorageCache cache;
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
        this.cache = new StorageCache();
        createBucketIfNeeded(config);
    }

    public MultipartUpload initMultipartUpload(String fileId, String path, String resourceName, String contentType) {
        BlobMetadata metadata = buildBlobMetadata(fileId, path, resourceName, contentType, bucketName);
        return blobStore.initiateMultipartUpload(bucketName, metadata, PutOptions.NONE);
    }

    public MultipartPart storeMultipartPart(MultipartUpload multipart, int part, Buffer buffer) {
        return blobStore.uploadMultipartPart(multipart, part, new BufferPayload(buffer));
    }

    public void completeMultipartUpload(FileMetadata metadata, MultipartUpload multipart, List<MultipartPart> parts) {
        String etag = blobStore.completeMultipartUpload(multipart, parts);
        // temp fileId to resource linking
        cache.cache(metadata.getId(), metadata);
        log.info("Stored etag: " + etag);
    }

    public void abortMultipartUpload(MultipartUpload multipart) {
        blobStore.abortMultipartUpload(multipart);
    }

    public void store(FileMetadata metadata, Buffer data) {
        String fileName = metadata.getName();
        String fileId = metadata.getId();
        String contentType = metadata.getContentType();
        String parentPath = metadata.getPath();
        Map<String, String> userMetadata = buildUserMetadata(fileName, contentType);
        String filePath = BlobStorageUtil.buildFilePath(parentPath, fileId);
        Blob blob = blobStore.blobBuilder(filePath)
                .payload(new BufferPayload(data))
                .contentLength(data.length())
                .contentType(contentType)
                .userMetadata(userMetadata)
                .build();

        String etag = blobStore.putBlob(bucketName, blob);
        // temp fileId to resource linking
        cache.cache(fileId, metadata);
        log.info("Stored etag: " + etag);
    }

    public Blob load(String fileId) {
        FileMetadata metadata = cache.load(fileId);
        if (metadata == null) {
            return null;
        }
        String parentPath = metadata.getPath();
        String id = metadata.getId();
        String resourceLocation = BlobStorageUtil.buildFilePath(parentPath, id);
        return blobStore.getBlob(bucketName, resourceLocation);
    }

    public void delete(String fileId) {
        FileMetadata metadata = cache.load(fileId);
        if (metadata == null) {
            throw new IllegalArgumentException("File with ID %s not found".formatted(fileId));
        }
        String parentPath = metadata.getPath();
        String id = metadata.getId();
        String resourceLocation = BlobStorageUtil.buildFilePath(parentPath, id);
        blobStore.removeBlob(bucketName, resourceLocation);
        cache.remove(fileId);
    }

    public List<FileMetadataBase> listMetadata(String path, boolean recursive) {
        List<FileMetadataBase> metadata = new ArrayList<>();
        ListContainerOptions options = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(path), recursive);
        PageSet<? extends StorageMetadata> list = blobStore.list(bucketName, options);
        list.forEach(meta -> {
            StorageType objectType = meta.getType();
            switch (objectType) {
                case BLOB -> metadata.add(buildFileMetadata(meta));
                case FOLDER, RELATIVE_PATH ->
                        metadata.add(new FolderMetadata(BlobStorageUtil.removeLeadingAndTrailingPathSeparators(meta.getName())));
                default -> throw new IllegalArgumentException("Can't list container");
            }
        });

        return metadata;
    }

    private static ListContainerOptions buildListContainerOptions(String prefix, boolean recursive) {
        ListContainerOptions options = new ListContainerOptions()
                .withDetails()
                .delimiter(BlobStorageUtil.PATH_SEPARATOR);
        if (prefix != null) {
            options.prefix(prefix);
        }
        if (recursive) {
            options.recursive();
        }

        return options;
    }

    private static FileMetadata buildFileMetadata(StorageMetadata metadata) {
        Map<String, String> userMetadata = metadata.getUserMetadata();
        String fullFileName = metadata.getName();
        String fileName = userMetadata.get(FILE_NAME_METADATA_KEY);
        String contentType = userMetadata.get(CONTENT_TYPE_METADATA_KEY);
        String[] elements = fullFileName.split(BlobStorageUtil.PATH_SEPARATOR);
        String fileId = elements[elements.length - 1];
        // strip /UUID if needed
        String parentPath = elements.length > 1 ? fullFileName.substring(0, fullFileName.length() - 37) : null;
        return new FileMetadata(fileId, fileName, parentPath, metadata.getSize(), contentType);
    }

    private static Map<String, String> buildUserMetadata(String fileName, String contentType) {
        return Map.of(FILE_NAME_METADATA_KEY, fileName, CONTENT_TYPE_METADATA_KEY, contentType);
    }

    public void close() {
        storeContext.close();
    }

    private static BlobMetadata buildBlobMetadata(String fileId, String path, String resourceName, String contentType, String bucketName) {
        Map<String, String> userMetadata = buildUserMetadata(resourceName, contentType);
        String filePath = BlobStorageUtil.buildFilePath(path, fileId);
        ContentMetadata contentMetadata = buildContentMetadata(contentType);
        return new BlobMetadataImpl(fileId, filePath, null, null, null, null, null, userMetadata, null, bucketName, contentMetadata, null, Tier.STANDARD);
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
