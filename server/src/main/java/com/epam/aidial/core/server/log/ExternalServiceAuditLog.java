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
                                    String ownerSub, RuntimeException error) {
        String outcome = switch (error) {
            case null -> "SUCCESS";
            case ConsentRequiredException ignored -> "CONSENT_REQUIRED";
            case PermissionDeniedException ignored -> "DENIED";
            case ResourceNotFoundException ignored -> "NOT_FOUND";
            default -> "ERROR";
        };
        // reason echoes only the exception message, never a response body or secret — keep it that way.
        // All interpolated values are sanitized: caller-supplied ids/messages must not inject newlines that
        // would forge extra audit lines to a line-based log reader.
        AUDIT.info("event=obo_credential_retrieval outcome={} actor={} owner_sub={} application_id={} "
                        + "external_service_id={} trace_id={}{}",
                outcome, actorEvidence(context), sanitize(ownerSub), sanitize(applicationId),
                sanitize(externalServiceId), context.getTraceId(),
                error == null ? "" : " reason=\"%s\"".formatted(sanitize(error.getMessage())));
    }

    // Non-secret evidence of the calling actor: the DIAL key's project and/or the workload JWT's azp. Both are
    // recorded when both are present, since AppIdentityMatcher may have passed the gate via either one.
    private static String actorEvidence(ProxyContext context) {
        Key key = context.getKey();
        ExtractedClaims claims = context.getExtractedClaims();
        String azp = claims == null ? null : claims.authorizedParty();
        String project = key == null ? null : "project:" + sanitize(key.getProject());
        String authorizedParty = azp == null ? null : "azp:" + sanitize(azp);
        if (project != null && authorizedParty != null) {
            return project + " " + authorizedParty;
        }
        if (project != null) {
            return project;
        }
        return authorizedParty == null ? "unknown" : authorizedParty;
    }

    private static String sanitize(String value) {
        // Replace ISO control characters (CR/LF included) so a value can't break out of its audit field.
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "_");
    }
}
