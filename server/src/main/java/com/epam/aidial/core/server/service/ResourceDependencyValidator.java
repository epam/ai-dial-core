package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public static final String CURRENT_USER_PLACEHOLDER = "current-user";

    /** Global-view roots a concrete path may address. The personal root is reachable only via the placeholder. */
    private static final Set<String> GLOBAL_VIEW_ROOTS =
            Set.of("files", "public", "prompts", "conversations", "applications", "toolsets", "skills");

    /**
     * Resource-type folders a {@code current-user/…} path must be rooted in for user-authored apps —
     * a root-level {@code current-user/} declaration ("write everything personal") is not declarable.
     */
    private static final Set<String> PERSONAL_TYPED_ROOTS =
            Set.of("files", "prompts", "conversations", "applications", "toolsets", "skills");

    private final boolean allowUserResourceDependencies;

    /** Throws on the first shape violation. Applied on every writer surface regardless of author. */
    public void validateShape(Application application) {
        List<String> issues = shapeIssues(application);
        if (!issues.isEmpty()) {
            throw new HttpException(BAD_REQUEST, "Invalid resource dependencies: " + String.join("; ", issues));
        }
    }

    /**
     * Governance ceiling for user-authored apps: with the flag off (the default) they may not declare
     * dependencies at all; with it on, personal targets must be typed — never the personal root.
     * Admin-authored writes (public bucket by an admin, the platform bucket) are not gated here.
     */
    public void validateUserAuthored(Application application) {
        List<ResourceDependency> section = application.getResourceDependencies();
        if (section == null || section.isEmpty()) {
            return;
        }
        if (!allowUserResourceDependencies) {
            throw new HttpException(FORBIDDEN,
                    "User-authored applications may not declare resource dependencies (allowUserResourceDependencies is disabled)");
        }
        for (ResourceDependency dependency : section) {
            String path = pathOf(dependency);
            if (path == null) {
                continue;
            }
            String[] segments = decodedSegments(path);
            if (CURRENT_USER_PLACEHOLDER.equals(segments[0]) && !isTypedPersonalPath(segments)) {
                throw new HttpException(FORBIDDEN, "Root-level current-user dependency is not declarable: "
                        + "personal targets must be rooted in a resource-type folder: " + path);
            }
        }
    }

    /** Non-throwing form of {@link #validateShape}, so the lazy read-side validator can reuse the same rules. */
    public static List<String> shapeIssues(Application application) {
        List<String> issues = new ArrayList<>();
        List<ResourceDependency> section = application.getResourceDependencies();
        if (section == null || section.isEmpty()) {
            return issues;
        }
        if (section.size() > MAX_DECLARED_DEPENDENCIES) {
            issues.add("resourceDependencies: the section exceeds " + MAX_DECLARED_DEPENDENCIES + " entries");
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
        List<String> issues = new ArrayList<>();
        String path = pathOf(dependency);
        if (path == null) {
            issues.add(at + ": target.path is required");
            return issues;
        }
        // Token rules run on decoded segments, mirroring ResourceDescriptorFactory's single tryDecodePath
        // pass — the platform canonicalizes declared paths through that decode, so validating the raw
        // string would let %2e%2e / %2a / %63urrent-user smuggle banned tokens past the bans.
        String[] segments = decodedSegments(path);
        String root = segments[0];
        // Token rules on every segment after the root; the root itself is governed by the form checks below.
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (CURRENT_USER_PLACEHOLDER.equals(segment)) {
                issues.add(at + ": the current-user placeholder is valid only as the root segment: " + path);
            }
            if (segment.isEmpty()) {
                issues.add(at + ": path must not contain empty segments: " + path);
            }
            if (segment.contains("*")) {
                issues.add(at + ": wildcards are not allowed: " + path);
            }
            if (".".equals(segment) || "..".equals(segment)) {
                issues.add(at + ": relative path segments are not allowed: " + path);
            }
        }
        if (CURRENT_USER_PLACEHOLDER.equals(root)) {
            // Placeholder-rooted form; the typed-root restriction is the governance ceiling's, not shape's.
            return issues;
        }
        if ("users".equals(root)) {
            // Personal targets are declared only via the placeholder — a concrete users/… path resolves for
            // no one but that user and is rejected at write time as a shape error.
            issues.add(at + ": personal targets must use the current-user placeholder, not a concrete users/… path: " + path);
        } else if (!GLOBAL_VIEW_ROOTS.contains(root)) {
            issues.add(at + ": target must be a global-view path or current-user rooted: " + path);
        }
        return issues;
    }

    private static boolean isTypedPersonalPath(String[] segments) {
        return segments.length > 1 && PERSONAL_TYPED_ROOTS.contains(segments[1]);
    }

    private static String pathOf(ResourceDependency dependency) {
        if (dependency.getTarget() == null || dependency.getTarget().getPath() == null) {
            return null;
        }
        String path = dependency.getTarget().getPath().trim();
        return path.isEmpty() ? null : path;
    }

    /** Splits off a single trailing slash (folder targets end with one) before splitting into segments. */
    private static String[] splitPath(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.split("/");
    }

    private static String[] decodedSegments(String path) {
        return Arrays.stream(splitPath(path)).map(UrlUtil::tryDecodePath).toArray(String[]::new);
    }
}
