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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Request-start resolution of the application's declared resource dependencies (design §7.1):
 * resolve each declared target, verify it fresh against the originating user's reach, intersect
 * with the admin-consented set, and bake the passing grants into the per-request key the
 * application will hold — the app never asks for a credential; the key it already holds gets
 * richer. A record is a request, not a grant: nothing here widens anything the user cannot
 * already reach.
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
        // Load-bearing timing: this function runs in the enhancement chain BEFORE the per-request key
        // is assigned, so the reach checks below evaluate the originating user's permissions. After
        // assignment the same calls would silently evaluate the app's own key instead — make that
        // drift loud instead of silent.
        if (context.getApiKeyData().getPerRequestKey() != null) {
            throw new IllegalStateException(
                    "Resource dependencies must be resolved before the per-request key is assigned");
        }
        resolve(application, declaration);
        return false;
    }

    private void resolve(Application application, List<ResourceDependency> declaration) {
        String applicationId = application.getName();
        // The consent record is content-bound to the whole declaration: any change since the grant
        // re-requires it, and until then nothing resolves.
        boolean consented = proxy.getConsentService().isAdminConsented(applicationId, declaration);
        AccessService accessService = proxy.getAccessService();
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        AuthBucket userBucket = BucketBuilder.buildBucket(context);

        List<Consent.ResourceEntry> granted = new ArrayList<>();
        List<Consent.ResourceEntry> unresolved = new ArrayList<>();
        List<String> requiredFailures = new ArrayList<>();

        for (ResourceDependency dependency : declaration) {
            Set<ResourceAccessType> requestedAccess = requestedAccessOf(dependency);
            ResourceDescriptor target = requestedAccess == null ? null : resolveTarget(dependency, userBucket);
            if (!consented || target == null) {
                // Fail closed per record: unconsented, malformed (config-file apps bypass write-time
                // validation) — no grant, no failure, unless required.
                trackUnresolved(dependency, target, unresolved, requiredFailures);
                continue;
            }
            Set<ResourceAccessType> userAccess =
                    accessService.lookupPermissions(Set.of(target), context).getOrDefault(target, Set.of());
            if (userAccess.containsAll(requestedAccess)) {
                // Both halves of the delivery: perRequestSharedResources serves the application's own
                // direct calls with this key (the access rule reads the presented key's shared map);
                // perRequestReceivers[app] carries the grants to every descendant mint down a chained
                // call — ApiKeyData.initFromContext always shares the initial deployment's receiver
                // entry into each child key.
                proxyApiKeyData.getPerRequestSharedResources()
                        .put(target.getUrl(), new PerRequestSharedData(requestedAccess));
                proxyApiKeyData.getPerRequestReceivers()
                        .computeIfAbsent(applicationId, key -> new HashMap<>())
                        .put(target.getUrl(), new PerRequestSharedData(requestedAccess));
                granted.add(entryOf(dependency));
            } else {
                // The user cannot reach the target with the declared rights — the record simply does
                // not grant; it does not fail the call unless required.
                trackUnresolved(dependency, target, unresolved, requiredFailures);
            }
        }

        ResourceDependencyAuditLog.grant(context, applicationId, granted);
        ResourceDependencyAuditLog.denial(context, applicationId, unresolved);
        if (!requiredFailures.isEmpty()) {
            // A required dependency is unresolvable — the application never half-works silently.
            ResourceDependencyAuditLog.runtimeFail(context, applicationId, requiredFailures);
            throw new HttpException(HttpStatus.FORBIDDEN,
                    "Required resource dependencies are not accessible: " + String.join(", ", requiredFailures));
        }
    }

    private static void trackUnresolved(ResourceDependency dependency, @Nullable ResourceDescriptor target,
                                         List<Consent.ResourceEntry> unresolved, List<String> requiredFailures) {
        String path = dependency == null || dependency.getTarget() == null ? null : dependency.getTarget().getPath();
        if (dependency != null && dependency.isRequired()) {
            requiredFailures.add(path == null ? "<missing target.path>" : path);
        }
        unresolved.add(entryOf(dependency));
    }

    private static Consent.ResourceEntry entryOf(@Nullable ResourceDependency dependency) {
        Consent.ResourceEntry entry = new Consent.ResourceEntry();
        if (dependency != null && dependency.getTarget() != null) {
            entry.setUrl(dependency.getTarget().getPath());
        }
        if (dependency != null && dependency.getAccess() != null) {
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
     * Resolves a declared target path to a descriptor: a {@code current-user/…} path against the
     * originating user's own bucket, a concrete global-view path as-is. Null when the record is
     * malformed — an unresolvable record, never a crash.
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
            if (segments.length == 0) {
                return null;
            }
            if (ResourceDependencyValidator.CURRENT_USER_PLACEHOLDER.equals(segments[0])) {
                // current-user/<type-segment>/<path…> — the type segment names the target's resource
                // type, the rest is the path inside the user's bucket. Two segments (e.g.
                // current-user/skills/) target the type's root folder in the user's bucket.
                if (segments.length < 2) {
                    return null;
                }
                ResourceType type = ResourceTypes.of(segments[1]);
                String relativePath = segments.length == 2
                        ? "" : String.join("/", Arrays.asList(segments).subList(2, segments.length));
                if (folder && !relativePath.isEmpty()) {
                    relativePath += "/";
                }
                return ResourceDescriptorFactory.fromDecoded(
                        type, userBucket.getUserBucket(), userBucket.getUserBucketLocation(), relativePath);
            }
            return ResourceDescriptorFactory.fromAnyUrl(path, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String[] decodedSegments(String path, boolean folder) {
        String trimmed = folder ? path.substring(0, path.length() - 1) : path;
        return Arrays.stream(trimmed.split("/")).map(UrlUtil::tryDecodePath).toArray(String[]::new);
    }
}
