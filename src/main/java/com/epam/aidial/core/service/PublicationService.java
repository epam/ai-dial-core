package com.epam.aidial.core.service;

import com.epam.aidial.core.data.Publication;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.ResourceUrl;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.UrlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.mutable.MutableObject;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;
import static com.epam.aidial.core.storage.ResourceDescription.PUBLIC_BUCKET;

@RequiredArgsConstructor
public class PublicationService {

    private static final String PUBLICATIONS_NAME = "publications";
    private static final TypeReference<LinkedHashMap<String, Publication>> PUBLICATIONS_TYPE = new TypeReference<>() {
    };

    private static final ResourceDescription PUBLIC_PUBLICATIONS = ResourceDescription.fromDecoded(
            ResourceType.PUBLICATION, PUBLIC_BUCKET, ResourceDescription.PUBLIC_LOCATION,
            PUBLICATIONS_NAME);


    private final EncryptionService encryption;
    private final ResourceService resources;
    private final BlobStorage files;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    public boolean hasReviewAccess(ResourceDescription resource, String userBucket, String userLocation) {
        String reviewLocation = userLocation + PUBLICATIONS_NAME + PATH_SEPARATOR;
        return resource.getBucketLocation().startsWith(reviewLocation);
    }

    public Collection<Publication> listPublications(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || !resource.isRootFolder()) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescription key = localPublications(resource);
        Map<String, Publication> publications = decodePublications(resources.getResource(key));

        for (Publication publication : publications.values()) {
            leaveDescription(publication);
        }

        return publications.values();
    }

    @Nullable
    public Publication getPublication(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || resource.isFolder() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescription key = localPublications(resource);
        Map<String, Publication> publications = decodePublications(resources.getResource(key));

        return publications.get(resource.getUrl());
    }

    public Publication createPublication(ResourceDescription bucket, Publication publication) {
        if (bucket.getType() != ResourceType.PUBLICATION || !bucket.isRootFolder()) {
            throw new IllegalArgumentException("Bad publication bucket: " + bucket.getUrl());
        }

        preparePublication(bucket, publication);

        checkSourceResources(publication);
        checkTargetResources(publication);

        copySourceToReviewResources(publication);

        resources.computeResource(localPublications(bucket), body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), publication) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        leaveDescription(publication);
        resources.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), publication) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        return publication;
    }

    public boolean deletePublication(ResourceDescription resource) {
        if (resource.isFolder() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        MutableObject<Publication> deleted = new MutableObject<>();

        resources.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());
            return (publication == null) ? body : encodePublications(publications);
        });

        resources.computeResource(localPublications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());

            if (publication == null) {
                return body;
            }

            deleted.setValue(publication);
            return encodePublications(publications);
        });

        Publication publication = deleted.getValue();

        if (publication == null) {
            return false;
        }

        if (publication.getStatus() == Publication.Status.PENDING) {
            deleteReviewResources(publication);
        }

        return true;
    }

    private void preparePublication(ResourceDescription bucket, Publication publication) {
        if (publication.getSourceUrl() == null) {
            throw new IllegalArgumentException("Publication \"sourceUrl\" is missing");
        }

        if (publication.getTargetUrl() == null) {
            throw new IllegalArgumentException("Publication \"targetUrl\" is missing");
        }

        if (publication.getResources() == null) {
            publication.setResources(List.of());
        }

        if (publication.getResources().isEmpty() && publication.getRules() == null) {
            throw new IllegalArgumentException("No resources and no rules in publication");
        }

        ResourceUrl sourceFolder = ResourceUrl.parse(publication.getSourceUrl());
        ResourceUrl targetFolder = ResourceUrl.parse(publication.getTargetUrl());

        if (!sourceFolder.startsWith(bucket.getBucketName()) || !sourceFolder.isFolder()) {
            throw new IllegalArgumentException("Publication \"sourceUrl\" must start with: %s and ends with: %s"
                    .formatted(bucket.getBucketName(), PATH_SEPARATOR));
        }

        if (!targetFolder.startsWith(PUBLIC_BUCKET) || !targetFolder.isFolder()) {
            throw new IllegalArgumentException("Publication \"targetUrl\" must start with: %s and ends with: %s"
                    .formatted(PUBLIC_BUCKET, PATH_SEPARATOR));
        }

        String id = UrlUtil.encodePath(ids.get());
        String reviewBucket = encodeReviewBucket(bucket, id);

        publication.setUrl(bucket.getUrl() + id);
        publication.setSourceUrl(sourceFolder.getUrl());
        publication.setTargetUrl(targetFolder.getUrl());
        publication.setCreatedAt(clock.getAsLong());
        publication.setStatus(Publication.Status.PENDING);

        Set<String> urls = new HashSet<>();

        for (Publication.Resource resource : publication.getResources()) {
            ResourceDescription source = ResourceDescription.fromBucketLink(resource.getSourceUrl(), bucket);
            String sourceUrl = source.getUrl();

            if (source.isFolder()) {
                throw new IllegalArgumentException("Resource folder is not allowed: " + sourceUrl);
            }

            if (source.getType() != ResourceType.FILE && source.getType() != ResourceType.PROMPT && source.getType() != ResourceType.CONVERSATION) {
                throw new IllegalArgumentException("Resource type is not supported: " + sourceUrl);
            }

            String sourceSuffix = sourceUrl.substring(source.getType().getGroup().length() + 1);

            if (!sourceSuffix.startsWith(publication.getSourceUrl())) {
                throw new IllegalArgumentException("Resource folder does not match with source folder: " + publication.getSourceUrl());
            }

            String version = resource.getVersion();

            if (version == null) {
                throw new IllegalArgumentException("Resource version is missing: " + sourceUrl);
            }

            if (!version.equals(UrlUtil.decodePath(version))) {
                throw new IllegalArgumentException("Resource version contains not allowed characters: " + version);
            }

            sourceSuffix = sourceSuffix.substring(publication.getSourceUrl().length());

            String targetUrl = source.getType().getGroup() + PATH_SEPARATOR
                    + publication.getTargetUrl() + sourceSuffix + "." + version;

            String reviewUrl = source.getType().getGroup() + PATH_SEPARATOR
                    + reviewBucket + PATH_SEPARATOR + sourceSuffix;

            if (!urls.add(sourceUrl)) {
                throw new IllegalArgumentException("Source resources have duplicate urls: " + sourceUrl);
            }

            if (!urls.add(targetUrl)) {
                throw new IllegalArgumentException("Target resources have duplicate urls: " + targetUrl);
            }

            if (!urls.add(reviewUrl)) {
                throw new IllegalArgumentException("Review resources have duplicate urls: " + reviewUrl);
            }

            resource.setSourceUrl(sourceUrl);
            resource.setTargetUrl(targetUrl);
            resource.setReviewUrl(reviewUrl);
        }

        if (publication.getRules() != null) {
            for (Publication.Rule rule : publication.getRules()) {
                if (rule.getSource() == null) {
                    throw new IllegalArgumentException("Rule does not have source");
                }

                if (rule.getTargets() == null || rule.getTargets().isEmpty()) {
                    throw new IllegalArgumentException("Rule does not have targets");
                }

                if (rule.getFunction() == null) {
                    throw new IllegalArgumentException("Rule does not have function");
                }
            }
        }
    }

    private void checkSourceResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getSourceUrl();
            ResourceDescription descriptor = ResourceDescription.fromLink(url, encryption);

            if (!checkResource(descriptor)) {
                throw new IllegalArgumentException("Source resource does not exist: " + descriptor.getUrl());
            }
        }
    }

    private void checkTargetResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getTargetUrl();
            ResourceDescription descriptor = ResourceDescription.fromPublicLink(url);

            if (checkResource(descriptor)) {
                throw new IllegalArgumentException("Target resource already exists: " + descriptor.getUrl());
            }
        }
    }

    private void copySourceToReviewResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String sourceUrl = resource.getSourceUrl();
            String reviewUrl = resource.getReviewUrl();

            ResourceDescription from = ResourceDescription.fromLink(sourceUrl, encryption);
            ResourceDescription to = ResourceDescription.fromLink(reviewUrl, encryption);

            if (!copyResource(from, to)) {
                throw new IllegalStateException("Can't copy source resource from: " + from.getUrl() + " to review: " + to.getUrl());
            }
        }
    }

    private void deleteReviewResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getReviewUrl();
            ResourceDescription descriptor = ResourceDescription.fromLink(url, encryption);
            deleteResource(descriptor);
        }
    }

    private boolean checkResource(ResourceDescription descriptor) {
        return switch (descriptor.getType()) {
            case FILE -> files.exists(descriptor.getAbsoluteFilePath());
            case PROMPT, CONVERSATION -> resources.hasResource(descriptor);
            default -> throw new IllegalStateException("Unsupported type: " + descriptor.getType());
        };
    }

    private boolean copyResource(ResourceDescription from, ResourceDescription to) {
        return switch (from.getType()) {
            case FILE -> files.copy(from.getAbsoluteFilePath(), to.getAbsoluteFilePath());
            case PROMPT, CONVERSATION -> resources.copyResource(from, to);
            default -> throw new IllegalStateException("Unsupported type: " + from.getType());
        };
    }

    private void deleteResource(ResourceDescription descriptor) {
        switch (descriptor.getType()) {
            case FILE -> files.delete(descriptor.getAbsoluteFilePath());
            case PROMPT, CONVERSATION -> resources.deleteResource(descriptor);
            default -> throw new IllegalStateException("Unsupported type: " + descriptor.getType());
        }
    }

    private String encodeReviewBucket(ResourceDescription bucket, String id) {
        String path = bucket.getBucketLocation()
                + PUBLICATIONS_NAME + PATH_SEPARATOR
                + id + PATH_SEPARATOR;

        return encryption.encrypt(path);
    }

    private static void leaveDescription(Publication publication) {
        publication.setResources(null).setResources(null);
    }

    private static ResourceDescription localPublications(ResourceDescription resource) {
        return ResourceDescription.fromDecoded(ResourceType.PUBLICATION,
                resource.getBucketName(),
                resource.getBucketLocation(),
                PUBLICATIONS_NAME);
    }

    private static Map<String, Publication> decodePublications(String resources) {
        Map<String, Publication> publications = ProxyUtil.convertToObject(resources, PUBLICATIONS_TYPE);
        return (publications == null) ? new LinkedHashMap<>() : publications;
    }

    private static String encodePublications(Map<String, Publication> publications) {
        return ProxyUtil.convertToString(publications);
    }
}
