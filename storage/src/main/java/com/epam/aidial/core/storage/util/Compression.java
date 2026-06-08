package com.epam.aidial.core.storage.util;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

@UtilityClass
@Slf4j
public class Compression {

    @SneakyThrows
    public byte[] compress(String type, byte[] data) {
        if (!type.equals("gzip")) {
            throw new IllegalArgumentException("Unsupported compression: " + type);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream stream = new GZIPOutputStream(output)) {
            stream.write(data);
        }
        return output.toByteArray();
    }

    @SneakyThrows
    public byte[] decompress(String type, byte[] input) {
        if (!type.equals("gzip")) {
            throw new IllegalArgumentException("Unsupported compression: " + type);
        }
        try (InputStream decompressed = new GZIPInputStream(new ByteArrayInputStream(input))) {
            return decompressed.readAllBytes();
        } catch (ZipException e) {
            // special case for GCP cloud storage, due to jclouds bug https://issues.apache.org/jira/projects/JCLOUDS/issues/JCLOUDS-1633
            log.warn("Failed to decompress provided input: {}", e.getMessage());
            return input;
        }
    }

    /**
     * Decodes an HTTP response body according to its Content-Encoding header(s), per RFC 9110 §8.4.
     * Supports {@code identity} and {@code gzip}/{@code x-gzip}; any other or combined coding is
     * rejected. JDK HttpClient and Vert.x do not auto-decompress, so callers that parse or proxy
     * upstream bodies must decode explicitly. Unlike {@link #decompress}, a corrupt gzip stream is
     * surfaced as an error rather than passed through.
     *
     * @param contentEncodings raw Content-Encoding header values (each may be a comma-separated list)
     * @param body             raw response bytes (may be null or empty)
     * @return decoded bytes, or the input unchanged when no decoding applies
     * @throws IllegalArgumentException when the coding is unsupported; callers map it to a status
     */
    @SneakyThrows
    public byte[] decodeHttpBody(List<String> contentEncodings, byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        List<String> codings = contentEncodings.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(coding -> !coding.isEmpty())
                .toList();
        if (codings.isEmpty() || (codings.size() == 1 && codings.getFirst().equalsIgnoreCase("identity"))) {
            return body;
        }
        if (codings.size() == 1 && (codings.getFirst().equalsIgnoreCase("gzip")
                || codings.getFirst().equalsIgnoreCase("x-gzip"))) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return gzip.readAllBytes();
            }
        }
        throw new IllegalArgumentException("Unsupported Content-Encoding '%s'".formatted(String.join(", ", codings)));
    }
}