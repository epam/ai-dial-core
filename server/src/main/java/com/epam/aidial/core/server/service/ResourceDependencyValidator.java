package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.RequiredArgsConstructor;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.FORBIDDEN;

/**
 * Write-time validation of the {@code resourceDependencies} declaration section. This is the
 * pointer rule, not an access decision: creating a dependency requires no permission on the
 * target — whether the originating user can reach the target is a runtime question, verified
 * fresh per request at resolution time. Only the shape of the ask and the authoring governance
 * ceiling are checked here.
 */
@RequiredArgsConstructor
public class ResourceDependencyValidator {

    /** A declaration larger than this is wrong-shaped; it should be folder-scoped links, not a file inventory. */
    public static final int MAX_DECLARED_DEPENDENCIES = 100;

    /**
     * Stands for the initiator's bucket; valid only as the bucket segment. Braces cannot occur in a real bucket
     * (Base58) or a stored name (ResourceDescriptorFactory rejects them), so the token never collides with either.
     */
    private static final String CURRENT_USER_PLACEHOLDER = "{current-user}";

    /**
     * The resource types a declaration may address (segment 0); ResourceTypes.of() also maps internal
     * engine types (credentials, keys, models, …), which are never declarable.
     */
    private static final Set<String> DECLARABLE_RESOURCE_TYPES =
            Set.of("files", "prompts", "conversations", "applications", "toolsets", "skills");

    private static final String DECLARABLE_TYPES_LIST = String.join(", ", new TreeSet<>(DECLARABLE_RESOURCE_TYPES));

    private final boolean allowUserResourceDependencies;

    /** Throws on the first shape violation. Applied on every writer surface regardless of author. */
    public void validateShape(Application application) {
        String error = findShapeError(application);
        if (error != null) {
            throw new HttpException(BAD_REQUEST, error);
        }
    }

    /** The shape violations as one message, or null when there are none. */
    public static String findShapeError(Application application) {
        List<String> issues = shapeIssues(application);
        return issues.isEmpty() ? null : "Invalid resource dependencies: " + String.join("; ", issues);
    }

    /** User-authored apps may declare dependencies only while allowUserResourceDependencies is on; admin-authored writes are not gated here. */
    public void validateUserAuthored(Application application) {
        List<ResourceDependency> section = application.getResourceDependencies();
        if (section == null || section.isEmpty()) {
            return;
        }
        if (!allowUserResourceDependencies) {
            throw new HttpException(FORBIDDEN,
                    "User-authored applications may not declare resource dependencies (allowUserResourceDependencies is disabled)");
        }
    }

    /** Non-throwing form of {@link #validateShape}: the same rules, usable from any write surface. */
    public static List<String> shapeIssues(Application application) {
        List<String> issues = new ArrayList<>();
        List<ResourceDependency> section = application.getResourceDependencies();
        if (section == null || section.isEmpty()) {
            return issues;
        }
        if (section.size() > MAX_DECLARED_DEPENDENCIES) {
            return List.of("resourceDependencies: the section exceeds " + MAX_DECLARED_DEPENDENCIES + " entries");
        }
        Set<String> seenLinkIds = new HashSet<>();
        for (int i = 0; i < section.size(); i++) {
            ResourceDependency dependency = section.get(i);
            String at = "resourceDependencies[" + i + "]";
            if (dependency == null) {
                issues.add(at + ": entry is null");
                continue;
            }
            if (!ResourceDependency.KIND.equals(dependency.getKind())) {
                issues.add(at + ": kind must be " + ResourceDependency.KIND);
            }
            String linkId = dependency.getLinkId();
            if (linkId == null || linkId.isBlank()) {
                issues.add(at + ": linkId is required");
            } else if (!seenLinkIds.add(linkId)) {
                issues.add(at + ": duplicate linkId '" + linkId + "'");
            }
            issues.addAll(pathIssues(at, dependency));
            // An explicit JSON null defeats the field default, so guard for null alongside empty.
            if (dependency.getAccess() == null || dependency.getAccess().isEmpty()) {
                issues.add(at + ": access must not be empty");
            } else if (dependency.getAccess().contains(ResourceAccessType.SHARE)) {
                issues.add(at + ": SHARE is not a dependency right");
            }
        }
        return issues;
    }

    private static List<String> pathIssues(String at, ResourceDependency dependency) {
        String path = pathOf(dependency);
        if (path == null || path.replace(ResourceDescriptor.PATH_SEPARATOR, "").isEmpty()) {
            return List.of(at + ": target.path is required");
        }
        try {
            validateTarget(path);
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(at + ": " + e.getMessage() + ": " + path);
        }
    }

    /** Write side: throws {@link IllegalArgumentException} unless {@code path} is a declarable target. */
    public static void validateTarget(String path) {
        checkTarget(path);
    }

    /**
     * Read side: the descriptor {@code path} names, {@code {current-user}} standing for the initiator's bucket.
     *
     * @throws IllegalArgumentException when {@code path} is not a declarable target
     */
    public static ResourceDescriptor resolveTarget(String path, AuthBucket initiator, EncryptionService encryption) {
        String[] segments = checkTarget(path);
        return parsed(() -> isPlaceholder(segments[1])
                ? ResourceDescriptorFactory.fromEncoded(ResourceTypes.of(UrlUtil.tryDecodePath(segments[0])),
                        initiator.getUserBucket(), initiator.getUserBucketLocation(), restOf(path, segments))
                : ResourceDescriptorFactory.fromAnyUrl(path, encryption));
    }

    /**
     * The write-side rules, also applied first on the read side. The bucket is opaque at write time — whose it is,
     * and whether it is reachable, is a request-time question — so it is parsed as an ordinary element under public,
     * which still puts it through the factory's decoding and name rules.
     */
    private static String[] checkTarget(String path) {
        String[] segments = path.split(ResourceDescriptor.PATH_SEPARATOR, -1);
        for (int i = 2; i < segments.length; i++) {
            if (isPlaceholder(segments[i])) {
                throw new IllegalArgumentException("the " + CURRENT_USER_PLACEHOLDER
                        + " placeholder is valid only as the bucket segment (the second segment)");
            }
        }
        String type = UrlUtil.tryDecodePath(segments[0]);
        if ("users".equals(type)) {
            throw new IllegalArgumentException("personal targets are declared as {type}/" + CURRENT_USER_PLACEHOLDER
                    + "/…, not as a concrete users/… path");
        }
        if (!DECLARABLE_RESOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException("target must start with a declarable resource type (" + DECLARABLE_TYPES_LIST + ")");
        }
        if (segments.length < 3) {
            throw new IllegalArgumentException(
                    "target must address a folder or resource within a bucket ({type}/{bucket}/…), not the type root");
        }
        if (path.contains("//")) {
            throw new IllegalArgumentException("path must not contain empty segments");
        }
        String bucketElement = isPlaceholder(segments[1]) ? "" : segments[1] + ResourceDescriptor.PATH_SEPARATOR;
        parsed(() -> ResourceDescriptorFactory.fromAnyUrl(segments[0] + ResourceDescriptor.PATH_SEPARATOR
                + ResourceDescriptor.PUBLIC_LOCATION + bucketElement + restOf(path, segments), null));
        return segments;
    }

    /** Runs the factory call; its failure is reported as not a valid url, then the declaration's element rules apply. */
    private static ResourceDescriptor parsed(Supplier<ResourceDescriptor> factoryCall) {
        ResourceDescriptor target;
        try {
            target = factoryCall.get();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("target is not a valid resource url", e);
        } catch (RuntimeException e) {
            // UrlUtil.decodePath wraps a malformed URI this way; anything else is a defect, not bad input
            if (e.getCause() instanceof URISyntaxException) {
                throw new IllegalArgumentException("target is not a valid resource url", e);
            }
            throw e;
        }
        List<String> elements = new ArrayList<>(target.getParentFolders());
        if (target.getName() != null) {
            elements.add(target.getName());
        }
        for (String element : elements) {
            if (element.contains("*")) {
                throw new IllegalArgumentException("wildcards are not allowed");
            }
        }
        return target;
    }

    private static boolean isPlaceholder(String segment) {
        return CURRENT_USER_PLACEHOLDER.equals(UrlUtil.tryDecodePath(segment));
    }

    /** The relative path after {type}/{bucket}/. */
    private static String restOf(String path, String[] segments) {
        return path.substring(segments[0].length() + segments[1].length() + 2);
    }

    private static String pathOf(ResourceDependency dependency) {
        if (dependency.getTarget() == null || dependency.getTarget().getPath() == null) {
            return null;
        }
        String path = dependency.getTarget().getPath().trim();
        return path.isEmpty() ? null : path;
    }
}
