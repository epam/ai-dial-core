package com.epam.aidial.core.service;

import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.InvitationCollection;
import com.epam.aidial.core.data.InvitationsMap;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.SharedResource;
import com.epam.aidial.core.security.ApiKeyGenerator;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Slf4j
public class InvitationService {

    private static final InvitationCollection EMPTY_INVITATION_COLLECTION = new InvitationCollection(Set.of());

    private static final String INVITATION_RESOURCE_FILENAME = "invitations";
    private static final int DEFAULT_INVITATION_TTL_IN_SECONDS = 259_200;
    static final String INVITATION_PATH_BASE = "/v1/invitations";

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;
    private final int expirationInSeconds;

    public InvitationService(ResourceService resourceService, EncryptionService encryptionService, JsonObject settings) {
        this.resourceService = resourceService;
        this.encryptionService = encryptionService;
        this.expirationInSeconds = settings.getInteger("ttlInSeconds", DEFAULT_INVITATION_TTL_IN_SECONDS);
    }

    public Invitation createInvitation(String bucket, String location, List<SharedResource> resources) {
        ResourceDescription resource = ResourceDescription.fromDecoded(ResourceType.INVITATION, bucket, location, INVITATION_RESOURCE_FILENAME);
        String invitationId = generateInvitationId(resource);
        Instant creationTime = Instant.now();
        Instant expirationTime = Instant.now().plus(expirationInSeconds, ChronoUnit.SECONDS);
        Invitation invitation = new Invitation(invitationId, resources, creationTime.toEpochMilli(), expirationTime.toEpochMilli());

        resourceService.computeResource(resource, state -> {
            InvitationsMap invitations = ProxyUtil.convertToObject(state, InvitationsMap.class);
            if (invitations == null) {
                invitations = new InvitationsMap(new HashMap<>());
            }
            invitations.getInvitations().put(invitationId, invitation);

            return ProxyUtil.convertToString(invitations);
        });

        return invitation;
    }

    @Nullable
    public Invitation getInvitation(String invitationId) {
        ResourceDescription resource = getInvitationResource(invitationId);
        if (resource == null) {
            return null;
        }
        String resourceState = resourceService.getResource(resource);
        InvitationsMap invitations = ProxyUtil.convertToObject(resourceState, InvitationsMap.class);
        if (invitations == null) {
            return null;
        }

        Invitation invitation = invitations.getInvitations().get(invitationId);
        if (invitation == null) {
            return null;
        }

        Instant expireAt = Instant.ofEpochMilli(invitation.getExpireAt());
        if (Instant.now().isAfter(expireAt)) {
            // invitation expired - we need to clean up state
            cleanUpExpiredInvitations(resource, List.of(invitationId));
            return null;
        }

        return invitation;
    }

    public void deleteInvitation(String bucket, String invitationId) {
        ResourceDescription resource = getInvitationResource(invitationId);
        if (resource == null) {
            throw new ResourceNotFoundException("No invitation found for ID" + invitationId);
        }
        // deny operation if caller is not an owner
        if (!resource.getBucketName().equals(bucket)) {
            throw new PermissionDeniedException("You are not invitation owner");
        }
        cleanUpExpiredInvitations(resource, List.of(invitationId));
    }

    public InvitationCollection getMyInvitations(String bucket, String location) {
        ResourceDescription resource = ResourceDescription.fromDecoded(ResourceType.INVITATION, bucket, location, INVITATION_RESOURCE_FILENAME);
        String state = resourceService.getResource(resource);
        InvitationsMap invitationMap = ProxyUtil.convertToObject(state, InvitationsMap.class);
        if (invitationMap == null || invitationMap.getInvitations().isEmpty()) {
            return EMPTY_INVITATION_COLLECTION;
        }

        Collection<Invitation> invitations = invitationMap.getInvitations().values();
        Instant currentTime = Instant.now();
        Set<String> invitationsToEvict = invitations.stream()
                .filter(invitation -> currentTime.isAfter(Instant.ofEpochMilli(invitation.getExpireAt())))
                .map(Invitation::getId)
                .collect(Collectors.toSet());

        if (!invitationsToEvict.isEmpty()) {
            cleanUpExpiredInvitations(resource, invitationsToEvict);
            invitationsToEvict.forEach(invitationToEvict -> invitationMap.getInvitations().remove(invitationToEvict));
        }

        return new InvitationCollection(new HashSet<>(invitationMap.getInvitations().values()));
    }

    public void cleanUpResourceLink(String bucket, String location, ResourceDescription resource) {
        cleanUpPermissions(bucket, location, Map.of(resource, ResourceAccessType.ALL));
    }

    public void cleanUpPermissions(
            String bucket, String location, Map<ResourceDescription, Set<ResourceAccessType>> permissionsToCleanUp) {
        ResourceDescription resource = ResourceDescription.fromDecoded(ResourceType.INVITATION, bucket, location, INVITATION_RESOURCE_FILENAME);
        resourceService.computeResource(resource, state -> {
            InvitationsMap invitations = ProxyUtil.convertToObject(state, InvitationsMap.class);
            if (invitations == null) {
                return null;
            }
            Map<String, Invitation> invitationMap = invitations.getInvitations();
            List<String> invitationsToRemove = new ArrayList<>();
            Map<String, Set<ResourceAccessType>> linkToPermissions = permissionsToCleanUp.keySet().stream()
                    .collect(Collectors.toUnmodifiableMap(ResourceDescription::getUrl, permissionsToCleanUp::get));
            for (Invitation invitation : invitationMap.values()) {
                List<SharedResource> updatedResources = new ArrayList<>();
                for (SharedResource sharedResource : invitation.getResources()) {
                    Set<ResourceAccessType> permissions = linkToPermissions.get(sharedResource.url());
                    if (permissions == null) {
                        updatedResources.add(sharedResource);
                    } else {
                        sharedResource.permissions().removeAll(permissions);
                        if (!sharedResource.permissions().isEmpty()) {
                            updatedResources.add(sharedResource);
                        }
                    }
                }

                if (updatedResources.isEmpty()) {
                    invitationsToRemove.add(invitation.getId());
                } else {
                    invitation.setResources(updatedResources);
                }
            }

            invitationsToRemove.forEach(invitationMap::remove);

            return ProxyUtil.convertToString(invitations);
        });
    }

    public void moveResource(String bucket, String location, ResourceDescription source, ResourceDescription destination) {
        ResourceDescription resource = ResourceDescription.fromDecoded(ResourceType.INVITATION, bucket, location, INVITATION_RESOURCE_FILENAME);
        resourceService.computeResource(resource, state -> {
            InvitationsMap invitations = ProxyUtil.convertToObject(state, InvitationsMap.class);
            if (invitations == null) {
                return null;
            }
            Map<String, Invitation> invitationMap = invitations.getInvitations();
            for (Invitation invitation : invitationMap.values()) {
                List<SharedResource> invitationResourceLinks = invitation.getResources();
                Set<SharedResource> toMove = invitationResourceLinks.stream()
                        .filter(sharedResource -> source.getUrl().equals(sharedResource.url()))
                        .collect(Collectors.toUnmodifiableSet());
                for (SharedResource sharedResource : toMove) {
                    invitationResourceLinks.remove(sharedResource);
                    invitationResourceLinks.add(sharedResource.withUrl(destination.getUrl()));
                }
            }

            return ProxyUtil.convertToString(invitations);
        });
    }

    private void cleanUpExpiredInvitations(ResourceDescription resource, Collection<String> idsToEvict) {
        resourceService.computeResource(resource, state -> {
            InvitationsMap invitations = ProxyUtil.convertToObject(state, InvitationsMap.class);
            if (invitations == null) {
                return null;
            }
            Map<String, Invitation> invitationMap = invitations.getInvitations();
            idsToEvict.forEach(invitationMap::remove);

            return ProxyUtil.convertToString(invitations);
        });
    }

    @Nullable
    public ResourceDescription getInvitationResource(String invitationId) {
        // decrypt invitation ID to obtain its location
        String decryptedInvitationPath = encryptionService.decrypt(invitationId);
        if (decryptedInvitationPath == null) {
            return null;
        }

        String[] parts = decryptedInvitationPath.split(BlobStorageUtil.PATH_SEPARATOR);
        // due to current design decoded resource location looks like: Users/<SUB>/invitations/invitations.json/<random_id>
        if (parts.length != 5) {
            return null;
        }
        String location = parts[0] + BlobStorageUtil.PATH_SEPARATOR + parts[1] + BlobStorageUtil.PATH_SEPARATOR;
        String bucket = encryptionService.encrypt(location);
        ResourceType resourceType = ResourceType.of(parts[2]);
        return ResourceDescription.fromDecoded(resourceType, bucket, location, INVITATION_RESOURCE_FILENAME);
    }

    private String generateInvitationId(ResourceDescription resource) {
        return encryptionService.encrypt(resource.getAbsoluteFilePath() + BlobStorageUtil.PATH_SEPARATOR + ApiKeyGenerator.generateKey());
    }
}
