package com.epam.aidial.core.service;

import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.InvitationsMap;
import com.epam.aidial.core.data.ResourceLink;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.security.ApiKeyGenerator;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@AllArgsConstructor
@Slf4j
public class InvitationService {

    private static final String INVITATION_RESOURCE_FILENAME = "invitations";
    static final String INVITATION_PATH_BASE = "v1/invitations";

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;
    private final int expirationInSeconds;

    public Invitation createInvitation(String bucket, String location, Set<ResourceLink> resources) {
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
        String resourceState = resourceService.getResource(resource);
        InvitationsMap invitations = ProxyUtil.convertToObject(resourceState, InvitationsMap.class);
        if (invitations == null) {
            throw new ResourceNotFoundException("No invitation found for ID " + invitationId);
        }

        Invitation invitation = invitations.getInvitations().get(invitationId);
        if (invitation == null) {
            throw new ResourceNotFoundException("No invitation found for ID " + invitationId);
        }

        Instant expireAt = Instant.ofEpochMilli(invitation.getExpireAt());
        if (Instant.now().isAfter(expireAt)) {
            // invitation expired - we need to clean up state
            cleanUpExpiredInvitations(resource, List.of(invitationId));
            throw new ResourceNotFoundException("No invitation found for ID " + invitationId);
        }

        return invitation;
    }

    public void deleteInvitation(String bucket, String location, String invitationId) {
        ResourceDescription resource = getInvitationResource(invitationId);
        // deny operation if caller is not an owner
        if (!resource.getBucketName().equals(bucket)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Only invitation owner can delete invitation");
        }
        cleanUpExpiredInvitations(resource, List.of(invitationId));
    }

    public List<Invitation> getMyInvitations(String bucket, String location) {
        ResourceDescription resource = ResourceDescription.fromDecoded(ResourceType.INVITATION, bucket, location, INVITATION_RESOURCE_FILENAME);
        String state = resourceService.getResource(resource);
        InvitationsMap invitationMap = ProxyUtil.convertToObject(state, InvitationsMap.class);
        if (invitationMap == null || invitationMap.getInvitations().isEmpty()) {
            return List.of();
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

        return invitationMap.getInvitations().values().stream().toList();
    }

    private void cleanUpExpiredInvitations(ResourceDescription resource, Collection<String> idsToEvict) {
        resourceService.computeResource(resource, state -> {
            InvitationsMap invitations = ProxyUtil.convertToObject(state, InvitationsMap.class);
            if (invitations == null) {
                invitations = new InvitationsMap(new HashMap<>());
            }
            Map<String, Invitation> invitationMap = invitations.getInvitations();
            idsToEvict.forEach(invitationMap::remove);

            return ProxyUtil.convertToString(invitations);
        });
    }

    private ResourceDescription getInvitationResource(String invitationId) {
        // decrypt invitation ID to obtain its location
        String decryptedInvitationPath = encryptionService.decrypt(invitationId);
        if (decryptedInvitationPath == null) {
            throw new ResourceNotFoundException("No invitation found for ID " + invitationId);
        }

        String[] parts = decryptedInvitationPath.split(BlobStorageUtil.PATH_SEPARATOR);
        // due to current design decoded resource location looks like: Users/<SUB>/invitations/invitations.json/<random_id>
        if (parts.length != 5) {
            throw new ResourceNotFoundException("No invitation found for ID " + invitationId);
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
