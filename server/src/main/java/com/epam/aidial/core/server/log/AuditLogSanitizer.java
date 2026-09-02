package com.epam.aidial.core.server.log;

import java.util.regex.Pattern;

/**
 * Log-line hygiene shared by the audit streams: token and reason sanitization against log
 * forging. One copy, because the character classes are security-sensitive — a hardening applied
 * to one audit class must not silently miss the other.
 */
final class AuditLogSanitizer {

    // \p{Cntrl} is ASCII-only, so Unicode line breaks (NEL, LS, PS) are listed explicitly — some log viewers
    // treat them as line terminators. Tokens additionally forbid whitespace, '=' and '"' so a caller-supplied
    // value can't forge key=value pairs within the line; reason keeps spaces (it is quoted) but drops '=' and
    // '"' so it can neither escape its quotes nor carry a parseable forged token.
    private static final Pattern TOKEN_UNSAFE = Pattern.compile("[\\p{Cntrl}\\s=\"\\u0085\\u2028\\u2029]");
    private static final Pattern REASON_UNSAFE = Pattern.compile("[\\p{Cntrl}=\"\\u0085\\u2028\\u2029]");

    private AuditLogSanitizer() {
    }

    static String sanitizeToken(String value) {
        return value == null ? null : TOKEN_UNSAFE.matcher(value).replaceAll("_");
    }

    static String sanitizeReason(String value) {
        return value == null ? null : REASON_UNSAFE.matcher(value).replaceAll("_");
    }

    static String reasonOf(RuntimeException error) {
        return error == null ? "" : " reason=\"%s\"".formatted(sanitizeReason(error.getMessage()));
    }
}
