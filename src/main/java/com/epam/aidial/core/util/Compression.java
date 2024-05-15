package com.epam.aidial.core.util;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    public byte[] decompress(String type, InputStream input) {
        if (!type.equals("gzip")) {
            throw new IllegalArgumentException("Unsupported compression: " + type);
        }

        try (GZIPInputStream stream = new GZIPInputStream(input)) {
            return stream.readAllBytes();
        }
    }


    public byte[] decompress(String type, byte[] input) {
        try {
            return decompress(type, new ByteArrayInputStream(input));
        } catch (Exception e) {
            // special case for GCP cloud storage, due to jclouds bug https://issues.apache.org/jira/projects/JCLOUDS/issues/JCLOUDS-1633
            if (e instanceof ZipException) {
                log.warn("Failed to decompress provided input: " + e.getMessage());
                return input;
            }
            throw e;
        }
    }
}