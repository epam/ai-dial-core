package com.epam.aidial.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
