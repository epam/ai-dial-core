package com.epam.aidial.core.util;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@AllArgsConstructor
public class EtagHeader {
    public static final EtagHeader ANY = new EtagHeader(Set.of());
    private final Set<String> tags;

    public void validate(String etag) {
        validate(() -> etag);
    }

    public void validate(Supplier<String> etagSupplier) {
        if (tags.isEmpty()) {
            return;
        }

        String etag = etagSupplier.get();
        if (etag == null) {
            // Resource doesn't exist
            return;
        }

        if (!tags.contains(etag)) {
            throw new HttpException(HttpStatus.PRECONDITION_FAILED, "ETag %s is outdated".formatted(etag));
        }
    }

    public static EtagHeader fromRequest(HttpServerRequest request) {
        String etag = request.getHeader(HttpHeaders.IF_MATCH);
        if (StringUtils.isBlank(etag) || "*".equals(etag.strip())) {
            return ANY;
        }

        Set<String> parsedTags = Arrays.stream(etag.split(","))
                .map(tag -> StringUtils.strip(tag, "\""))
                .collect(Collectors.toUnmodifiableSet());
        return new EtagHeader(parsedTags);
    }
}
