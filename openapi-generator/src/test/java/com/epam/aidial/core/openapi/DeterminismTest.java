package com.epam.aidial.core.openapi;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that OpenAPI generation is deterministic (produces byte-identical output on repeated runs).
 */
class DeterminismTest {

    @Test
    void generationIsDeterministic() throws Exception {
        SpecAssembler assembler1 = new SpecAssembler("test-v1");
        SpecAssembler assembler2 = new SpecAssembler("test-v1");

        String yaml1 = assembler1.assemble();
        String yaml2 = assembler2.assemble();

        assertEquals(yaml1, yaml2,
                "Two consecutive generations from identical source must produce identical YAML output");
    }

    @Test
    void generationProducesSameFileContent() throws Exception {
        Path tempFile1 = Files.createTempFile("openapi-test-1", ".yaml");
        Path tempFile2 = Files.createTempFile("openapi-test-2", ".yaml");

        try {
            SpecAssembler assembler1 = new SpecAssembler("test-v1");
            SpecAssembler assembler2 = new SpecAssembler("test-v1");

            String yaml1 = assembler1.assemble();
            String yaml2 = assembler2.assemble();

            Files.writeString(tempFile1, yaml1);
            Files.writeString(tempFile2, yaml2);

            byte[] bytes1 = Files.readAllBytes(tempFile1);
            byte[] bytes2 = Files.readAllBytes(tempFile2);

            assertEquals(bytes1.length, bytes2.length, "File sizes must match");
            for (int i = 0; i < bytes1.length; i++) {
                assertEquals(bytes1[i], bytes2[i],
                        "Byte mismatch at position " + i + " (byte values: " + bytes1[i] + " vs " + bytes2[i] + ")");
            }
        } finally {
            Files.deleteIfExists(tempFile1);
            Files.deleteIfExists(tempFile2);
        }
    }
}
