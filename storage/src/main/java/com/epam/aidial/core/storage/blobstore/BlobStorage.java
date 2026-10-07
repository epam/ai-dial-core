package com.epam.aidial.core.storage.blobstore;

import com.epam.aidial.core.storage.blobstore.credential.CredentialProvider;
import com.epam.aidial.core.storage.blobstore.credential.CredentialProviderFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import com.google.common.collect.ImmutableSet;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.MutableStorageMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.Tier;
import org.jclouds.blobstore.domain.internal.BlobMetadataImpl;
import org.jclouds.blobstore.domain.internal.MutableBlobMetadataImpl;
import org.jclouds.blobstore.domain.internal.MutableStorageMetadataImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.io.ContentMetadata;
import org.jclouds.io.ContentMetadataBuilder;
import org.jclouds.io.Payload;
import org.jclouds.io.payloads.BaseMutableContentMetadata;
import org.jclouds.io.payloads.ByteArrayPayload;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.s3.domain.ObjectMetadataBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

@Slf4j
public class BlobStorage implements Closeable {

    // S3 implementation do not return a blob content type without additional head request.
    // To avoid additional request for each blob in the listing we try to recognize blob content type by its extension.
    // Default value is binary/octet-stream, see org.jclouds.s3.domain.ObjectMetadataBuilder
    private static final String DEFAULT_CONTENT_TYPE = ObjectMetadataBuilder.create().build().getContentMetadata().getContentType();

    private static final Duration[] OPERATION_LATENCY_BUCKETS = {
        Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5)
    };

    private final BlobStoreContext storeContext;
    private final BlobStore blobStore;
    private final String bucketName;

    // defines a root folder for all resources in bucket
    @Getter
    @Nullable
    private final String prefix;

    @Getter
    private final long maxUploadedFileSize;

    private final BlockingCallTracer tracing;

    public BlobStorage(Storage config) {
        this(config, BlockingCallTracer.NOOP);
    }

    public BlobStorage(Storage config, BlockingCallTracer tracing) {
        this.tracing = tracing;
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
        builder.modules(ImmutableSet.of(new SLF4JLoggingModule()));
        this.storeContext = builder.buildView(BlobStoreContext.class);
        this.blobStore = storeContext.getBlobStore();
        this.bucketName = config.getBucket();
        this.prefix = config.getPrefix();
        this.maxUploadedFileSize = config.getMaxUploadedFileSize();
        createBucketIfNeeded(config);
    }

    /**
     * Initialize multipart upload
     *
     * @param absoluteFilePath absolute path according to the bucket, for example: Users/user1/files/input/file.txt
     * @param contentType      MIME type of the content, for example: text/csv
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public MultipartUpload initMultipartUpload(String absoluteFilePath, String contentType, Map<String, String> userMetadata) {
        String storageLocation = getStorageLocation(absoluteFilePath);
        BlobMetadata metadata = buildBlobMetadata(storageLocation, contentType, bucketName, userMetadata);
        return measure("init_multipart_upload", () -> blobStore.initiateMultipartUpload(bucketName, metadata, PutOptions.NONE));
    }

    /**
     * Upload part/chunk of the file
     *
     * @param multipart MultipartUpload that chunk related to
     * @param part      chunk number, starting from 1
     * @param payload    payload
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public MultipartPart storeMultipartPart(MultipartUpload multipart, int part, Payload payload) {
        return measure("store_multipart_part", () -> blobStore.uploadMultipartPart(multipart, part, payload));
    }

    /**
     * Commit multipart upload.
     * This method must be called after all parts/chunks uploaded
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public String completeMultipartUpload(MultipartUpload multipart, List<MultipartPart> parts) {
        return measure("complete_multipart_upload", () -> blobStore.completeMultipartUpload(multipart, parts));
    }

    /**
     * Abort multipart upload.
     * This method must be called if something was wrong during upload to clean up uploaded parts/chunks
     */
    @SuppressWarnings("UnstableApiUsage") // multipart upload uses beta API
    public void abortMultipartUpload(MultipartUpload multipart) {
        measure("abort_multipart_upload", () -> {
            blobStore.abortMultipartUpload(multipart);
            return null;
        });
    }

    /**
     * Upload file in a single request
     *
     * @param absoluteFilePath absolute path according to the bucket, for example: Users/user1/files/input/file.txt
     * @param contentType      MIME type of the content, for example: text/csv
     * @param contentEncoding  content encoding, e.g. gzip/brotli/deflate
     * @param data             whole content data
     */
    public void store(
            String absoluteFilePath,
            String contentType,
            String contentEncoding,
            Map<String, String> metadata,
            byte[] data) {
        String storageLocation = getStorageLocation(absoluteFilePath);
        Blob blob = blobStore.blobBuilder(storageLocation)
                .payload(new ByteArrayPayload(data))
                .contentLength(data.length)
                .contentType(contentType)
                .contentEncoding(contentEncoding)
                .userMetadata(metadata)
                .build();

        measure("store", () -> {
            BlockingCallTracer.currentSpan().setAttribute("dial.blob.size", data.length);
            return blobStore.putBlob(bucketName, blob);
        });
    }

    /**
     * Load file content from blob store
     *
     * @param filePath absolute file path, for example: Users/user1/files/inputs/data.csv
     * @return Blob instance if file was found, null - otherwise
     */
    public Blob load(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        return measure("load", () -> {
            Blob blob = blobStore.getBlob(bucketName, storageLocation);
            BlockingCallTracer.currentSpan().setAttribute("dial.blob.found", blob != null);
            return blob;
        });
    }

    public boolean exists(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        return measure("exists", () -> blobStore.blobExists(bucketName, storageLocation));
    }

    public BlobMetadata meta(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        return measure("meta", () -> blobStore.blobMetadata(bucketName, storageLocation));
    }

    /**
     * Delete file content from blob store
     *
     * @param filePath absolute file path, for example: Users/user1/files/inputs/data.csv
     */
    public void delete(String filePath) {
        String storageLocation = getStorageLocation(filePath);
        measure("delete", () -> {
            blobStore.removeBlob(bucketName, storageLocation);
            return null;
        });
    }

    public boolean copy(String fromPath, String toPath, Map<String, String> userMetadata) {
        CopyOptions copyOptions;
        if (userMetadata == null) {
            copyOptions = CopyOptions.NONE;
        } else {
            BlobMetadata blobMetadata = meta(fromPath);
            copyOptions = CopyOptions.builder().contentMetadata(blobMetadata.getContentMetadata()).userMetadata(userMetadata).build();
        }
        measure("copy", () -> blobStore.copyBlob(bucketName, getStorageLocation(fromPath), bucketName, getStorageLocation(toPath), copyOptions));
        return true;
    }

    public PageSet<? extends StorageMetadata> list(String absoluteFilePath, String afterMarker, int maxResults, boolean recursive) {
        ListContainerOptions options = buildListContainerOptions(absoluteFilePath, maxResults, recursive, afterMarker);

        PageSet<? extends StorageMetadata> originalSet = measure("list", () -> blobStore.list(bucketName, options));
        if (prefix == null) {
            return originalSet;
        }
        // if prefix defined - subtract it from blob key
        String nextMarker = originalSet.getNextMarker();
        List<MutableStorageMetadata> resultSet = originalSet.stream()
                .map(metadata -> {
                    MutableStorageMetadata mutableMetadata = metadata instanceof BlobMetadata blobMetadata
                            ? new MutableBlobMetadataImpl(blobMetadata)
                            : new MutableStorageMetadataImpl(metadata);
                    mutableMetadata.setName(removePrefix(metadata.getName()));
                    return mutableMetadata;
                })
                .toList();

        return new PageSetImpl<>(resultSet, nextMarker);
    }

    private <T> T measure(String operation, Callable<T> work) {
        Timer.Sample sample = Timer.start();
        String outcome = "error";
        try {
            T result = tracing.trace("blob." + operation, work);
            outcome = "success";
            return result;
        } finally {
            sample.stop(Timer.builder("dial_blob_operation")
                    .description("Latency of blob storage calls")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .serviceLevelObjectives(OPERATION_LATENCY_BUCKETS)
                    .register(Metrics.globalRegistry));
        }
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
            options.delimiter(ResourceDescriptor.PATH_SEPARATOR);
        }

        if (afterMarker != null) {
            options.afterMarker(afterMarker);
        }
        return options;
    }

    public static String resolveContentType(BlobMetadata metadata) {
        String blobContentType = metadata.getContentMetadata().getContentType();
        if (DEFAULT_CONTENT_TYPE.equals(blobContentType)) {
            return BlobStorageUtil.getContentType(metadata.getName());
        }

        return blobContentType;
    }

    private static BlobMetadata buildBlobMetadata(String absoluteFilePath, String contentType,
                                                  String bucketName, Map<String, String> userMetadata) {
        ContentMetadata contentMetadata = buildContentMetadata(contentType);
        return new BlobMetadataImpl(null, absoluteFilePath, null, null, null, null,
                null, userMetadata, null, bucketName, contentMetadata, null, Tier.STANDARD);
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
