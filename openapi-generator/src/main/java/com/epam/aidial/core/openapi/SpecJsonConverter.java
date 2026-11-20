package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Converts the merged YAML spec into an equivalent JSON spec, keeping key order.
 * Output is deterministic across platforms: two-space indent, LF line endings, trailing newline.
 */
public final class SpecJsonConverter {

    private SpecJsonConverter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: SpecJsonConverter <input-yaml> <output-json>");
            System.exit(1);
        }

        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        Files.createDirectories(outputPath.getParent());

        Files.writeString(outputPath, convert(Files.readString(inputPath)));
        System.out.println("Generated OpenAPI JSON: " + outputPath.toAbsolutePath());
    }

    static String convert(String yaml) throws IOException {
        JsonNode spec = new ObjectMapper(new YAMLFactory()).readTree(yaml);

        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                        .withObjectEmptySeparator("")
                        .withArrayEmptySeparator(""))
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);

        return new ObjectMapper().writer(printer).writeValueAsString(spec) + "\n";
    }
}
