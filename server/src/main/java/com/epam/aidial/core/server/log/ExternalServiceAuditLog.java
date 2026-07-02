package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distinct audit stream for on-behalf-of external-service events (design §8). Kept separate from the downstream
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
            case PermissionDeniedException ignored -> "DENIED";
            case ResourceNotFoundException ignored -> "NOT_FOUND";
            default -> "ERROR";
        };
        AUDIT.info("event=obo_credential_retrieval outcome={} actor={} owner_sub={} application_id={} "
                        + "external_service_id={} trace_id={}{}",
                outcome, actorEvidence(context), ownerSub, applicationId, externalServiceId, context.getTraceId(),
                error == null ? "" : " reason=\"%s\"".formatted(error.getMessage()));
    }

    // Non-secret evidence of the calling actor: the DIAL key's project, or the workload JWT's azp (Azure v1: appid).
    private static String actorEvidence(ProxyContext context) {
        Key key = context.getKey();
        if (key != null) {
            return "project:" + key.getProject();
        }
        ExtractedClaims claims = context.getExtractedClaims();
        String azp = claims == null ? null : claims.authorizedParty();
        return azp == null ? "unknown" : "azp:" + azp;
    }
}
