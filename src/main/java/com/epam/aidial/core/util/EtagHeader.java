package com.epam.aidial.core.util;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@AllArgsConstructor
public class EtagHeader {
    public static final String ANY_TAG = "*";
    public static final EtagHeader ANY = new EtagHeader(Set.of(), "", true);
    public static final EtagHeader NEW_ONLY = new EtagHeader(Set.of(), "", false);
    private final Set<String> tags;
    private final String raw;
    @Getter
    private final boolean overwrite;

    public void validate(String etag) {
        validate(() -> etag);
    }

    public void validate(Supplier<String> etagSupplier) {
        if (tags.isEmpty() && overwrite) {
            return;
        }

        String etag = etagSupplier.get();
        if (etag == null) {
            // Resource doesn't exist
            return;
        }

        if (!overwrite) {
            throw new HttpException(HttpStatus.PRECONDITION_FAILED, "Resource already exists");
        }

        if (!tags.contains(etag)) {
            throw new HttpException(HttpStatus.PRECONDITION_FAILED, "ETag %s is rejected".formatted(raw));
        }
    }

    public static EtagHeader fromRequest(HttpServerRequest request) {
        return fromHeader(request.getHeader(HttpHeaders.IF_MATCH), request.getHeader(HttpHeaders.IF_NONE_MATCH));
    }

    static EtagHeader fromHeader(String ifMatch, String ifNoneMatch) {
        Set<String> tags = parseIfMatch(StringUtils.strip(ifMatch));
        boolean overwrite = parseOverwrite(StringUtils.strip(ifNoneMatch));
        return new EtagHeader(tags, ifMatch, overwrite);
    }

    private static Set<String> parseIfMatch(String value) {
        if (StringUtils.isEmpty(value) || ANY_TAG.equals(value)) {
            return Set.of();
        }

        return Arrays.stream(value.split(","))
                .map(tag -> StringUtils.strip(tag, "\""))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean parseOverwrite(String value) {
        if (ANY_TAG.equals(value)) {
            return false;
        }

        if (value != null) {
            throw new HttpException(
                    HttpStatus.BAD_REQUEST, "Only * is supported for header " + HttpHeaders.IF_NONE_MATCH);
        }

        return true;
    }
}
