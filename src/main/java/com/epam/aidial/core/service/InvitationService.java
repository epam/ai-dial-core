package com.epam.aidial.core.service;

import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.ResourceLink;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@AllArgsConstructor
@Slf4j
public class InvitationService {

    private static final String INVITATION_PATTERN = "invitations:%s";

    private static final int EXPIRATION_IN_DAYS = 3;

    private final RedissonClient redis;

    public Invitation createInvitation(String owner, Set<ResourceLink> resources) {
        String invitationId = generateId();
        Instant creationTime = Instant.now();
        Instant expirationTime = Instant.now().plus(EXPIRATION_IN_DAYS, ChronoUnit.DAYS);
        Invitation invitation = new Invitation(invitationId, owner, resources, creationTime.toEpochMilli(), expirationTime.toEpochMilli());

        RBucket<Invitation> cachedInvitation = redis.getBucket(INVITATION_PATTERN.formatted(invitationId));
        cachedInvitation.set(invitation);
        cachedInvitation.expire(expirationTime);

        RSet<String> userInvitations = redis.getSet(INVITATION_PATTERN.formatted(owner));
        userInvitations.add(invitationId);

        return invitation;
    }

    @Nullable
    public Invitation getInvitation(String invitationId) {
        return (Invitation) redis.getBucket(INVITATION_PATTERN.formatted(invitationId)).get();
    }

    public void deleteInvitation(String userId, String invitationId) {
        RBucket<Invitation> invitationObject = redis.getBucket(INVITATION_PATTERN.formatted(invitationId));

        if (invitationObject.isExists()) {
            Invitation invitation = invitationObject.get();
            String invitationOwner = invitation.getOwner();
            if (invitationOwner.equals(userId)) {
                invitationObject.delete();

                RSet<String> userInvitations = redis.getSet(INVITATION_PATTERN.formatted(userId));
                userInvitations.remove(invitationId);
            } else {
                throw new RuntimeException("Permission denied");
            }
        } else {
            throw new RuntimeException("Invitation with ID %s not found".formatted(invitationId));
        }
    }

    public List<Invitation> getMyInvitations(String userId) {
        RSet<String> userInvitations = redis.getSet(INVITATION_PATTERN.formatted(userId));
        return userInvitations.stream()
                .map(invitationId -> (Invitation) redis.getBucket(INVITATION_PATTERN.formatted(invitationId)).get())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static String generateId() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }
}
