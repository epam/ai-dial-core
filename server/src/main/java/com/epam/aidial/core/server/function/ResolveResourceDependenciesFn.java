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
 * <p><b>Every hop, one rule.</b> Resolution runs identically on the root user call and on any
 * chained or interceptor hop. Identity is not the obstacle it was once thought to be:
 * {@code ApiKeyData.initFromContext} propagates {@code extractedClaims} and {@code originalKey}
 * at every depth, so {@code ProxyContext.userId} is always the originating human — which is why
 * placeholder targets resolve correctly through {@code BucketBuilder.buildInitiatorBucket} at
 * any depth. Only the reach check needed care: the general permission chain's own-bucket rule
 * deliberately flips to the per-request key holder's sandbox, so reach is evaluated through
 * {@code AccessService.lookupOriginatingUserPermissions} — own bucket as the human, shared,
 * public — and never through rules that read the calling key's own grants (D-24).
 */
public class ResolveResourceDependenciesFn<T> extends BaseRequestFunction<T> {

    public ResolveResourceDependenciesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    /**
     * The request body is not an input here — resolution reads only {@code context}. The type
     * parameter exists solely so this function can join chains of either element type
     * ({@code RequestObject} on the conversation mint sites, {@code ObjectNode} on the MCP
     * proxy); see D-25a.
     */
    @Override
    public Boolean apply(T ignored) {
        if (!(context.getDeployment() instanceof Application application)) {
            // Interceptor hop or a non-application deployment — dependencies resolve for the
            // application being called, nothing else.
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
        // Nowhere to bake a grant into — e.g. an MCP application with forwardPerRequestKey
        // disabled never gets a per-request key at all, so it can never receive one regardless of
        // consent or reach. Treated exactly like "not consented": every declared dependency is
        // unresolved, so a required one still hard-fails the call instead of silently succeeding
        // for an app that was promised access it can never actually receive.
        boolean hasKeyToBakeInto = context.getProxyApiKeyData() != null;
        // The consent record is content-bound to the whole declaration: any change since the grant
        // re-requires it, and until then nothing resolves. Checked before any resolution work.
        boolean consented = hasKeyToBakeInto && proxy.getConsentService().isAdminConsented(applicationId, declaration);
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
                    proxy.getAccessService().lookupOriginatingUserPermissions(targets, context);
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
