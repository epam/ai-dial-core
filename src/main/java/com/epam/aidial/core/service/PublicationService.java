package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ListPublishedResourcesRequest;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.Publication;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.ResourceUrl;
import com.epam.aidial.core.data.Rule;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.security.RuleMatcher;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.UrlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;
import static com.epam.aidial.core.storage.BlobStorageUtil.PUBLIC_BUCKET;
import static com.epam.aidial.core.storage.BlobStorageUtil.PUBLIC_LOCATION;

@RequiredArgsConstructor
public class PublicationService {

    private static final String PUBLICATIONS_NAME = "publications";
    private static final String RULES_NAME = "rules";

    private static final TypeReference<Map<String, Publication>> PUBLICATIONS_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, List<Rule>>> RULES_TYPE = new TypeReference<>() {
    };

    private static final ResourceDescription PUBLIC_PUBLICATIONS = ResourceDescription.fromDecoded(
            ResourceType.PUBLICATION, PUBLIC_BUCKET, PUBLIC_LOCATION, PUBLICATIONS_NAME);

    private static final ResourceDescription PUBLIC_RULES = ResourceDescription.fromDecoded(
            ResourceType.RULES, PUBLIC_BUCKET, PUBLIC_LOCATION, RULES_NAME);

    private static final Set<ResourceType> ALLOWED_RESOURCES = Set.of(ResourceType.FILE, ResourceType.CONVERSATION, ResourceType.PROMPT);

    /**
     * Key is updated time from the metadata. Value is decoded map (folder path, list of rules).
     */
    private final AtomicReference<Pair<Long, Map<String, List<Rule>>>> cachedRules = new AtomicReference<>();
    private final EncryptionService encryption;
    private final ResourceService resources;
    private final BlobStorage files;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    public boolean isReviewResource(ResourceDescription resource) {
        return resource.isPrivate() && resource.getBucketLocation().contains(PUBLICATIONS_NAME);
    }

    public boolean hasReviewAccess(ProxyContext context, ResourceDescription resource) {
        if (isReviewResource(resource)) {
            String location = BlobStorageUtil.buildInitiatorBucket(context);
            String reviewLocation = location + PUBLICATIONS_NAME + PATH_SEPARATOR;
            return resource.getBucketLocation().startsWith(reviewLocation);
        }

        return false;
    }

    public boolean hasPublicAccess(ProxyContext context, ResourceDescription resource) {
        if (!resource.isPublic()) {
            return false;
        }

        Map<String, List<Rule>> rules = getCachedRules();
        Map<String, Boolean> cache = new HashMap<>();

        return evaluate(context, resource, rules, cache);
    }

    public void filterForbidden(ProxyContext context, ResourceDescription folder, ResourceFolderMetadata metadata) {
        if (!folder.isPublic() || !folder.isFolder()) {
            return;
        }

        Map<String, List<Rule>> rules = getCachedRules();
        Map<String, Boolean> cache = new HashMap<>();
        cache.put(ruleUrl(folder), true);

        List<? extends MetadataBase> filtered = metadata.getItems().stream().filter(item -> {
            ResourceDescription resource = ResourceDescription.fromPublicUrl(item.getUrl());
            return evaluate(context, resource, rules, cache);
        }).toList();

        metadata.setItems(filtered);
    }

    public Map<String, List<Rule>> listRules(ResourceDescription resource) {
        if (!resource.isFolder() || !resource.isPublic()) {
            throw new IllegalArgumentException("Bad rule url: " + resource.getUrl());
        }

        Map<String, List<Rule>> rules = getCachedRules();
        Map<String, List<Rule>> result = new TreeMap<>();

        while (resource != null) {
            String url = ruleUrl(resource);;
            List<Rule> list = rules.get(url);
            resource = resource.getParent();

            if (list != null) {
                result.put(url, list);
            }
        }

        return result;
    }

    public Collection<Publication> listPublications(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || !resource.isRootFolder()) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescription key = publications(resource);
        Map<String, Publication> publications = decodePublications(resources.getResource(key));

        for (Publication publication : publications.values()) {
            leaveMetadata(publication);
        }

        return publications.values();
    }

    public Collection<MetadataBase> listPublishedResources(ListPublishedResourcesRequest request, String bucket, String location) {
        ResourceDescription publicationResource = publications(bucket, location);
        Map<String, Publication> publications = decodePublications(resources.getResource(publicationResource));

        // get approved publications only
        List<Publication> approvedPublications = publications.values()
                .stream()
                .filter(publication -> Publication.Status.APPROVED.equals(publication.getStatus()))
                .toList();

        Set<Publication.Resource> resourceSet = approvedPublications.stream()
                .flatMap(publication -> publication.getResources().stream())
                .collect(Collectors.toSet());
        Set<ResourceType> requestedResourceTypes = request.getResourceTypes();

        Set<MetadataBase> metadata = new HashSet<>();
        for (Publication.Resource resource : resourceSet) {
            ResourceDescription resourceDescription = ResourceDescription.fromPrivateUrl(resource.getSourceUrl(), encryption);
            // check if published resource match requested criteria
            if (!requestedResourceTypes.contains(resourceDescription.getType())) {
                continue;
            }

            if (resourceDescription.isFolder()) {
                metadata.add(new ResourceFolderMetadata(resourceDescription));
            } else {
                metadata.add(new ResourceItemMetadata(resourceDescription));
            }
        }

        return metadata;
    }

    public Publication getPublication(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || resource.isPublic() || resource.isFolder() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescription key = publications(resource);
        Map<String, Publication> publications = decodePublications(resources.getResource(key));
        Publication publication = publications.get(resource.getUrl());

        if (publication == null) {
            throw new ResourceNotFoundException("No publication: " + resource.getUrl());
        }

        return publication;
    }

    public Publication createPublication(ProxyContext context, ResourceDescription bucket, Publication publication) {
        if (bucket.getType() != ResourceType.PUBLICATION || bucket.isPublic() || !bucket.isRootFolder()) {
            throw new IllegalArgumentException("Bad publication bucket: " + bucket.getUrl());
        }

        preparePublication(bucket, publication);

        checkSourceResources(publication);
        checkTargetResources(publication);

        // check target location access
        boolean hasAccess = hasPublicAccess(context, ResourceDescription.fromPublicUrl(publication.getResources().get(0).getTargetUrl()));
        if (!hasAccess) {
            throw new PermissionDeniedException("You don't have access to the target folder: " + publication.getTargetUrl());
        }

        copySourceToReviewResources(publication);

        return storePublication(bucket, publication);
    }

    public Publication deletePublication(ProxyContext context, ResourceDescription bucket, Publication publication) {
        if (bucket.getType() != ResourceType.PUBLICATION || bucket.isPublic() || !bucket.isFolder()) {
            throw new IllegalArgumentException("Bad publication url: " + bucket.getUrl());
        }

        preparePublicationForDeletion(bucket, publication);
        checkTargetResourcesExists(publication);
        checkTargetResourceAccess(context, publication);

        return storePublication(bucket, publication);
    }

    @Nullable
    public Publication approvePublication(ResourceDescription resource) {
        Publication publication = getPublication(resource);
        Publication.Status status = publication.getStatus();

        return switch (status) {
            case PENDING -> approvePendingPublication(resource, publication);
            case REQUESTED_FOR_DELETION -> approveDeletionPublication(resource, publication);
            default -> throw new IllegalArgumentException("Bad publication status: " + status);
        };
    }

    @Nullable
    public Publication rejectPublication(ResourceDescription resource) {
        if (resource.isFolder() || resource.isPublic() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        MutableObject<Publication> reference = new MutableObject<>();
        resources.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.get(resource.getUrl());

            if (publication == null) {
                throw new ResourceNotFoundException("No publication: " + resource.getUrl());
            }

            Publication.Status status = publication.getStatus();
            if (status != Publication.Status.PENDING && status != Publication.Status.REQUESTED_FOR_DELETION) {
                throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
            }

            reference.setValue(publication);
            publication.setStatus(Publication.Status.REJECTED);
            return encodePublications(publications);
        });

        resources.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());
            return (publication == null) ? body : encodePublications(publications);
        });

        Publication publication = reference.getValue();
        deleteReviewResources(publication);
        return publication;
    }

    private Publication approvePendingPublication(ResourceDescription resource, Publication publication) {
        if (publication.getStatus() != Publication.Status.PENDING) {
            throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
        }

        checkReviewResources(publication);
        checkTargetResources(publication);

        resources.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication previous = publications.put(resource.getUrl(), publication);

            if (!publication.equals(previous)) {
                throw new ResourceNotFoundException("Publication changed during approving: " + resource.getUrl());
            }

            publication.setStatus(Publication.Status.APPROVED);
            return encodePublications(publications);
        });

        resources.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication removed = publications.remove(resource.getUrl());
            return (removed == null) ? body : encodePublications(publications);
        });

        if (publication.getRules() != null) {
            resources.computeResource(PUBLIC_RULES, body -> {
                Map<String, List<Rule>> rules = decodeRules(body);
                List<Rule> previous = rules.put(publication.getTargetUrl(), publication.getRules());
                return (publication.getRules().equals(previous)) ? body : encodeRules(rules);
            });
        }

        copyReviewToTargetResources(publication);
        deleteReviewResources(publication);

        return publication;
    }

    private Publication approveDeletionPublication(ResourceDescription resource, Publication publication) {
        if (publication.getStatus() != Publication.Status.REQUESTED_FOR_DELETION) {
            throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
        }

        checkTargetResourcesExists(publication);

        resources.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication previous = publications.put(resource.getUrl(), publication);

            if (!publication.equals(previous)) {
                throw new ResourceNotFoundException("Publication changed during approving: " + resource.getUrl());
            }

            publication.setStatus(Publication.Status.DELETED);
            return encodePublications(publications);
        });

        resources.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication removed = publications.remove(resource.getUrl());
            return (removed == null) ? body : encodePublications(publications);
        });

        deletePublicResources(publication);

        return publication;
    }

    private Publication storePublication(ResourceDescription bucket, Publication publication) {
        resources.computeResource(publications(bucket), body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), publication) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        resources.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), newMetadata(publication)) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        return publication;
    }

    private void preparePublication(ResourceDescription bucket, Publication publication) {
        if (publication.getTargetUrl() == null) {
            throw new IllegalArgumentException("Publication \"targetUrl\" is missing");
        }

        if (publication.getResources() == null || publication.getResources().isEmpty()) {
            throw new IllegalArgumentException("Publication \"resources\" is missing");
        }

        ResourceUrl targetFolder = ResourceUrl.parse(publication.getTargetUrl());

        if (!targetFolder.startsWith(PUBLIC_BUCKET) || !targetFolder.isFolder()) {
            throw new IllegalArgumentException("Publication \"targetUrl\" must start with: %s and ends with: %s"
                    .formatted(PUBLIC_BUCKET, PATH_SEPARATOR));
        }

        String id = UrlUtil.encodePath(ids.get());
        String reviewBucket = encodeReviewBucket(bucket, id);

        publication.setUrl(bucket.getUrl() + id);
        publication.setTargetUrl(targetFolder.getUrl());
        publication.setCreatedAt(clock.getAsLong());
        publication.setStatus(Publication.Status.PENDING);

        Set<String> urls = new HashSet<>();

        for (Publication.Resource resource : publication.getResources()) {
            ResourceDescription source = ResourceDescription.fromPrivateUrl(resource.getSourceUrl(), bucket);
            ResourceDescription target = ResourceDescription.fromPublicUrl(resource.getTargetUrl());

            String sourceUrl = source.getUrl();
            String targetUrl = target.getUrl();

            if (source.isFolder()) {
                throw new IllegalArgumentException("Source resource is folder: " + sourceUrl);
            }

            if (target.isFolder()) {
                throw new IllegalArgumentException("Target resource is folder: " + targetUrl);
            }

            if (!ALLOWED_RESOURCES.contains(source.getType())) {
                throw new IllegalArgumentException("Source resource type is not supported: " + sourceUrl);
            }

            if (source.getType() != target.getType()) {
                throw new IllegalArgumentException("Source and target resource types do not match: " + targetUrl);
            }

            String targetSuffix = targetUrl.substring(source.getType().getGroup().length() + 1);

            if (!targetSuffix.startsWith(publication.getTargetUrl())) {
                throw new IllegalArgumentException("Target resource folder does not match with target folder: " + targetUrl);
            } else {
                targetSuffix = targetSuffix.substring(publication.getTargetUrl().length());
            }

            String reviewUrl = source.getType().getGroup() + PATH_SEPARATOR
                    + reviewBucket + PATH_SEPARATOR + targetSuffix;

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
            for (Rule rule : publication.getRules()) {
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

    private void preparePublicationForDeletion(ResourceDescription bucket, Publication publication) {
        if (publication.getResources() == null || publication.getResources().isEmpty()) {
            throw new IllegalArgumentException("Publication \"resources\" is missing");
        }

        String id = UrlUtil.encodePath(ids.get());
        publication.setUrl(bucket.getUrl() + id);
        publication.setCreatedAt(clock.getAsLong());
        publication.setStatus(Publication.Status.REQUESTED_FOR_DELETION);
        // clean up rules
        publication.setRules(null);

        Set<String> urls = new HashSet<>();

        for (Publication.Resource resource : publication.getResources()) {
            ResourceDescription target = ResourceDescription.fromPublicUrl(resource.getTargetUrl());

            String targetUrl = target.getUrl();

            if (target.isFolder()) {
                throw new IllegalArgumentException("Target resource is folder: " + targetUrl);
            }

            if (!urls.add(targetUrl)) {
                throw new IllegalArgumentException("Target resources have duplicate urls: " + targetUrl);
            }

            resource.setTargetUrl(targetUrl);
        }
    }

    private void checkSourceResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getSourceUrl();
            ResourceDescription descriptor = ResourceDescription.fromPrivateUrl(url, encryption);

            if (!checkResource(descriptor)) {
                throw new IllegalArgumentException("Source resource does not exist: " + descriptor.getUrl());
            }
        }
    }

    private void checkReviewResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getReviewUrl();
            if (url == null) {
                continue;
            }
            ResourceDescription descriptor = ResourceDescription.fromPrivateUrl(url, encryption);

            if (!checkResource(descriptor)) {
                throw new IllegalArgumentException("Review resource does not exist: " + descriptor.getUrl());
            }
        }
    }

    private void checkTargetResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getTargetUrl();
            ResourceDescription descriptor = ResourceDescription.fromPublicUrl(url);

            if (checkResource(descriptor)) {
                throw new IllegalArgumentException("Target resource already exists: " + descriptor.getUrl());
            }
        }
    }

    private void checkTargetResourcesExists(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getTargetUrl();
            ResourceDescription descriptor = ResourceDescription.fromPublicUrl(url);

            if (!checkResource(descriptor)) {
                throw new IllegalArgumentException("Target resource does not exist: " + descriptor.getUrl());
            }
        }
    }

    private void checkTargetResourceAccess(ProxyContext context, Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getTargetUrl();
            if (!hasPublicAccess(context, ResourceDescription.fromPublicUrl(url))) {
                throw new PermissionDeniedException("You don't have access to the resource: " + url);
            }
        }
    }

    private void copySourceToReviewResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String sourceUrl = resource.getSourceUrl();
            String reviewUrl = resource.getReviewUrl();

            ResourceDescription from = ResourceDescription.fromPrivateUrl(sourceUrl, encryption);
            ResourceDescription to = ResourceDescription.fromPrivateUrl(reviewUrl, encryption);

            if (!copyResource(from, to)) {
                throw new IllegalStateException("Can't copy source resource from: " + from.getUrl() + " to review: " + to.getUrl());
            }
        }
    }

    private void copyReviewToTargetResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String reviewUrl = resource.getReviewUrl();
            String targetUrl = resource.getTargetUrl();

            ResourceDescription from = ResourceDescription.fromPrivateUrl(reviewUrl, encryption);
            ResourceDescription to = ResourceDescription.fromPublicUrl(targetUrl);

            if (!copyResource(from, to)) {
                throw new IllegalStateException("Can't copy review resource from: " + from.getUrl() + " to target: " + to.getUrl());
            }
        }
    }

    private void deleteReviewResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getReviewUrl();
            if (url == null) {
                continue;
            }
            ResourceDescription descriptor = ResourceDescription.fromPrivateUrl(url, encryption);
            deleteResource(descriptor);
        }
    }

    private void deletePublicResources(Publication publication) {
        for (Publication.Resource resource : publication.getResources()) {
            String url = resource.getTargetUrl();
            ResourceDescription descriptor = ResourceDescription.fromPublicUrl(url);
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

    private Map<String, List<Rule>> getCachedRules() {
        ResourceItemMetadata meta = resources.getResourceMetadata(PUBLIC_RULES);
        long key = (meta == null) ? Long.MIN_VALUE : meta.getUpdatedAt();
        Pair<Long, Map<String, List<Rule>>> current = cachedRules.get();

        if (current == null || current.getKey() != key) {
            Pair<ResourceItemMetadata, String> resource = resources.getResourceWithMetadata(PUBLIC_RULES);
            Pair<Long, Map<String, List<Rule>>> next = (resource == null)
                    ? Pair.of(Long.MIN_VALUE, decodeRules(null))
                    : Pair.of(resource.getKey().getUpdatedAt(), decodeRules(resource.getValue()));

            cachedRules.compareAndSet(current, next);
            current = next;
        }

        return current.getValue();
    }

    private static boolean evaluate(ProxyContext context,
                                    ResourceDescription resource,
                                    Map<String, List<Rule>> rules,
                                    Map<String, Boolean> cache) {

        if (resource != null && !resource.isFolder()) {
            resource = resource.getParent();
        }

        if (resource == null) {
            return true;
        }

        String folderUrl = ruleUrl(resource);
        Boolean evaluated = cache.get(folderUrl);

        if (evaluated != null) {
            return evaluated;
        }

        evaluated = evaluate(context, resource.getParent(), rules, cache);

        if (evaluated) {
            List<Rule> folderRules = rules.get(folderUrl);
            evaluated = folderRules == null || RuleMatcher.match(context, folderRules);
        }

        cache.put(folderUrl, evaluated);
        return evaluated;
    }

    private static String ruleUrl(ResourceDescription resource) {
        String prefix = resource.getType().getGroup();
        return resource.getUrl().substring(prefix.length() + 1);
    }

    /**
     * Leaves only required fields for listing.
     */
    private static void leaveMetadata(Publication publication) {
        publication.setResources(null).setRules(null);
    }

    private static Publication newMetadata(Publication publication) {
        return new Publication()
                .setUrl(publication.getUrl())
                .setTargetUrl(publication.getTargetUrl())
                .setStatus(publication.getStatus())
                .setResourceTypes(publication.getResourceTypes())
                .setCreatedAt(publication.getCreatedAt());
    }

    private static ResourceDescription publications(ResourceDescription resource) {
        return publications(resource.getBucketName(), resource.getBucketLocation());
    }

    private static ResourceDescription publications(String bucket, String location) {
        return ResourceDescription.fromDecoded(ResourceType.PUBLICATION,
                bucket, location, PUBLICATIONS_NAME);
    }

    private static Map<String, Publication> decodePublications(String json) {
        Map<String, Publication> publications = ProxyUtil.convertToObject(json, PUBLICATIONS_TYPE);
        return (publications == null) ? new LinkedHashMap<>() : publications;
    }

    private static String encodePublications(Map<String, Publication> publications) {
        return ProxyUtil.convertToString(publications);
    }

    private static Map<String, List<Rule>> decodeRules(String json) {
        Map<String, List<Rule>> rules = ProxyUtil.convertToObject(json, RULES_TYPE);
        return (rules == null) ? new LinkedHashMap<>() : rules;
    }

    private static String encodeRules(Map<String, List<Rule>> rules) {
        return ProxyUtil.convertToString(rules);
    }
}
