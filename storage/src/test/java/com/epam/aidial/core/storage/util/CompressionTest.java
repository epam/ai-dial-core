package com.epam.aidial.core.storage.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompressionTest {

    @Test
    public void testNormalFlow() {
        byte[] content = "Hello world!".getBytes();

        assertThrows(IllegalArgumentException.class, () -> Compression.compress("wrong", content));

        byte[] compressed = Compression.compress("gzip", content);

        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
        assertThrows(IllegalArgumentException.class, () -> Compression.decompress("wrong", compressed));

        byte[] actual = Compression.decompress("gzip", compressed);

        assertArrayEquals(content, actual);
    }

    @Test
    public void testGcpWorkaround() {
        byte[] content = "Hello world!".getBytes();

        byte[] actual = Compression.decompress("gzip", content);

        assertEquals(content, actual);
    }

    @Test
    public void testDecodeHttpBody() {
        byte[] content = "Hello world!".getBytes();
        byte[] gzipped = Compression.compress("gzip", content);

        // gzip and x-gzip are decoded, case-insensitively
        assertArrayEquals(content, Compression.decodeHttpBody(List.of("gzip"), gzipped));
        assertArrayEquals(content, Compression.decodeHttpBody(List.of("x-gzip"), gzipped));
        assertArrayEquals(content, Compression.decodeHttpBody(List.of("GZIP"), gzipped));

        // identity or no coding is passed through unchanged
        assertArrayEquals(content, Compression.decodeHttpBody(List.of("identity"), content));
        assertArrayEquals(content, Compression.decodeHttpBody(List.of(), content));

        // empty/null bodies are returned as-is
        assertArrayEquals(new byte[0], Compression.decodeHttpBody(List.of("gzip"), new byte[0]));
        assertNull(Compression.decodeHttpBody(List.of("gzip"), null));

        // unsupported or combined codings are rejected
        assertThrows(IllegalArgumentException.class, () -> Compression.decodeHttpBody(List.of("br"), content));
        assertThrows(IllegalArgumentException.class, () -> Compression.decodeHttpBody(List.of("gzip, identity"), gzipped));
    }
}
