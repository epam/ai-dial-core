package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.log.ResourceDependencyAuditLog;
import com.epam.aidial.core.server.service.ResourceDependencyValidator;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Bakes the called application's admin-consented resource dependencies into its per-request key, each only as far as
 * the initiator can reach the target on their own standing. Runs on every hop of a call chain.
 */
@Slf4j
public class ResolveResourceDependenciesFn<T> extends BaseRequestFunction<T> {

    private static final String UNDELIVERABLE_REASON = "dependency grants cannot be delivered: mcp.forwardPerRequestKey is false";
    private static final String UNDELIVERABLE_MESSAGE = "dependency grants cannot be delivered to this application";

    public ResolveResourceDependenciesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(T ignored) {
        if (!(context.getDeployment() instanceof Application application)) {
            return false;
        }
        List<ResourceDependency> declaration = application.getResourceDependencies();
        if (declaration == null || declaration.isEmpty()) {
            return false;
        }
        try {
            resolve(application, declaration);
        } catch (HttpException e) {
            throw e;
        } catch (RuntimeException e) {
            // the MCP proxy would answer any other exception with 400
            log.error("Failed to resolve resource dependencies of {}", application.getName(), e);
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to resolve resource dependencies", e);
        }
        return false;
    }

    private void resolve(Application application, List<ResourceDependency> declaration) {
        String applicationId = application.getName();
        // null only for an MCP application with forwardPerRequestKey=false
        boolean hasKeyToBakeInto = context.getProxyApiKeyData() != null;
        boolean consented = hasKeyToBakeInto && proxy.getAdminConsentService().isAdminConsented(applicationId, declaration);
        Resolution resolution = consented ? resolveConsented(declaration) : Resolution.unresolved(declaration);

        ResourceDependencyAuditLog.grant(context, applicationId, Consent.toResourceEntries(resolution.granted()));
        ResourceDependencyAuditLog.denial(context, applicationId, Consent.toResourceEntries(resolution.unresolved()));
        List<String> requiredFailures = requiredUrlsOf(resolution.unresolved());
        if (!requiredFailures.isEmpty()) {
            ResourceDependencyAuditLog.runtimeFail(context, applicationId, requiredFailures,
                    hasKeyToBakeInto ? "required dependencies unresolvable" : UNDELIVERABLE_REASON);
            String message = "Required resource dependencies are not accessible: " + String.join(", ", requiredFailures);
            throw new HttpException(HttpStatus.FORBIDDEN, hasKeyToBakeInto ? message : message + " — " + UNDELIVERABLE_MESSAGE);
        }
    }

    private Resolution resolveConsented(List<ResourceDependency> declaration) {
        AuthBucket userBucket = BucketBuilder.buildBucket(context);
        List<ResolvedTarget> resolvedTargets = new ArrayList<>();
        List<ResourceDependency> malformed = new ArrayList<>();
        for (ResourceDependency dependency : declaration) {
            ResolvedTarget resolved = resolveEntry(dependency, userBucket);
            if (resolved != null) {
                resolvedTargets.add(resolved);
            } else if (dependency != null) {
                malformed.add(dependency);
            }
        }
        Resolution reachable = bakeReachableGrants(resolvedTargets);
        return new Resolution(reachable.granted(), Stream.concat(malformed.stream(), reachable.unresolved().stream()).toList());
    }

    @Nullable
    private ResolvedTarget resolveEntry(@Nullable ResourceDependency dependency, AuthBucket userBucket) {
        Set<ResourceAccessType> requestedAccess = ResourceDependencyValidator.grantableAccessOf(dependency);
        if (requestedAccess == null) {
            return null;
        }
        ResourceDescriptor target = resolveTarget(dependency, userBucket);
        if (target == null || !ResourceDependencyValidator.isAllowedAccess(target.getType(), requestedAccess)) {
            return null;
        }
        return new ResolvedTarget(dependency, target, requestedAccess);
    }

    /**
     * Bakes the targets the initiator can reach with the declared rights; the rest are unresolved.
     */
    private Resolution bakeReachableGrants(List<ResolvedTarget> resolvedTargets) {
        if (resolvedTargets.isEmpty()) {
            return new Resolution(List.of(), List.of());
        }
        Set<ResourceDescriptor> targets = resolvedTargets.stream().map(ResolvedTarget::target).collect(Collectors.toSet());
        Map<ResourceDescriptor, Set<ResourceAccessType>> initiatorAccessByTarget =
                proxy.getAccessService().lookupInitiatorPermissions(targets, context);
        List<ResourceDependency> granted = new ArrayList<>();
        List<ResourceDependency> unreachable = new ArrayList<>();
        for (ResolvedTarget resolved : resolvedTargets) {
            Set<ResourceAccessType> initiatorAccess = initiatorAccessByTarget.getOrDefault(resolved.target(), Set.of());
            if (initiatorAccess.containsAll(resolved.access())) {
                bakeGrant(resolved.target(), resolved.access());
                granted.add(resolved.dependency());
            } else {
                unreachable.add(resolved.dependency());
            }
        }
        return new Resolution(granted, unreachable);
    }

    private record ResolvedTarget(ResourceDependency dependency, ResourceDescriptor target, Set<ResourceAccessType> access) {
    }

    private record Resolution(List<ResourceDependency> granted, List<ResourceDependency> unresolved) {
        static Resolution unresolved(List<ResourceDependency> declaration) {
            return new Resolution(List.of(), declaration.stream().filter(Objects::nonNull).toList());
        }
    }

    private void bakeGrant(ResourceDescriptor target, Set<ResourceAccessType> requestedAccess) {
        // copy-on-write: the entry may be shared with the calling key's receivers
        context.getProxyApiKeyData().getPerRequestSharedResources().merge(target.getUrl(),
                new PerRequestSharedData(new HashSet<>(requestedAccess)),
                (existing, added) -> {
                    Set<ResourceAccessType> union = new HashSet<>(existing.permissions());
                    union.addAll(added.permissions());
                    return new PerRequestSharedData(union);
                });
    }

    private static List<String> requiredUrlsOf(List<ResourceDependency> unresolved) {
        return unresolved.stream()
                .filter(ResourceDependency::isRequired)
                .map(dependency -> dependency.getTarget() == null || dependency.getTarget().getUrl() == null
                        ? "<missing target.url>" : dependency.getTarget().getUrl())
                .toList();
    }

    /**
     * Resolves a declared target url to a descriptor: a {@code {type}/{current-user}/<path…>}
     * url against the initiator's own bucket, a concrete {@code {type}/{bucket}/<path…>}
     * url as-is. Null when the record is malformed — config-file apps bypass write-time validation.
     */
    @Nullable
    private ResourceDescriptor resolveTarget(ResourceDependency dependency, AuthBucket userBucket) {
        if (dependency.getTarget() == null || dependency.getTarget().getUrl() == null) {
            return null;
        }
        try {
            return ResourceDependencyValidator.resolveTarget(dependency.getTarget().getUrl(), userBucket, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
