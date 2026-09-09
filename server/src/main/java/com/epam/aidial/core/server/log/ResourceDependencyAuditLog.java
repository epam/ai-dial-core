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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Distinct audit stream for resource-dependency events (admin consent decisions, and the
 * request-start grant/denial/runtime-fail outcomes that land with the resolver). Kept separate
 * from the OBO stream so operators can route/retain it independently. Never logs credential
 * material or resource content — only identities, targets and outcomes.
 */
public final class ResourceDependencyAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("DIAL_RESOURCE_DEPS_AUDIT");

    // \p{Cntrl} is ASCII-only, so Unicode line breaks (NEL, LS, PS) are listed explicitly — some log viewers
    // treat them as line terminators. Tokens additionally forbid whitespace, '=' and '"' so a caller-supplied
    // value can't forge key=value pairs within the line; reason keeps spaces (it is quoted) but drops '=' and
    // '"' so it can neither escape its quotes nor carry a parseable forged token.
    private static final Pattern TOKEN_UNSAFE = Pattern.compile("[\\p{Cntrl}\\s=\"\\u0085\\u2028\\u2029]");
    private static final Pattern REASON_UNSAFE = Pattern.compile("[\\p{Cntrl}=\"\\u0085\\u2028\\u2029]");

    private ResourceDependencyAuditLog() {
    }

    /**
     * One event per administrator decision on an application's declared resource dependencies.
     * {@code declaration} is the snapshot in force for the decision — the current declaration on
     * grant, the withdrawn record on withdraw — so the audit line always carries what was approved.
     */
    public static void consent(ProxyContext context, String applicationId, String action,
                               List<Consent.ResourceEntry> declaration, RuntimeException error) {
        String targets = declaration == null ? "" : declaration.stream()
                .map(Consent.ResourceEntry::getUrl)
                .map(ResourceDependencyAuditLog::sanitizeToken)
                .collect(Collectors.joining(","));
        String accessTypes = declaration == null ? "" : declaration.stream()
                .flatMap(entry -> entry.getAccess().stream())
                .map(Enum::name)
                .distinct()
                .collect(Collectors.joining(","));
        AUDIT.info("event=resource_dependency_consent action={} outcome={} actor={} admin_user_id={} "
                        + "application_id={} targets={} access_types={} trace_id={}{}",
                sanitizeToken(action), outcomeOf(error), actorEvidence(context),
                sanitizeToken(context.getUserId()), sanitizeToken(applicationId),
                targets, accessTypes, context.getTraceId(), reasonOf(error));
    }

    private static String outcomeOf(RuntimeException error) {
        return switch (error) {
            case null -> "SUCCESS";
            case PermissionDeniedException ignored -> "DENIED";
            case ResourceNotFoundException ignored -> "NOT_FOUND";
            default -> "ERROR";
        };
    }

    private static String reasonOf(RuntimeException error) {
        return error == null ? "" : " reason=\"%s\"".formatted(sanitizeReason(error.getMessage()));
    }

    // Non-secret evidence of the calling actor: the DIAL key's project and/or the workload JWT's azp.
    private static String actorEvidence(ProxyContext context) {
        Key key = context.getKey();
        ExtractedClaims claims = context.getExtractedClaims();
        String azp = claims == null ? null : claims.authorizedParty();
        String project = key == null ? null : "project:" + sanitizeToken(key.getProject());
        String authorizedParty = azp == null ? null : "azp:" + sanitizeToken(azp);
        if (project != null && authorizedParty != null) {
            return project + " " + authorizedParty;
        }
        if (project != null) {
            return project;
        }
        return authorizedParty == null ? "unknown" : authorizedParty;
    }

    private static String sanitizeToken(String value) {
        return value == null ? null : TOKEN_UNSAFE.matcher(value).replaceAll("_");
    }

    private static String sanitizeReason(String value) {
        return value == null ? null : REASON_UNSAFE.matcher(value).replaceAll("_");
    }
}
