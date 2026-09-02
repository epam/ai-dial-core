package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Distinct audit stream for resource-dependency events (admin consent decisions, and the
 * request-start grant/denial/runtime-fail outcomes that land with the resolver). Kept separate
 * from the OBO stream so operators can route/retain it independently. Never logs credential
 * material or resource content — only identities, targets and outcomes.
 */
public final class ResourceDependencyAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("DIAL_RESOURCE_DEPS_AUDIT");

    private ResourceDependencyAuditLog() {
    }

    /**
     * One event per administrator decision on an application's declared resource dependencies.
     * {@code declaration} is the snapshot in force for the decision — the current declaration on
     * grant, the withdrawn record on withdraw — so the audit line always carries what was approved.
     */
    public static void consent(ProxyContext context, String applicationId, String action,
                               List<Consent.ResourceEntry> declaration, RuntimeException error) {
        String targets = declaration == null ? "" : targetsOf(declaration);
        String accessTypes = declaration == null ? "" : accessTypesOf(declaration);
        AUDIT.info("event=resource_dependency_consent action={} outcome={} actor={} admin_user_id={} "
                        + "application_id={} targets={} access_types={} trace_id={}{}",
                AuditLogSanitizer.sanitizeToken(action), outcomeOf(error), actorEvidence(context),
                AuditLogSanitizer.sanitizeToken(context.getUserId()), AuditLogSanitizer.sanitizeToken(applicationId),
                targets, accessTypes, context.getTraceId(), AuditLogSanitizer.reasonOf(error));
    }

    /** One event per run whose declared dependencies resolved into grants, listing what was granted. */
    public static void grant(ProxyContext context, String applicationId, List<Consent.ResourceEntry> granted) {
        if (granted.isEmpty()) {
            return;
        }
        AUDIT.info("event=resource_dependency_grant outcome=SUCCESS actor={} user_id={} application_id={} "
                        + "targets={} access_types={} trace_id={}",
                actorEvidence(context), AuditLogSanitizer.sanitizeToken(context.getUserId()), AuditLogSanitizer.sanitizeToken(applicationId),
                targetsOf(granted), accessTypesOf(granted), context.getTraceId());
    }

    /**
     * One event per run listing declared targets that did not resolve — the originating user
     * cannot reach them with the declared rights, or the declaration is not admin-consented.
     * No grant, no failure (unless a required record failed — see {@link #runtimeFail}).
     */
    public static void denial(ProxyContext context, String applicationId, List<Consent.ResourceEntry> unresolved) {
        if (unresolved.isEmpty()) {
            return;
        }
        AUDIT.info("event=resource_dependency_denial outcome=DENIED actor={} user_id={} application_id={} "
                        + "targets={} trace_id={}",
                actorEvidence(context), AuditLogSanitizer.sanitizeToken(context.getUserId()), AuditLogSanitizer.sanitizeToken(applicationId),
                targetsOf(unresolved), context.getTraceId());
    }

    /** One event when a required dependency failed to resolve and the call is rejected. */
    public static void runtimeFail(ProxyContext context, String applicationId, List<String> requiredFailures) {
        AUDIT.info("event=resource_dependency_runtime_fail outcome=RUNTIME_FAIL actor={} user_id={} "
                        + "application_id={} targets={} trace_id={} reason=\"required dependencies unresolvable\"",
                actorEvidence(context), AuditLogSanitizer.sanitizeToken(context.getUserId()), AuditLogSanitizer.sanitizeToken(applicationId),
                requiredFailures.stream().map(AuditLogSanitizer::sanitizeToken)
                        .collect(Collectors.joining(",")),
                context.getTraceId());
    }

    private static String targetsOf(List<Consent.ResourceEntry> entries) {
        return entries.stream()
                .map(Consent.ResourceEntry::getUrl)
                .map(AuditLogSanitizer::sanitizeToken)
                .collect(Collectors.joining(","));
    }

    private static String accessTypesOf(List<Consent.ResourceEntry> entries) {
        return entries.stream()
                .flatMap(entry -> entry.getAccess().stream())
                .map(Enum::name)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private static String outcomeOf(RuntimeException error) {
        return switch (error) {
            case null -> "SUCCESS";
            case PermissionDeniedException ignored -> "DENIED";
            case ResourceNotFoundException ignored -> "NOT_FOUND";
            default -> "ERROR";
        };
    }

    // Non-secret evidence of the calling actor: the DIAL key's project and/or the workload JWT's azp.
    private static String actorEvidence(ProxyContext context) {
        Key key = context.getKey();
        ExtractedClaims claims = context.getExtractedClaims();
        String azp = claims == null ? null : claims.authorizedParty();
        String project = key == null ? null : "project:" + AuditLogSanitizer.sanitizeToken(key.getProject());
        String authorizedParty = azp == null ? null : "azp:" + AuditLogSanitizer.sanitizeToken(azp);
        if (project != null && authorizedParty != null) {
            return project + " " + authorizedParty;
        }
        if (project != null) {
            return project;
        }
        return authorizedParty == null ? "unknown" : authorizedParty;
    }
}
