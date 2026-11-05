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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Request-start resolution of the called application's declared resource dependencies: resolve
 * each declared target, verify it against the request initiator's reach, intersect with the
 * admin-consent record, and bake the passing grants into the per-request key the application will
 * hold. A record is a request, not a grant: nothing here widens anything the user cannot reach.
 *
 * <p>Runs on every hop of a call chain; reach is evaluated by
 * {@code AccessService.lookupInitiatorPermissions}, never against the calling key's own grants.
 */
public class ResolveResourceDependenciesFn<T> extends BaseRequestFunction<T> {

    private static final String UNDELIVERABLE_REASON = "dependency grants cannot be delivered: mcp.forwardPerRequestKey is false";
    private static final String UNDELIVERABLE_MESSAGE = "dependency grants cannot be delivered to this application";

    public ResolveResourceDependenciesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    /**
     * The argument is unused: resolution reads only {@code context}, so the function fits chains of any element type.
     */
    @Override
    public Boolean apply(T ignored) {
        if (!(context.getDeployment() instanceof Application application)) {
            return false;
        }
        List<ResourceDependency> declaration = application.getResourceDependencies();
        if (declaration == null || declaration.isEmpty()) {
            return false;
        }
        resolve(application, declaration);
        return false;
    }

    private void resolve(Application application, List<ResourceDependency> declaration) {
        String applicationId = application.getName();
        // only an MCP app with forwardPerRequestKey=false gets no per-request key: grants can't be delivered, a required dependency fails
        boolean hasKeyToBakeInto = context.getProxyApiKeyData() != null;
        boolean consented = hasKeyToBakeInto && proxy.getConsentService().isAdminConsented(applicationId, declaration);
        AuthBucket userBucket = BucketBuilder.buildBucket(context);

        List<ResolvedTarget> resolvedTargets = new ArrayList<>();
        List<ResourceDependency> unresolvedDeps = new ArrayList<>();
        List<String> requiredFailures = new ArrayList<>();

        for (ResourceDependency dependency : declaration) {
            Set<ResourceAccessType> requestedAccess = consented ? requestedAccessOf(dependency) : null;
            ResourceDescriptor target = requestedAccess == null ? null : resolveTarget(dependency, userBucket);
            if (target == null) {
                trackUnresolved(dependency, unresolvedDeps, requiredFailures);
            } else {
                resolvedTargets.add(new ResolvedTarget(dependency, target, requestedAccess));
            }
        }

        List<ResourceDependency> granted = bakeReachableGrants(resolvedTargets, unresolvedDeps, requiredFailures);

        ResourceDependencyAuditLog.grant(context, applicationId, Consent.toResourceEntries(granted));
        ResourceDependencyAuditLog.denial(context, applicationId, Consent.toResourceEntries(unresolvedDeps));
        if (!requiredFailures.isEmpty()) {
            ResourceDependencyAuditLog.runtimeFail(context, applicationId, requiredFailures,
                    hasKeyToBakeInto ? "required dependencies unresolvable" : UNDELIVERABLE_REASON);
            String message = "Required resource dependencies are not accessible: " + String.join(", ", requiredFailures);
            throw new HttpException(HttpStatus.FORBIDDEN, hasKeyToBakeInto ? message : message + " — " + UNDELIVERABLE_MESSAGE);
        }
    }

    /**
     * Bakes the targets the initiator can reach with the declared rights; tracks the rest as unresolved.
     */
    private List<ResourceDependency> bakeReachableGrants(List<ResolvedTarget> resolvedTargets,
                                                         List<ResourceDependency> unresolvedDeps,
                                                         List<String> requiredFailures) {
        List<ResourceDependency> granted = new ArrayList<>();
        if (resolvedTargets.isEmpty()) {
            return granted;
        }
        Set<ResourceDescriptor> targets = resolvedTargets.stream().map(ResolvedTarget::target).collect(Collectors.toSet());
        Map<ResourceDescriptor, Set<ResourceAccessType>> initiatorAccessByTarget =
                proxy.getAccessService().lookupInitiatorPermissions(targets, context);
        for (ResolvedTarget resolved : resolvedTargets) {
            Set<ResourceAccessType> initiatorAccess = initiatorAccessByTarget.getOrDefault(resolved.target(), Set.of());
            if (initiatorAccess.containsAll(resolved.access())) {
                bakeGrant(resolved.target(), resolved.access());
                granted.add(resolved.dependency());
            } else {
                trackUnresolved(resolved.dependency(), unresolvedDeps, requiredFailures);
            }
        }
        return granted;
    }

    private record ResolvedTarget(ResourceDependency dependency, ResourceDescriptor target, Set<ResourceAccessType> access) {
    }

    private void bakeGrant(ResourceDescriptor target, Set<ResourceAccessType> requestedAccess) {
        // Union with any existing entry, copy-on-write: the entry may be shared with the calling key's receivers
        context.getProxyApiKeyData().getPerRequestSharedResources().merge(target.getUrl(),
                new PerRequestSharedData(new HashSet<>(requestedAccess)),
                (existing, added) -> {
                    Set<ResourceAccessType> union = new HashSet<>(existing.permissions());
                    union.addAll(added.permissions());
                    return new PerRequestSharedData(union);
                });
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

    /** The dependency's requested rights, or null when the record cannot be granted at all. */
    @Nullable
    private static Set<ResourceAccessType> requestedAccessOf(@Nullable ResourceDependency dependency) {
        if (dependency == null || !ResourceDependency.KIND.equals(dependency.getKind())) {
            return null;
        }
        if (dependency.getAccess() == null || dependency.getAccess().isEmpty()) {
            return null;
        }
        for (ResourceAccessType access : dependency.getAccess()) {
            if (access != ResourceAccessType.READ && access != ResourceAccessType.WRITE) {
                return null;
            }
        }
        return Set.copyOf(dependency.getAccess());
    }

    /**
     * Resolves a declared target path to a descriptor: a {@code {type}/{current-user}/<path…>}
     * path against the initiator's own bucket, a concrete {@code {type}/{bucket}/<path…>}
     * path as-is. Null when the record is malformed — config-file apps bypass write-time validation.
     */
    @Nullable
    private ResourceDescriptor resolveTarget(ResourceDependency dependency, AuthBucket userBucket) {
        if (dependency.getTarget() == null || dependency.getTarget().getPath() == null) {
            return null;
        }
        try {
            return ResourceDependencyValidator.resolveTarget(dependency.getTarget().getPath().trim(), userBucket, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
