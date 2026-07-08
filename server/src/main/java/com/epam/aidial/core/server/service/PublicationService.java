package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListPublishedResourcesRequest;
import com.epam.aidial.core.server.data.Notification;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.data.RejectPublicationRequest;
import com.epam.aidial.core.server.data.ResourceUrl;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.data.UserMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Slf4j
@RequiredArgsConstructor
public class PublicationService {

    private static final String PUBLICATIONS_NAME = "publications";

    private static final TypeReference<Map<String, Publication>> PUBLICATIONS_TYPE = new TypeReference<>() {
    };

    private static final ResourceDescriptor PUBLIC_PUBLICATIONS = ResourceDescriptorFactory.fromDecoded(
            ResourceTypes.PUBLICATION, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, PUBLICATIONS_NAME);

    private static final Set<ResourceType> ALLOWED_RESOURCES = Set.of(ResourceTypes.FILE, ResourceTypes.CONVERSATION,
            ResourceTypes.PROMPT, ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET);

    private final EncryptionService encryption;
    private final ResourceService resourceService;
    private final AccessService accessService;
    private final RuleService ruleService;
    private final NotificationService notificationService;
    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;
    private final ResourceOperationService resourceOperationService;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    public static boolean isReviewBucket(ResourceDescriptor resource) {
        return resource.isPrivate() && resource.getBucketLocation().contains(PUBLICATIONS_NAME);
    }

    public static boolean hasReviewAccess(ProxyContext context, ResourceDescriptor resource) {
        if (isReviewBucket(resource)) {
            String location = BucketBuilder.buildInitiatorBucket(context);
            String reviewLocation = location + PUBLICATIONS_NAME + ResourceDescriptor.PATH_SEPARATOR;
            return resource.getBucketLocation().startsWith(reviewLocation);
        }

        return false;
    }

    public Collection<Publication> listPublications(ResourceDescriptor resource) {
        if (resource.getType() != ResourceTypes.PUBLICATION || !resource.isRootFolder()) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescriptor key = publications(resource);
        Map<String, Publication> publications = decodePublications(resourceService.getResource(key));

        for (Publication publication : publications.values()) {
            leaveMetadata(publication);
        }

        return publications.values();
    }

    public Collection<MetadataBase> listPublishedResources(ListPublishedResourcesRequest request, String bucket, String location) {
        ResourceDescriptor publicationResource = publications(bucket, location);
        Map<String, Publication> publications = decodePublications(resourceService.getResource(publicationResource));

        // get approved publications only
        List<Publication> approvedPublications = publications.values()
                .stream()
                .filter(publication -> Publication.Status.APPROVED.equals(publication.getStatus()))
                .toList();

        Set<Publication.Resource> resourceSet = approvedPublications.stream()
                .flatMap(publication -> publication.getResources().stream())
                .collect(Collectors.toSet());
        Set<? extends ResourceType> requestedResourceTypes = request.getResourceTypes();

        Set<MetadataBase> metadata = new HashSet<>();
        for (Publication.Resource resource : resourceSet) {
            ResourceDescriptor resourceDescription = ResourceDescriptorFactory.fromPrivateUrl(resource.getSourceUrl(), encryption);
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

    public Publication getPublication(ResourceDescriptor resource) {
        validatePublicationResourceDescriptor(resource);

        ResourceDescriptor key = publications(resource);
        Map<String, Publication> publications = decodePublications(resourceService.getResource(key));
        Publication publication = publications.get(resource.getUrl());

        if (publication == null) {
            throw new ResourceNotFoundException("No publication: " + resource.getUrl());
        }

        return publication;
    }

    private static void validatePublicationResourceDescriptor(ResourceDescriptor resource) {
        if (resource.getType() != ResourceTypes.PUBLICATION || resource.isPublic() || resource.isFolder() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }
    }

    public Publication createPublication(ProxyContext context, Publication publication) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryption.encrypt(bucketLocation);
        boolean isAdmin = accessService.hasAdminAccess(context);

        prepareAndValidatePublicationRequest(context, publication, bucket, bucketLocation, isAdmin);

        List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                .filter(resource -> resource.getAction() == Publication.ResourceAction.ADD || resource.getAction() == Publication.ResourceAction.ADD_IF_ABSENT)
                .toList();

        copySourceToReviewResources(context, resourcesToAdd);

        resourceService.computeResource(publications(bucket, bucketLocation), body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), publication) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), newMetadata(publication)) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        return publication;
    }

    public Publication deletePublication(ProxyContext context, ResourceDescriptor resource) {
        validatePublicationResourceDescriptor(resource);

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());
            return (publication == null) ? body : encodePublications(publications);
        });

        MutableObject<Publication> reference = new MutableObject<>();
        resourceService.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());

            if (publication == null) {
                throw new ResourceNotFoundException("No publication: " + resource.getUrl());
            }

            reference.setValue(publication);
            return encodePublications(publications);
        });

        Publication publication = reference.get();

        if (publication.getStatus() == Publication.Status.PENDING) {
            List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                    .filter(i -> i.getAction() == Publication.ResourceAction.ADD || i.getAction() == Publication.ResourceAction.ADD_IF_ABSENT)
                    .toList();
            deleteReviewResources(context, resourcesToAdd);
        }

        return publication;
    }

    public Publication updatePublication(ProxyContext context, Publication publication) {
        validatePublicationRequest(publication);

        if (publication.getUrl() == null) {
            throw new IllegalArgumentException("Publication url is required");
        }
        ResourceDescriptor publicationResource = ResourceDescriptorFactory.fromPrivateUrl(publication.getUrl(), encryption);

        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryption.encrypt(bucketLocation);

        String reviewBucket = getReviewBucket(publicationResource);

        validatePublicationResources(context, publication, bucket, reviewBucket, true);

        validatePublicationResourceDescriptor(publicationResource);

        List<Publication.Resource> reviewResourcesToAdd = new ArrayList<>();
        List<Publication.Resource> reviewResourcesToDelete = new ArrayList<>();
        List<Pair<String, String>> reviewResourcesToMove = new ArrayList<>();
        Map<String, String> replacementLinks = new HashMap<>();

        ResourceDescriptor publicationsFile = publications(publicationResource);

        try (var ignore = resourceService.lockResource(publicationsFile)) {
            String body = resourceService.getResource(publicationsFile, EtagHeader.ANY, false);
            Map<String, Publication> publications = decodePublications(body);
            Publication existingPublication = publications.get(publicationResource.getUrl());

            if (existingPublication == null) {
                throw new ResourceNotFoundException("No publication: " + publicationResource.getUrl());
            }

            if (existingPublication.getStatus() != Publication.Status.PENDING) {
                throw new IllegalStateException("Can only update PENDING publications");
            }
            Map<String, Publication.Resource> sourceUrlToResource = new HashMap<>();
            for (Publication.Resource resource : existingPublication.getResources()) {
                sourceUrlToResource.put(resource.getSourceUrl(), resource);
            }
            Set<String> newSourceUrls = new HashSet<>();
            for (Publication.Resource resource : publication.getResources()) {
                Publication.Resource existingResource = sourceUrlToResource.get(resource.getSourceUrl());
                if (resource.getAction() == Publication.ResourceAction.ADD || resource.getAction() == Publication.ResourceAction.ADD_IF_ABSENT) {
                    if (existingResource == null) {
                        ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(resource.getSourceUrl(), encryption);
                        if (!resourceService.hasResource(from)) {
                            throw new IllegalArgumentException("Source resource does not exist: " + resource.getSourceUrl());
                        }
                        reviewResourcesToAdd.add(resource);
                        ResourceDescriptor to = ResourceDescriptorFactory.fromPrivateUrl(resource.getReviewUrl(), encryption);
                        replacementLinks.put(from.getDecodedUrl(), to.getUrl());
                    } else if (!resource.getReviewUrl().equals(existingResource.getReviewUrl())) {
                        reviewResourcesToMove.add(Pair.of(existingResource.getReviewUrl(), resource.getReviewUrl()));
                        ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(existingResource.getReviewUrl(), encryption);

                        if (from.getType() == ResourceTypes.FILE) {
                            ResourceDescriptor to = ResourceDescriptorFactory.fromPrivateUrl(resource.getReviewUrl(), encryption);
                            replacementLinks.put(from.getDecodedUrl(), to.getUrl());
                        }
                    }
                }
                newSourceUrls.add(resource.getSourceUrl());
            }
            for (Publication.Resource resource : existingPublication.getResources()) {
                if (!newSourceUrls.contains(resource.getSourceUrl())
                        && (resource.getAction() == Publication.ResourceAction.ADD || resource.getAction() == Publication.ResourceAction.ADD_IF_ABSENT)) {
                    reviewResourcesToDelete.add(resource);
                }
            }

            // move renamed target resources in the review bucket
            for (Pair<String, String> pair : reviewResourcesToMove) {
                ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(pair.getLeft(), encryption);
                ResourceDescriptor to = ResourceDescriptorFactory.fromPrivateUrl(pair.getRight(), encryption);
                resourceOperationService.moveResource(context, from, to, false);
            }

            // delete removed resources from the review bucket
            for (Publication.Resource reviewResource : reviewResourcesToDelete) {
                ResourceDescriptor resource = ResourceDescriptorFactory.fromPrivateUrl(reviewResource.getReviewUrl(), encryption);
                if (resource.getType() == ResourceTypes.TOOL_SET) {
                    toolSetService.deleteToolset(context, resource, EtagHeader.ANY);
                } else {
                    resourceService.deleteResource(resource, EtagHeader.ANY);
                }
            }

            // copy new resources to the review bucket
            copySourceToReviewResources(context, reviewResourcesToAdd);

            // replace internal links in the resources
            for (Publication.Resource resource : publication.getResources()) {
                if (resource.getAction() == Publication.ResourceAction.ADD || resource.getAction() == Publication.ResourceAction.ADD_IF_ABSENT) {
                    ResourceDescriptor to = ResourceDescriptorFactory.fromPrivateUrl(resource.getReviewUrl(), encryption);
                    if (to.getType() == ResourceTypes.CONVERSATION) {
                        resourceService.computeResource(to, conversationBody -> PublicationUtil.replaceConversationLinks(conversationBody, to, replacementLinks));
                    }
                    if (to.getType() == ResourceTypes.APPLICATION) {
                        // Decrypt external-service secrets on read so the re-put below re-encrypts plaintext
                        // once; getApplication() returns them still encrypted, which would double-encrypt.
                        Application app = applicationService.getApplicationWithDecryptedSecrets(to).getValue();
                        app.setIconUrl(replaceLink(replacementLinks, app.getIconUrl()));
                        // Publication-approval is conservative: continue stripping forwardAuthToken.
                        // The originating user-bucket write already stripped it; this is defense in depth.
                        applicationService.putApplication(to, EtagHeader.ANY, null, app, false);
                    }
                }
            }

            // update user publications
            existingPublication.setRules(publication.getRules());
            existingPublication.setTargetFolder(publication.getTargetFolder());
            existingPublication.setResources(publication.getResources());
            existingPublication.setDisplayAuthor(publication.getDisplayAuthor());
            existingPublication.setResourceTypes(publication.getResourceTypes());
            resourceService.putResource(publicationsFile, encodePublications(publications), EtagHeader.ANY, null, false);

            // update public publications to be viewed by admin
            updatePublicPublications(existingPublication);

            return existingPublication;
        }
    }

    private void updatePublicPublications(Publication publication) {
        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            publications.computeIfPresent(publication.getUrl(), (k, v) -> newMetadata(publication));
            return encodePublications(publications);
        });
    }

    private String getReviewBucket(ResourceDescriptor publicationResource) {
        String bucketLocation = publicationResource.getBucketLocation();
        String publicationId = publicationResource.getName();
        return encodeReviewBucket(bucketLocation, publicationId);
    }

    @Nullable
    public Publication approvePublication(ProxyContext context, ResourceDescriptor resource) {
        Publication publication = getPublication(resource);
        if (publication.getStatus() != Publication.Status.PENDING) {
            throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
        }

        List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                .filter(i -> i.getAction() == Publication.ResourceAction.ADD || i.getAction() == Publication.ResourceAction.ADD_IF_ABSENT)
                .toList();

        List<Publication.Resource> resourcesToDelete = publication.getResources().stream()
                .filter(i -> i.getAction() == Publication.ResourceAction.DELETE)
                .toList();

        checkReviewResources(resourcesToAdd);
        checkTargetResources(resourcesToAdd, false);
        checkTargetResources(resourcesToDelete, true);

        resourceService.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication previous = publications.put(resource.getUrl(), publication);

            if (!publication.equals(previous)) {
                throw new ResourceNotFoundException("Publication changed during approving: " + resource.getUrl());
            }

            publication.setStatus(Publication.Status.APPROVED);
            return encodePublications(publications);
        });

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication removed = publications.remove(resource.getUrl());
            return (removed == null) ? body : encodePublications(publications);
        });

        ruleService.storeRules(publication);

        copyReviewToTargetResources(context, publication, resourcesToAdd);
        deleteReviewResources(context, resourcesToAdd);
        deletePublicResources(context, resourcesToDelete);

        String notificationMessage = "Your request has been approved by admin";
        Notification notification = Notification.getPublicationNotification(resource.getUrl(), notificationMessage);
        notificationService.createNotification(resource.getBucketName(), resource.getBucketLocation(), notification);

        return publication;
    }

    @Nullable
    public Publication rejectPublication(ProxyContext context, ResourceDescriptor resource, RejectPublicationRequest request) {
        validatePublicationResourceDescriptor(resource);

        MutableObject<Publication> reference = new MutableObject<>();
        resourceService.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.get(resource.getUrl());

            if (publication == null) {
                throw new ResourceNotFoundException("No publication: " + resource.getUrl());
            }

            if (publication.getStatus() != Publication.Status.PENDING) {
                throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
            }

            reference.setValue(publication);
            publication.setStatus(Publication.Status.REJECTED);
            return encodePublications(publications);
        });

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());
            return (publication == null) ? body : encodePublications(publications);
        });

        Publication publication = reference.get();
        List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                .filter(i -> i.getAction() == Publication.ResourceAction.ADD || i.getAction() == Publication.ResourceAction.ADD_IF_ABSENT)
                .toList();
        deleteReviewResources(context, resourcesToAdd);

        String rejectReason = request.comment();
        String notificationMessage = "Your request has been rejected by admin";
        notificationMessage = rejectReason != null ? notificationMessage + ": " + rejectReason : notificationMessage;
        Notification notification = Notification.getPublicationNotification(resource.getUrl(), notificationMessage);
        notificationService.createNotification(resource.getBucketName(), resource.getBucketLocation(), notification);

        return publication;
    }

    private void prepareAndValidatePublicationRequest(ProxyContext context, Publication publication,
                                                      String bucketName, String bucketLocation,
                                                      boolean isAdmin) {
        validatePublicationRequest(publication);
        String id = UrlUtil.encodePathSegment(ids.get());
        String publicationUrl = String.join(ResourceDescriptor.PATH_SEPARATOR, "publications", bucketName, id);
        String reviewBucket = encodeReviewBucket(bucketLocation, id);

        validatePublicationResources(context, publication, bucketName, reviewBucket, isAdmin);

        publication.setUrl(publicationUrl);
        publication.setCreatedAt(clock.getAsLong());
        publication.setStatus(Publication.Status.PENDING);
        publication.setAuthor(context.getUserDisplayName());
    }

    private void validatePublicationResources(ProxyContext context, Publication publication, String bucketName, String reviewBucket, boolean isAdmin) {
        Set<String> urls = new HashSet<>();
        String targetFolder = publication.getTargetFolder();
        boolean isPublicationNew = publication.getUrl() == null;
        for (Publication.Resource resource : publication.getResources()) {
            Publication.ResourceAction action = resource.getAction();
            if (action == null) {
                throw new IllegalArgumentException("Resource \"action\" is missing");
            }

            if (action == Publication.ResourceAction.ADD || action == Publication.ResourceAction.ADD_IF_ABSENT) {
                validateResourceForAddition(context, resource, targetFolder, reviewBucket, urls, isPublicationNew);
            } else if (action == Publication.ResourceAction.DELETE) {
                validateResourceForDeletion(resource, targetFolder, urls, bucketName, isAdmin);
            } else {
                throw new UnsupportedOperationException("Unsupported resource action: " + action);
            }
        }

        Set<ResourceDescriptor> targetResources = publication.getResources().stream()
                .map(resource -> ResourceDescriptorFactory.fromPublicUrl(resource.getTargetUrl()))
                .collect(Collectors.toUnmodifiableSet());

        // validate if user has access to all target resources
        boolean hasPublicAccess = accessService.hasPublicAccess(targetResources, context);
        if (!hasPublicAccess) {
            throw new PermissionDeniedException("User don't have permissions to the provided target resources");
        }
    }

    private void validatePublicationRequest(Publication publication) {
        String targetFolder = publication.getTargetFolder();
        if (targetFolder == null) {
            throw new IllegalArgumentException("Publication \"targetFolder\" is missing");
        }

        // rules to the root publication folder are not allowed
        if (targetFolder.equals("public/") && publication.getRules() != null && !publication.getRules().isEmpty()) {
            throw new IllegalArgumentException("Rules are not allowed for root targetFolder");
        }

        // publication must contain resources or rule or both
        if (publication.getResources().isEmpty() && publication.getRules() == null) {
            throw new IllegalArgumentException("Publication must have at least one resource or rule");
        }

        ResourceUrl targetFolderUrl = ResourceUrl.parse(publication.getTargetFolder());

        if (!targetFolderUrl.startsWith(ResourceDescriptor.PUBLIC_BUCKET) || !targetFolderUrl.isFolder()) {
            throw new IllegalArgumentException("Publication \"targetUrl\" must start with: %s and ends with: %s"
                    .formatted(ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PATH_SEPARATOR));
        }
        validateRules(publication);
    }

    private void validateResourceForAddition(ProxyContext context, Publication.Resource resource, String targetFolder,
                                             String reviewBucket, Set<String> urls, boolean isPublicationNew) {
        ResourceDescriptor source = ResourceDescriptorFactory.fromPrivateUrl(resource.getSourceUrl(), encryption);
        ResourceDescriptor target = ResourceDescriptorFactory.fromPublicUrl(resource.getTargetUrl());
        verifyResourceType(source);

        String sourceUrl = resource.getSourceUrl();
        String targetUrl = resource.getTargetUrl();

        if (!(accessService.hasReadAccess(source, context) || accessService.hasAdminAccess(context))) {
            throw new PermissionDeniedException("You don't have permission to access resource " + sourceUrl);
        }

        if (source.isFolder()) {
            throw new IllegalArgumentException("Source resource is folder: " + sourceUrl);
        }

        if (target.isFolder()) {
            throw new IllegalArgumentException("Target resource is folder: " + targetUrl);
        }

        if (source.getType() != target.getType()) {
            throw new IllegalArgumentException("Source and target resource types do not match: " + targetUrl);
        }

        if (isPublicationNew && !resourceService.hasResource(source)) {
            throw new IllegalArgumentException("Source resource does not exist: " + sourceUrl);
        }

        if (resource.getAction() == Publication.ResourceAction.ADD && resourceService.hasResource(target)) {
            throw new IllegalArgumentException("Target resource already exists: " + targetUrl);
        }

        if (!urls.add(sourceUrl)) {
            throw new IllegalArgumentException("Source resources have duplicate urls: " + sourceUrl);
        }

        if (!urls.add(targetUrl)) {
            throw new IllegalArgumentException("Target resources have duplicate urls: " + targetUrl);
        }

        String targetSuffix = targetUrl.substring(source.getType().group().length() + 1);

        if (!targetSuffix.startsWith(targetFolder)) {
            throw new IllegalArgumentException("Target resource folder does not match with target folder: " + targetUrl);
        } else {
            targetSuffix = targetSuffix.substring(targetFolder.length());
        }

        String reviewUrl = source.getType().group() + ResourceDescriptor.PATH_SEPARATOR
                + reviewBucket + ResourceDescriptor.PATH_SEPARATOR + targetSuffix;

        if (!urls.add(reviewUrl)) {
            throw new IllegalArgumentException("Review resources have duplicate urls: " + reviewUrl);
        }

        resource.setReviewUrl(reviewUrl);
    }

    private void validateResourceForDeletion(Publication.Resource resource, String targetFolder, Set<String> urls,
                                             String bucketName, boolean isAdmin) {
        String targetUrl = resource.getTargetUrl();
        ResourceDescriptor target = ResourceDescriptorFactory.fromPublicUrl(targetUrl);
        verifyResourceType(target);

        if (target.isFolder()) {
            throw new IllegalArgumentException("Target resource is folder: " + targetUrl);
        }

        String targetSuffix = targetUrl.substring(target.getType().group().length() + 1);
        if (!targetSuffix.startsWith(targetFolder)) {
            throw new IllegalArgumentException("Target resource folder does not match with target folder: " + targetUrl);
        }

        if (!urls.add(targetUrl)) {
            throw new IllegalArgumentException("Target resources have duplicate urls: " + targetUrl);
        }

        if (!resourceService.hasResource(target)) {
            throw new IllegalArgumentException("Target resource does not exists: " + targetUrl);
        }

        if (target.getType() == ResourceTypes.APPLICATION && !isAdmin) {
            Application application = applicationService.getApplication(target).getValue();
            if (application.getFunction() != null && !application.getFunction().getAuthorBucket().equals(bucketName)) {
                throw new IllegalArgumentException("Target application has a different author: " + targetUrl);
            }
        }

        resource.setTargetUrl(targetUrl);
    }

    private void validateRules(Publication publication) {
        if (publication.getRules() != null) {
            for (Rule rule : publication.getRules()) {
                Rule.Function function = rule.getFunction();
                if (function == null) {
                    throw new IllegalArgumentException("Rule does not have function");
                }

                if (rule.getSource() == null) {
                    throw new IllegalArgumentException("Rule does not have source");
                }

                // function TRUE or FALSE do not require targets
                if (function != Rule.Function.TRUE && function != Rule.Function.FALSE) {
                    if (rule.getTargets() == null || rule.getTargets().isEmpty()) {
                        throw new IllegalArgumentException("Rule %s does not have targets".formatted(function));
                    }
                }
            }
        }
    }

    private void checkReviewResources(List<Publication.Resource> resources) {
        for (Publication.Resource resource : resources) {
            String url = resource.getReviewUrl();
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromPrivateUrl(url, encryption);
            verifyResourceType(descriptor);
            if (!resourceService.hasResource(descriptor)) {
                throw new IllegalArgumentException("Review resource does not exist: " + descriptor.getUrl());
            }
        }
    }

    private void checkTargetResources(List<Publication.Resource> resources, boolean exists) {
        for (Publication.Resource resource : resources) {
            String url = resource.getTargetUrl();
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromPublicUrl(url);
            verifyResourceType(descriptor);

            if (resource.getAction() != Publication.ResourceAction.ADD_IF_ABSENT && resourceService.hasResource(descriptor) != exists) {
                String errorMessage = exists ? "Target resource does not exists: " + url : "Target resource  exists: " + url;
                throw new IllegalArgumentException(errorMessage);
            }
        }
    }

    private void copySourceToReviewResources(ProxyContext context, List<Publication.Resource> resources) {
        Map<String, String> replacementLinks = new HashMap<>();

        for (Publication.Resource resource : resources) {
            String sourceUrl = resource.getSourceUrl();
            String reviewUrl = resource.getReviewUrl();

            ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(sourceUrl, encryption);
            ResourceDescriptor to = ResourceDescriptorFactory.fromPrivateUrl(reviewUrl, encryption);

            verifyResourceType(from);

            if (from.getType() == ResourceTypes.FILE) {
                String decodedUrl = UrlUtil.decodePath(from.getUrl());
                replacementLinks.put(decodedUrl, to.getUrl());
            }
        }

        for (Publication.Resource resource : resources) {
            String sourceUrl = resource.getSourceUrl();
            String reviewUrl = resource.getReviewUrl();

            ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(sourceUrl, encryption);
            ResourceDescriptor to = ResourceDescriptorFactory.fromPrivateUrl(reviewUrl, encryption);

            if (from.getType() == ResourceTypes.APPLICATION) {
                applicationService.copyApplication(from, to, null, false, app -> {
                    app.setReference(ProxyUtil.generateReference());
                    app.setIconUrl(replaceLink(replacementLinks, app.getIconUrl()));
                });
            } else if (from.getType() == ResourceTypes.TOOL_SET) {
                Map<CredentialsLevel, Boolean> credentialsToCopy = getCredentialsLevelsToCopy(resource);
                toolSetService.copyToolSet(context, from, to, null, false, credentialsToCopy,
                        toolSet -> toolSet.setIconUrl(replaceLink(replacementLinks, toolSet.getIconUrl())));
            } else if (!resourceService.copyResource(from, to)) {
                throw new IllegalStateException("Can't copy source resource from: " + from.getUrl() + " to review: " + to.getUrl());
            }

            if (from.getType() == ResourceTypes.CONVERSATION) {
                this.resourceService.computeResource(to, body -> PublicationUtil.replaceConversationLinks(body, to, replacementLinks));
            }
        }
    }


    private void copyReviewToTargetResources(ProxyContext context, Publication publication, List<Publication.Resource> resources) {
        Map<String, String> replacementLinks = new HashMap<>();

        for (Publication.Resource resource : resources) {
            String reviewUrl = resource.getReviewUrl();
            String targetUrl = resource.getTargetUrl();

            ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(reviewUrl, encryption);
            ResourceDescriptor to = ResourceDescriptorFactory.fromPublicUrl(targetUrl);

            verifyResourceType(from);

            if (from.getType() == ResourceTypes.FILE) {
                String decodedUrl = UrlUtil.decodePath(from.getUrl());
                replacementLinks.put(decodedUrl, to.getUrl());
            }
        }

        for (Publication.Resource resource : resources) {
            String reviewUrl = resource.getReviewUrl();
            String targetUrl = resource.getTargetUrl();

            ResourceDescriptor from = ResourceDescriptorFactory.fromPrivateUrl(reviewUrl, encryption);
            ResourceDescriptor to = ResourceDescriptorFactory.fromPublicUrl(targetUrl);

            if (from.getType() == ResourceTypes.APPLICATION) {
                applicationService.copyApplication(from, to, publication.getDisplayAuthor(), false, app -> {
                    app.setReference(ProxyUtil.generateReference());
                    app.setIconUrl(replaceLink(replacementLinks, app.getIconUrl()));
                });
            } else if (from.getType() == ResourceTypes.TOOL_SET) {
                Map<CredentialsLevel, Boolean> credentialsToCopy = getCredentialsLevelsToCopy(resource);
                toolSetService.copyToolSet(context, from, to, publication.getDisplayAuthor(), false,
                        credentialsToCopy, toolSet -> toolSet.setIconUrl(replaceLink(replacementLinks, toolSet.getIconUrl())));
            } else {
                UserMetadata userMetadata = new UserMetadata();
                ResourceItemMetadata metadata = resourceService.getResourceMetadata(from);
                if (metadata == null) {
                    throw new IllegalArgumentException("Review resource does not exist: " + from.getUrl());
                }
                userMetadata.setAuthor(publication.getDisplayAuthor());
                userMetadata.setResourceType(metadata.getResourceType().name());
                userMetadata.setEtag(metadata.getEtag());
                long currentIme = clock.getAsLong();
                userMetadata.setCreatedAt(currentIme);
                userMetadata.setUpdatedAt(currentIme);

                if (!resourceService.copyResource(from, to, userMetadata, false)
                        && resource.getAction() != Publication.ResourceAction.ADD_IF_ABSENT) {
                    throw new IllegalStateException("Can't copy source resource from: " + from.getUrl() + " to review: " + to.getUrl());
                }
            }

            if (from.getType() == ResourceTypes.CONVERSATION) {
                resourceService.computeResource(to, body -> PublicationUtil.replaceConversationLinks(body, to, replacementLinks));
            } else if (from.getType() == ResourceTypes.PROMPT) {
                resourceService.computeResource(to, body -> PublicationUtil.replacePromptIdentity(body, to));
            }
        }
    }

    private static Map<CredentialsLevel, Boolean> getCredentialsLevelsToCopy(Publication.Resource resource) {
        return resource.isPublishCredentials()
                ? Map.of(CredentialsLevel.GLOBAL, false)
                : Map.of();
    }

    private void deleteReviewResources(ProxyContext context, List<Publication.Resource> resources) {
        for (Publication.Resource resource : resources) {
            String url = resource.getReviewUrl();
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromPrivateUrl(url, encryption);
            verifyResourceType(descriptor);
            resourceOperationService.deleteResource(context, descriptor, EtagHeader.ANY);
        }
    }

    private void deletePublicResources(ProxyContext context, List<Publication.Resource> resources) {
        for (Publication.Resource resource : resources) {
            String url = resource.getTargetUrl();
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromPublicUrl(url);
            verifyResourceType(descriptor);
            resourceOperationService.deleteResource(context, descriptor, EtagHeader.ANY);
        }
    }

    private void verifyResourceType(ResourceDescriptor descriptor) {
        if (!ALLOWED_RESOURCES.contains(descriptor.getType())) {
            throw new IllegalArgumentException("Unsupported type: " + descriptor.getType());
        }
    }

    private String encodeReviewBucket(String bucketLocation, String id) {
        String path = bucketLocation
                + PUBLICATIONS_NAME + ResourceDescriptor.PATH_SEPARATOR
                + id + ResourceDescriptor.PATH_SEPARATOR;

        return encryption.encrypt(path);
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
                .setName(publication.getName())
                .setTargetFolder(publication.getTargetFolder())
                .setStatus(publication.getStatus())
                .setResourceTypes(publication.getResourceTypes())
                .setAuthor(publication.getAuthor())
                .setCreatedAt(publication.getCreatedAt());
    }

    private static ResourceDescriptor publications(ResourceDescriptor resource) {
        return publications(resource.getBucketName(), resource.getBucketLocation());
    }

    private static ResourceDescriptor publications(String bucket, String location) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.PUBLICATION,
                bucket, location, PUBLICATIONS_NAME);
    }

    private static Map<String, Publication> decodePublications(String json) {
        Map<String, Publication> publications = ProxyUtil.convertToObject(json, PUBLICATIONS_TYPE);
        return (publications == null) ? new LinkedHashMap<>() : publications;
    }

    private static String encodePublications(Map<String, Publication> publications) {
        return ProxyUtil.convertToString(publications);
    }

    private static String replaceLink(Map<String, String> links, String url) {
        if (url != null) {
            String key = UrlUtil.decodePath(url);
            String replacement = links.get(key);

            if (replacement != null) {
                return replacement;
            }
        }

        return url;
    }

}
