package com.epam.aidial.core.server.orgunit;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * What a unit's role holders may do with publication requests into it: {@code read publications/**} lists and reviews
 * them, {@code write publications/**} approves and rejects them. Nothing here concerns DIAL admins: callers check them
 * first. Everything fails closed: anything unreadable or outside every unit allows nothing.
 */
@RequiredArgsConstructor
public class OrgUnitApprovals {

    private static final String PUBLICATIONS = "publications";
    private static final TypeReference<Map<String, Publication>> PUBLICATIONS_TYPE = new TypeReference<>() {
    };

    private final OrgUnitService orgUnits;
    private final EncryptionService encryption;
    private final ResourceService resources;

    public boolean isEnabled() {
        return orgUnits.isEnabled();
    }

    /**
     * Approve or reject: the caller may read and write publications in every unit the request touches, and it isn't
     * the caller's own. Rules a request still carries, as one filed before its folder became a unit does, are never
     * stored inside a unit.
     */
    public boolean mayDecide(ProxyContext context, Publication publication) {
        if (!isEnabled() || !isPerson(context) || isRequester(context, publication)) {
            return false;
        }
        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        return grantedInEveryUnit(context, publication, units, OrgUnitRoles.READ)
                && grantedInEveryUnit(context, publication, units, OrgUnitRoles.WRITE);
    }

    /**
     * Read the request and its review copies: the caller may read publications in the unit of its target folder and
     * of every target.
     */
    public boolean mayReview(ProxyContext context, Publication publication) {
        return isEnabled() && isPerson(context) && grantedInEveryUnit(context, publication, orgUnits.getUnitList().units(), OrgUnitRoles.READ);
    }

    private boolean grantedInEveryUnit(ProxyContext context, Publication publication, Map<String, OrgUnit> units, String action) {
        Set<String> touched = unitsOf(units, publication);
        return touched != null && touched.stream().allMatch(key -> grants(context, orgUnits.roleAssignmentsOf(key, units.get(key)), action));
    }

    /**
     * Whether a pending request with this target folder belongs in the caller's list.
     */
    public boolean approvesUnitOf(ProxyContext context, String targetFolder) {
        if (!isEnabled() || !isPerson(context)) {
            return false;
        }

        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        String key = ownerOfFolder(units, targetFolder);
        return key != null && grants(context, orgUnits.roleAssignmentsOf(key, units.get(key)), OrgUnitRoles.READ);
    }

    /**
     * Whether the folder lies in a unit, where roles decide who reads and read rules have no place.
     */
    public boolean isInsideUnit(@Nullable String targetFolder) {
        return isEnabled() && ownerOfFolder(orgUnits.getUnitList().units(), targetFolder) != null;
    }

    public boolean approvesAnyUnit(ProxyContext context) {
        return isEnabled() && isPerson(context) && approvesAnyUnit(context, orgUnits.getUnitList().units());
    }

    /**
     * Reads every unit's policy in one batch rather than one call per unit.
     */
    private boolean approvesAnyUnit(ProxyContext context, Map<String, OrgUnit> units) {
        return orgUnits.roleAssignmentsOfAll(units).values().stream()
                .anyMatch(assignments -> grants(context, assignments, OrgUnitRoles.READ));
    }

    /**
     * A file or folder in a requester's review bucket, {@code <requester location>publications/<id>/}, while the
     * request is pending and the caller may review it.
     */
    public boolean mayReadReviewCopy(ProxyContext context, ResourceDescriptor resource) {
        if (!isEnabled() || !isPerson(context) || !PublicationService.isReviewBucket(resource)) {
            return false;
        }

        String location = resource.getBucketLocation();
        String suffix = PUBLICATIONS + ResourceDescriptor.PATH_SEPARATOR;
        int idStart = location.lastIndexOf(ResourceDescriptor.PATH_SEPARATOR, location.length() - 2) + 1;
        if (!location.endsWith(ResourceDescriptor.PATH_SEPARATOR) || idStart <= suffix.length()
                || !location.substring(0, idStart).endsWith(suffix)) {
            return false;
        }

        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        if (!approvesAnyUnit(context, units)) {
            return false;
        }

        String requesterLocation = location.substring(0, idStart - suffix.length());
        String id = location.substring(idStart, location.length() - 1);
        String requesterBucket = encryption.encrypt(requesterLocation);
        ResourceDescriptor requests = ResourceDescriptorFactory.fromDecoded(ResourceTypes.PUBLICATION,
                requesterBucket, requesterLocation, PUBLICATIONS);
        Map<String, Publication> publications = ProxyUtil.convertToObject(resources.getResource(requests), PUBLICATIONS_TYPE);
        Publication publication = publications == null ? null
                : publications.get(String.join(ResourceDescriptor.PATH_SEPARATOR, PUBLICATIONS, requesterBucket, id));
        return publication != null && publication.getStatus() == Publication.Status.PENDING
                && grantedInEveryUnit(context, publication, units, OrgUnitRoles.READ);
    }

    /**
     * The unit keys of the target folder and of every target; null when any of them lies under no unit or can't be read.
     */
    @Nullable
    private static Set<String> unitsOf(Map<String, OrgUnit> units, Publication publication) {
        Set<String> touched = new HashSet<>();
        touched.add(ownerOfFolder(units, publication.getTargetFolder()));
        for (Publication.Resource resource : publication.getResources()) {
            try {
                touched.add(OrgUnitService.ownerOf(units, ResourceDescriptorFactory.fromPublicUrl(resource.getTargetUrl())));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return touched.contains(null) || touched.contains(OrgUnitService.SHARED_SPACE_KEY) ? null : touched;
    }

    @Nullable
    private static String ownerOfFolder(Map<String, OrgUnit> units, @Nullable String targetFolder) {
        if (targetFolder == null || !targetFolder.startsWith(ResourceDescriptor.PUBLIC_LOCATION)
                || targetFolder.equals(ResourceDescriptor.PUBLIC_LOCATION)) {
            return null;
        }

        try {
            ResourceDescriptor folder = ResourceDescriptorFactory.fromEncoded(ResourceTypes.FILE, ResourceDescriptor.PUBLIC_BUCKET,
                    ResourceDescriptor.PUBLIC_LOCATION, targetFolder.substring(ResourceDescriptor.PUBLIC_LOCATION.length()));
            String key = OrgUnitService.ownerOf(units, folder);
            return key.equals(OrgUnitService.SHARED_SPACE_KEY) ? null : key;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isRequester(ProxyContext context, Publication publication) {
        String bucket = encryption.encrypt(BucketBuilder.buildInitiatorBucket(context));
        String url = publication.getUrl();
        return url == null || url.startsWith(PUBLICATIONS + ResourceDescriptor.PATH_SEPARATOR + bucket + ResourceDescriptor.PATH_SEPARATOR);
    }

    private static boolean isPerson(ProxyContext context) {
        return OrgUnitSubjects.isPerson(context);
    }

    private static boolean grants(ProxyContext context, List<RoleAssignment> assignments, String action) {
        return OrgUnitRoles.of(context.getConfig()).allows(OrgUnitSubjects.heldRoles(context, assignments), action, OrgUnitRoles.PUBLICATIONS);
    }
}
