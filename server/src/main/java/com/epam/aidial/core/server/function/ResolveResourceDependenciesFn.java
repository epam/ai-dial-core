package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.log.ResourceDependencyAuditLog;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ResourceDependencyValidator;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Request-start resolution of the called application's declared resource dependencies (design
 * §7.1): resolve each declared target, verify it fresh against the originating user's reach,
 * intersect with the content-bound admin-consent record, and bake the passing grants into the
 * per-request key the application will hold — the app never asks for a credential; the key it
 * already holds gets richer. A record is a request, not a grant: nothing here widens anything
 * the user cannot already reach.
 *
 * <p><b>Root-call only.</b> Resolution runs when the context still carries the originating user
 * (no per-request key) — that is the only state in which the user's reach is directly
 * evaluable; under a per-request key the same checks would silently evaluate a deployment's own
 * key instead. Hops that arrive with a per-request key present — an interceptor's final call
 * back to the app, or a chained app-to-app call — are skipped: their declarations do not
 * resolve and their grants do not propagate in v1 (documented limitation; chained composition
 * needs originating-user evaluation under key contexts, which is phase-2 machinery).
 */
public class ResolveResourceDependenciesFn extends BaseRequestFunction<RequestObject> {

    public ResolveResourceDependenciesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        if (!(context.getDeployment() instanceof Application application)) {
            // Interceptor hop or a non-application deployment — dependencies resolve for the
            // application being called, nothing else.
            return false;
        }
        List<ResourceDependency> declaration = application.getResourceDependencies();
        if (declaration == null || declaration.isEmpty()) {
            return false;
        }
        if (context.getApiKeyData().getPerRequestKey() != null) {
            // Not the root user call — see the class javadoc. Skip, never throw: a declaring app
            // behind an interceptor or in a chain must stay callable, just without grants.
            return false;
        }
        resolve(application, declaration);
        return false;
    }

    private void resolve(Application application, List<ResourceDependency> declaration) {
        String applicationId = application.getName();
        // The consent record is content-bound to the whole declaration: any change since the grant
        // re-requires it, and until then nothing resolves. Checked before any resolution work.
        boolean consented = proxy.getConsentService().isAdminConsented(applicationId, declaration);
        AuthBucket userBucket = BucketBuilder.buildBucket(context);

        List<Resolved> resolvedTargets = new ArrayList<>();
        List<ResourceDependency> unresolvedDeps = new ArrayList<>();
        List<String> requiredFailures = new ArrayList<>();

        for (ResourceDependency dependency : declaration) {
            Set<ResourceAccessType> requestedAccess = consented ? requestedAccessOf(dependency) : null;
            ResourceDescriptor target = requestedAccess == null ? null : resolveTarget(dependency, userBucket);
            if (target == null) {
                // Fail closed per record: malformed (config-file apps bypass write-time validation)
                // or unconsented — no grant, no failure, unless required.
                trackUnresolved(dependency, unresolvedDeps, requiredFailures);
            } else {
                resolvedTargets.add(new Resolved(dependency, target, requestedAccess));
            }
        }

        List<Consent.ResourceEntry> granted = new ArrayList<>();
        if (!resolvedTargets.isEmpty()) {
            // One batched walk of the permission chain for all resolved targets.
            Set<ResourceDescriptor> targets = resolvedTargets.stream().map(Resolved::target).collect(Collectors.toSet());
            Map<ResourceDescriptor, Set<ResourceAccessType>> userAccessByTarget =
                    proxy.getAccessService().lookupPermissions(targets, context);
            for (Resolved resolved : resolvedTargets) {
                Set<ResourceAccessType> userAccess = userAccessByTarget.getOrDefault(resolved.target(), Set.of());
                if (userAccess.containsAll(resolved.access())) {
                    bakeGrant(resolved.target(), resolved.access());
                    granted.add(entryOf(resolved.dependency()));
                } else {
                    // The user cannot reach the target with the declared rights — the record simply
                    // does not grant; it does not fail the call unless required.
                    trackUnresolved(resolved.dependency(), unresolvedDeps, requiredFailures);
                }
            }
        }

        ResourceDependencyAuditLog.grant(context, applicationId, granted);
        ResourceDependencyAuditLog.denial(context, applicationId, entriesOf(unresolvedDeps));
        if (!requiredFailures.isEmpty()) {
            // A required dependency is unresolvable — the application never half-works silently.
            ResourceDependencyAuditLog.runtimeFail(context, applicationId, requiredFailures);
            throw new HttpException(HttpStatus.FORBIDDEN,
                    "Required resource dependencies are not accessible: " + String.join(", ", requiredFailures));
        }
    }

    private record Resolved(ResourceDependency dependency, ResourceDescriptor target, Set<ResourceAccessType> access) {
    }

    private void bakeGrant(ResourceDescriptor target, Set<ResourceAccessType> requestedAccess) {
        // Union semantics, like every other grant writer: two records targeting the same URL
        // with different rights must combine, not overwrite each other.
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyApiKeyData.getPerRequestSharedResources()
                .computeIfAbsent(target.getUrl(), key -> new PerRequestSharedData(new HashSet<>()))
                .permissions().addAll(requestedAccess);
    }

    private static void trackUnresolved(@Nullable ResourceDependency dependency,
                                        List<ResourceDependency> unresolvedDeps, List<String> requiredFailures) {
        if (dependency == null) {
            return;
        }
        String path = dependency.getTarget() == null ? null : dependency.getTarget().getPath();
        if (dependency.isRequired()) {
            requiredFailures.add(path == null ? "<missing target.path>" : path);
        }
        unresolvedDeps.add(dependency);
    }

    private static List<Consent.ResourceEntry> entriesOf(List<ResourceDependency> dependencies) {
        List<Consent.ResourceEntry> entries = new ArrayList<>(dependencies.size());
        for (ResourceDependency dependency : dependencies) {
            entries.add(entryOf(dependency));
        }
        return entries;
    }

    private static Consent.ResourceEntry entryOf(ResourceDependency dependency) {
        Consent.ResourceEntry entry = new Consent.ResourceEntry();
        if (dependency.getTarget() != null) {
            entry.setUrl(dependency.getTarget().getPath());
        }
        if (dependency.getAccess() != null) {
            entry.setAccess(new HashSet<>(dependency.getAccess()));
        }
        return entry;
    }

    /** The dependency's requested rights, or null when the record cannot be granted at all. */
    @Nullable
    private static Set<ResourceAccessType> requestedAccessOf(@Nullable ResourceDependency dependency) {
        if (dependency == null || !ResourceDependency.KIND.equals(dependency.getKind())) {
            // Wrong or missing kind is not a resource link — unresolvable (config-file apps bypass
            // write-time validation, so the read side enforces the same vocabulary).
            return null;
        }
        if (dependency.getAccess() == null || dependency.getAccess().isEmpty()) {
            return null;
        }
        // Only READ and WRITE are dependency rights; anything else (SHARE, future vocabulary that
        // bypassed write-time validation) makes the record unresolvable.
        for (ResourceAccessType access : dependency.getAccess()) {
            if (access != ResourceAccessType.READ && access != ResourceAccessType.WRITE) {
                return null;
            }
        }
        return Set.copyOf(dependency.getAccess());
    }

    /**
     * Resolves a declared target path to a descriptor: a {@code {type}/{current-user}/<path…>}
     * path against the originating user's own bucket, a concrete {@code {type}/{bucket}/<path…>}
     * path as-is. Null when the record is malformed — an unresolvable record, never a crash.
     */
    @Nullable
    private ResourceDescriptor resolveTarget(ResourceDependency dependency, AuthBucket userBucket) {
        if (dependency.getTarget() == null || dependency.getTarget().getPath() == null) {
            return null;
        }
        String path = dependency.getTarget().getPath().trim();
        if (path.isEmpty()) {
            return null;
        }
        boolean folder = path.endsWith("/");
        try {
            String[] segments = decodedSegments(path, folder);
            if (segments.length < 2) {
                return null;                                   // {type}/{bucket} is the minimum
            }
            if (!ResourceDependencyValidator.DECLARABLE_TYPE_ROOTS.contains(segments[0])) {
                // ResourceTypes.of() also maps internal engine types (credentials, keys, models, …).
                // Config-file apps bypass write-time validation, so the read side enforces the same
                // closed vocabulary — for concrete paths too, not only placeholder ones.
                return null;
            }
            if (ResourceDependencyValidator.CURRENT_USER_PLACEHOLDER.equals(segments[1])) {
                // {type}/{current-user}/{path…}. Two segments target the type's root folder in the
                // user's bucket — the skill-creator shape.
                ResourceType type = ResourceTypes.of(segments[0]);
                String relativePath = segments.length == 2
                        ? "" : String.join("/", Arrays.asList(segments).subList(2, segments.length));
                if (folder && !relativePath.isEmpty()) {
                    relativePath += "/";
                }
                return ResourceDescriptorFactory.fromDecoded(
                        type, userBucket.getUserBucket(), userBucket.getUserBucketLocation(), relativePath);
            }
            return ResourceDescriptorFactory.fromAnyUrl(path, proxy.getEncryptionService());
        } catch (RuntimeException e) {
            // fromAnyUrl wraps URISyntaxException in a plain RuntimeException — malformed means
            // unresolvable, whatever the exception shape.
            return null;
        }
    }

    private static String[] decodedSegments(String path, boolean folder) {
        String trimmed = folder ? path.substring(0, path.length() - 1) : path;
        return Arrays.stream(trimmed.split("/")).map(UrlUtil::tryDecodePath).toArray(String[]::new);
    }
}
