package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.ConsentRequiredException;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distinct audit stream for on-behalf-of external-service events. Kept separate from the downstream
 * credential-resolution logs so operators can route/retain it independently. Each event records both identities
 * (a non-secret actor + the owner subject), the scope, the outcome, and the trace id — never credential material.
 */
public final class ExternalServiceAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("DIAL_OBO_AUDIT");

    private ExternalServiceAuditLog() {
    }

    /** One event per OBO retrieval outcome; {@code error} is {@code null} on success. */
    public static void oboRetrieval(ProxyContext context, String applicationId, String externalServiceId,
                                    String ownerUserId, RuntimeException error) {
        // reason echoes only the exception message, never a response body or secret — keep it that way.
        AUDIT.info("event=obo_credential_retrieval outcome={} actor={} owner_user_id={} application_id={} "
                        + "external_service_id={} trace_id={}{}",
                outcomeOf(error), actorEvidence(context), AuditLogSanitizer.sanitizeToken(ownerUserId), AuditLogSanitizer.sanitizeToken(applicationId),
                AuditLogSanitizer.sanitizeToken(externalServiceId), context.getTraceId(), AuditLogSanitizer.reasonOf(error));
    }

    /**
     * One event per change to a user's own offline credentials. The subject is the caller: these endpoints are
     * self-service, so actor and owner are the same person.
     */
    public static void offlineCredentials(ProxyContext context, String action, RuntimeException error) {
        AUDIT.info("event=offline_credentials action={} outcome={} actor={} user_id={} trace_id={}{}",
                AuditLogSanitizer.sanitizeToken(action), outcomeOf(error), actorEvidence(context),
                AuditLogSanitizer.sanitizeToken(context.getUserId()), context.getTraceId(),
                AuditLogSanitizer.reasonOf(error));
    }

    /**
     * One event per administrator decision on an application's use of a DIAL-native service. Records who decided,
     * since consent reaches every user who has enabled offline credentials.
     */
    public static void consent(ProxyContext context, String applicationId, String externalServiceId,
                               String action, RuntimeException error) {
        AUDIT.info("event=external_service_consent action={} outcome={} actor={} admin_user_id={} "
                        + "application_id={} external_service_id={} trace_id={}{}",
                AuditLogSanitizer.sanitizeToken(action), outcomeOf(error), actorEvidence(context),
                AuditLogSanitizer.sanitizeToken(context.getUserId()), AuditLogSanitizer.sanitizeToken(applicationId),
                AuditLogSanitizer.sanitizeToken(externalServiceId), context.getTraceId(), AuditLogSanitizer.reasonOf(error));
    }

    private static String outcomeOf(RuntimeException error) {
        return switch (error) {
            case null -> "SUCCESS";
            case ConsentRequiredException ignored -> "CONSENT_REQUIRED";
            case PermissionDeniedException ignored -> "DENIED";
            case ResourceNotFoundException ignored -> "NOT_FOUND";
            default -> "ERROR";
        };
    }

    // Non-secret evidence of the calling actor: the DIAL key's project and/or the workload JWT's azp. Both are
    // recorded when both are present, since AppIdentityMatcher may have passed the gate via either one.
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
