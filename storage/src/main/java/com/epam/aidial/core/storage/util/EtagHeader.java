package com.epam.aidial.core.storage.util;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EtagHeader {
    public static final String ANY_TAG = "*";
    public static final EtagHeader ANY = new EtagHeader(null, Set.of(), null);
    public static final EtagHeader NEW_ONLY = new EtagHeader(null, null, null);
    /**
     * <code>null</code> means any tag '*'
     */
    private final Set<String> ifMatchTags;
    /**
     * <code>null</code> means any tag '*'
     */
    private final Set<String> ifNoneMatchTags;
    /**
     * Http method, e.g. GET, PUT, POST and etc
     */
    private final String method;

    public void validate(String etag) {
        validate(() -> etag);
    }

    public void validate(Supplier<String> etagSupplier) {

        String etag = etagSupplier.get();
        if (etag == null) {
            // Resource doesn't exist
            return;
        }

        validateIfMatch(etag);

        validateIfNoneMatch(etag);
    }

    private void validateIfNoneMatch(String etag) {
        // If-None-Match used with the * value can be used to save a file not known to exist,
        // guaranteeing that another upload didn't happen before
        if (ifNoneMatchTags == null) {
            throw createIfNoneMatchException(etag, "Resource already exists");
        }

        // if-non-match matches the etag
        if (ifNoneMatchTags.contains(etag)) {
            throw createIfNoneMatchException(etag, "if-none-match condition is failed for etag: " + etag);
        }
    }

    private HttpException createIfNoneMatchException(String etag, String message) {
        HttpStatus statusCode;
        Map<String, String> headers;
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            statusCode = HttpStatus.NOT_MODIFIED;
            headers = Map.of("etag", etag);
        } else {
            statusCode = HttpStatus.PRECONDITION_FAILED;
            headers = Map.of();
        }
        return new HttpException(statusCode, message, headers);
    }

    private void validateIfMatch(String etag) {
        // if-match is not any and doesn't match the etag
        if (ifMatchTags != null && !ifMatchTags.contains(etag)) {
            throw new HttpException(HttpStatus.PRECONDITION_FAILED, "If-match condition is failed for etag: " + etag);
        }
    }

    /**
     * Constructs etag header instance.
     *
     * @param ifMatch HTTP header
     * @param ifNoneMatch HTTP header
     */
    public static EtagHeader fromHeader(String ifMatch, String ifNoneMatch, String method) {
        Set<String> ifMatchTags = parseIfMatch(StringUtils.strip(ifMatch));
        Set<String> ifNoneMatchTags = parseIfNoneMatch(StringUtils.strip(ifNoneMatch));
        return new EtagHeader(ifMatchTags, ifNoneMatchTags, method);
    }

    @Nullable
    private static Set<String> parseIfNoneMatch(@Nullable String value) {
        if (ANY_TAG.equals(value)) {
            return null;
        }

        if (value == null) {
            return Set.of();
        }

        return parseTagValue(value);
    }

    @Nullable
    private static Set<String> parseIfMatch(@Nullable String value) {
        if (ANY_TAG.equals(value) || StringUtils.isEmpty(value)) {
            return null;
        }
        return parseTagValue(value);
    }

    private static Set<String> parseTagValue(String value) {
        return Arrays.stream(value.split(","))
                .map(tag -> StringUtils.strip(tag, "\""))
                .collect(Collectors.toUnmodifiableSet());
    }

}
