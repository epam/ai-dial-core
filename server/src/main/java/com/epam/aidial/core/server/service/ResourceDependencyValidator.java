package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.FORBIDDEN;

/**
 * Write-time shape rules for the {@code resourceDependencies} section.
 * Declaring needs no permission on the target; the user's reach is checked per request.
 * The user-authored ceiling is enforced in {@link ApplicationService#inheritOrGuardDependencies}.
 */
public class ResourceDependencyValidator {

    private ResourceDependencyValidator() {
    }

    /** A declaration larger than this is wrong-shaped; it should be folder-scoped links, not a file inventory. */
    public static final int MAX_DECLARED_DEPENDENCIES = 100;

    /**
     * Stands for the initiator's bucket; valid only as the bucket segment. Braces cannot occur in a real bucket
     * (Base58) or a stored name (ResourceDescriptorFactory rejects them), so the token never collides with either.
     */
    private static final String CURRENT_USER_PLACEHOLDER = "{current-user}";

    /** The resource types a declaration may address; internal engine types (credentials, keys, models, …) never are. */
    private static final Set<ResourceTypes> DECLARABLE_RESOURCE_TYPES = Set.of(ResourceTypes.FILE, ResourceTypes.PROMPT,
            ResourceTypes.CONVERSATION, ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET, ResourceTypes.SKILL, ResourceTypes.PROJECT);

    private static final Set<String> DECLARABLE_GROUPS = DECLARABLE_RESOURCE_TYPES.stream()
            .map(ResourceTypes::group).collect(Collectors.toUnmodifiableSet());

    private static final String DECLARABLE_TYPES_LIST = DECLARABLE_GROUPS.stream()
            .sorted().collect(Collectors.joining(", "));

    /** WRITE on these would let an application re-point the caller's deployments, and with them later calls and stored credentials. */
    private static final Set<ResourceTypes> READ_ONLY_RESOURCE_TYPES = Set.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET);

    public static void validateShape(Application application) {
        String error = findShapeError(application);
        if (error != null) {
            throw new HttpException(BAD_REQUEST, error);
        }
    }

    /** The shape violations as one message, or null when there are none. */
    public static String findShapeError(Application application) {
        List<String> issues = collectShapeIssues(application);
        return issues.isEmpty() ? null : "Invalid resource dependencies: " + String.join("; ", issues);
    }

    private static List<String> collectShapeIssues(Application application) {
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
            issues.addAll(collectUrlIssues(at, dependency));
            // An explicit JSON null defeats the field default, so guard for null alongside empty.
            if (dependency.getAccess() == null || dependency.getAccess().isEmpty()) {
                issues.add(at + ": access must not be empty");
            } else if (dependency.getAccess().contains(ResourceAccessType.SHARE)) {
                issues.add(at + ": SHARE is not a dependency right");
            }
        }
        return issues;
    }

    private static List<String> collectUrlIssues(String at, ResourceDependency dependency) {
        String url = readTargetUrl(dependency);
        if (url == null || url.replace(ResourceDescriptor.PATH_SEPARATOR, "").isEmpty()) {
            return List.of(at + ": target.url is required");
        }
        if (!url.equals(url.strip())) {
            return List.of(at + ": target.url must not have surrounding whitespace");
        }
        try {
            String[] segments = checkTarget(url);
            if (!isAllowedAccess(ResourceTypes.of(UrlUtil.tryDecodePath(segments[0])), dependency.getAccess())) {
                return List.of(at + ": WRITE is not a dependency right on " + segments[0] + ": " + url);
            }
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(at + ": " + e.getMessage() + ": " + url);
        }
    }

    /** Whether {@code access} may be declared on a target of {@code type}; the write side and the read side share the rule. */
    public static boolean isAllowedAccess(ResourceType type, @Nullable Set<ResourceAccessType> access) {
        return access == null || !access.contains(ResourceAccessType.WRITE) || !READ_ONLY_RESOURCE_TYPES.contains(type);
    }

    /**
     * The access set a dependency entry may grant at runtime (READ and/or WRITE, excluding SHARE and unknown types),
     * or null when the entry can never be granted.
     */
    @Nullable
    public static Set<ResourceAccessType> grantableAccessOf(@Nullable ResourceDependency dependency) {
        if (dependency == null || !ResourceDependency.KIND.equals(dependency.getKind())) {
            return null;
        }
        Set<ResourceAccessType> access = dependency.getAccess();
        if (access == null || access.isEmpty()) {
            return null;
        }
        if (access.contains(ResourceAccessType.SHARE)) {
            return null;
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(access));
    }

    /**
     * Read side: the descriptor {@code url} names, {@code {current-user}} standing for the initiator's bucket.
     *
     * @throws IllegalArgumentException when {@code url} is not a declarable target
     */
    public static ResourceDescriptor resolveTarget(String url, AuthBucket initiator, EncryptionService encryption) {
        String[] segments = checkTarget(url);
        return parseTarget(() -> isPlaceholder(segments[1])
                ? ResourceDescriptorFactory.fromEncoded(ResourceTypes.of(UrlUtil.tryDecodePath(segments[0])),
                        initiator.getUserBucket(), initiator.getUserBucketLocation(), stripTypeAndBucket(url, segments))
                : ResourceDescriptorFactory.fromAnyUrl(url, encryption));
    }

    /**
     * The write-side rules, also applied first on the read side. The bucket is opaque at write time — whose it is,
     * and whether it is reachable, is a request-time question — so it is parsed as an ordinary element under public,
     * which still puts it through the factory's decoding and name rules.
     */
    private static String[] checkTarget(String url) {
        String[] segments = url.split(ResourceDescriptor.PATH_SEPARATOR, -1);
        for (int i = 2; i < segments.length; i++) {
            if (isPlaceholder(segments[i])) {
                throw new IllegalArgumentException("the " + CURRENT_USER_PLACEHOLDER
                        + " placeholder is valid only as the bucket segment (the second segment)");
            }
        }
        String type = UrlUtil.tryDecodePath(segments[0]);
        if ("users".equals(type)) {
            throw new IllegalArgumentException("personal targets are declared as {type}/" + CURRENT_USER_PLACEHOLDER
                    + "/…, not as a concrete users/… url");
        }
        if (!isDeclarable(type)) {
            throw new IllegalArgumentException("target must start with a declarable resource type (" + DECLARABLE_TYPES_LIST + ")");
        }
        if (segments.length < 3) {
            throw new IllegalArgumentException(
                    "target must address a folder or resource within a bucket ({type}/{bucket}/…), not the type root");
        }
        if (url.contains("//")) {
            throw new IllegalArgumentException("target must not contain empty segments");
        }
        String bucketElement = isPlaceholder(segments[1]) ? "" : segments[1] + ResourceDescriptor.PATH_SEPARATOR;
        // factory validates segment structure (names, encoding) but not ownership; a placeholder bucket is opaque
        // at write time, so the url is re-parsed under the public bucket for the structural checks only
        parseTarget(() -> ResourceDescriptorFactory.fromAnyUrl(fakePublicUrl(segments[0], bucketElement,
                stripTypeAndBucket(url, segments)), null));
        return segments;
    }

    private static String fakePublicUrl(String type, String bucketElement, String rest) {
        return type + ResourceDescriptor.PATH_SEPARATOR + ResourceDescriptor.PUBLIC_LOCATION + bucketElement + rest;
    }

    /** Runs the factory call; its failure is reported as not a valid url. */
    private static ResourceDescriptor parseTarget(Supplier<ResourceDescriptor> factoryCall) {
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
        rejectWildcards(target);
        return target;
    }

    private static void rejectWildcards(ResourceDescriptor target) {
        List<String> elements = new ArrayList<>(target.getParentFolders());
        if (target.getName() != null) {
            elements.add(target.getName());
        }
        for (String element : elements) {
            if (element.contains("*")) {
                throw new IllegalArgumentException("wildcards are not allowed");
            }
        }
    }

    private static boolean isDeclarable(String type) {
        return DECLARABLE_GROUPS.contains(type);
    }

    private static boolean isPlaceholder(String segment) {
        return CURRENT_USER_PLACEHOLDER.equals(UrlUtil.tryDecodePath(segment));
    }

    private static String stripTypeAndBucket(String url, String[] segments) {
        return url.substring(segments[0].length() + segments[1].length() + 2);
    }

    private static String readTargetUrl(ResourceDependency dependency) {
        if (dependency.getTarget() == null || dependency.getTarget().getUrl() == null) {
            return null;
        }
        String url = dependency.getTarget().getUrl();
        return url.isEmpty() ? null : url;
    }
}
