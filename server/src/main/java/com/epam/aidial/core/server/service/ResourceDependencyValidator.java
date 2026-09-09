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

    public static final String CURRENT_USER_PLACEHOLDER = "{current-user}";

    /**
     * The resource types a declaration may address — segment 0 of {type}/{bucket}/{path…}, for
     * both the concrete and the placeholder form. Closed per Core version. ResourceTypes.of()
     * also maps internal engine types (credentials, keys, models, …); those are never declarable,
     * as a personal target or a concrete one. Enforced on both the write and the read side.
     */
    public static final Set<String> DECLARABLE_TYPE_ROOTS =
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
     * dependencies at all. With it on, no further per-path ceiling applies — the grammar itself already
     * requires segment 0 to be a declarable type (shape validation runs first, see ResourceController),
     * so a root-level "write everything personal" declaration is not expressible under
     * {type}/{bucket}/… at all. Admin-authored writes (public bucket by an admin, the platform bucket)
     * are not gated here.
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
    }

    /** Non-throwing form of {@link #validateShape}: the same rules, usable from any write surface. */
    public static List<String> shapeIssues(Application application) {
        List<String> issues = new ArrayList<>();
        List<ResourceDependency> section = application.getResourceDependencies();
        if (section == null || section.isEmpty()) {
            return issues;
        }
        boolean overCap = section.size() > MAX_DECLARED_DEPENDENCIES;
        if (overCap) {
            issues.add("resourceDependencies: the section exceeds " + MAX_DECLARED_DEPENDENCIES + " entries");
        }
        // Once over the cap the section is rejected anyway — inspect only the first MAX entries so a
        // huge body cannot turn validation itself into unbounded allocation.
        int inspected = Math.min(section.size(), MAX_DECLARED_DEPENDENCIES);
        Set<String> seenLinkIds = new HashSet<>();
        for (int i = 0; i < inspected; i++) {
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
        // A path of slashes only splits to zero segments; treat it as a missing path, not a crash.
        if (segments.length == 0) {
            issues.add(at + ": target.path is required");
            return issues;
        }
        String root = segments[0];
        // Root vocabulary — unconditional and first, no early return for either form. Deleting the old
        // placeholder early-return is deliberate: without this check running unconditionally,
        // credentials/{current-user}/… would silently bypass root-vocabulary validation at write time.
        if ("users".equals(root)) {
            // Personal targets are declared only via the placeholder — a concrete users/… path resolves for
            // no one but that user and is rejected at write time as a shape error.
            issues.add(at + ": personal targets are declared as {type}/" + CURRENT_USER_PLACEHOLDER
                    + "/…, not as a concrete users/… path: " + path);
        } else if (!DECLARABLE_TYPE_ROOTS.contains(root)) {
            issues.add(at + ": target must start with a declarable resource type "
                    + "(files, prompts, conversations, applications, toolsets, skills): " + path);
        } else if (segments.length < 2) {
            // A bare type root addresses the whole global view of that type — as over-broad as the
            // personal root the governance ceiling bans. Declarations must be folder- or file-scoped.
            // Applies to both forms: {type}/{bucket}/… is the minimum for either.
            issues.add(at + ": target must address a folder or resource within " + root + "/, not the type root: " + path);
        }
        // Token rules on every segment after the root, plus the placeholder-position rule covering
        // segment 0 as well — both issues are collected when the placeholder sits at segment 0, since
        // the root-vocabulary error above also fires there and two accurate messages beat one conditional.
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (CURRENT_USER_PLACEHOLDER.equals(segment) && i != 1) {
                issues.add(at + ": the " + CURRENT_USER_PLACEHOLDER
                        + " placeholder is valid only as the bucket segment (the second segment): " + path);
            }
            if (i == 0) {
                continue;
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
        return issues;
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
