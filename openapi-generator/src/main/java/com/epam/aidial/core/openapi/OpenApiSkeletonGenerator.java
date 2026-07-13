package com.epam.aidial.core.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class OpenApiSkeletonGenerator {

    private OpenApiSkeletonGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: OpenApiSkeletonGenerator <output-file> <api-version>");
            System.exit(1);
        }

        Path outputPath = Paths.get(args[0]);
        String apiVersion = args[1];
        Files.createDirectories(outputPath.getParent());

        SpecAssembler assembler = new SpecAssembler(apiVersion);
        String yamlContent = assembler.assemble();

        Files.writeString(outputPath, yamlContent);
        System.out.println("Generated OpenAPI skeleton: " + outputPath.toAbsolutePath());
    }
}