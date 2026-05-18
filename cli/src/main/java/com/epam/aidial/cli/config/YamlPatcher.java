package com.epam.aidial.cli.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class YamlPatcher {

    private YamlPatcher() {
    }

    /**
     * Patches a single scalar value in a raw YAML string at the given dot-separated path.
     * The edit is performed at the text level so comments, blank lines, and custom YAML tags
     * ({@code !if}, {@code !for}) are left untouched.
     *
     * @param yaml     raw YAML content (may be {@code null} or empty)
     * @param dotPath  dot-separated key path, e.g. {@code "defaults.env"}
     * @param newValue value to write verbatim; caller is responsible for quoting if needed
     * @return the patched YAML string
     */
    public static String patch(String yaml, String dotPath, String newValue) {
        if (yaml == null) {
            yaml = "";
        }
        String[] segments = dotPath.split("\\.", -1);
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\n", -1)));

        int depth = 0;
        int maxDepth = 0;
        int[] sectionIndents = new int[segments.length];
        int[] sectionLines = new int[segments.length];
        Arrays.fill(sectionLines, -1);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int indent = line.length() - stripped.length();

            // Pop sections whose indentation we have left
            while (depth > 0 && indent <= sectionIndents[depth - 1]) {
                depth--;
            }

            // At root depth, only process zero-indent lines
            if (depth == 0 && indent != 0) {
                continue;
            }

            String key = extractKey(stripped);

            if (depth < segments.length - 1) {
                // Looking for an intermediate section header
                if (segments[depth].equals(key)) {
                    sectionIndents[depth] = indent;
                    sectionLines[depth] = i;
                    depth++;
                    if (depth > maxDepth) {
                        maxDepth = depth;
                    }
                }
            } else {
                // Looking for the leaf key
                if (segments[depth].equals(key)) {
                    lines.set(i, " ".repeat(indent) + segments[depth] + ": " + newValue);
                    return String.join("\n", lines);
                }
            }
        }

        // Leaf not found — build and insert the missing lines
        int startIndent = maxDepth == 0 ? 0 : sectionIndents[maxDepth - 1] + 2;
        List<String> blockLines = buildBlock(segments, maxDepth, startIndent, newValue);

        if (maxDepth > 0) {
            // Insert right after the deepest matched section header
            int insertAfter = sectionLines[maxDepth - 1];
            for (int j = blockLines.size() - 1; j >= 0; j--) {
                lines.add(insertAfter + 1, blockLines.get(j));
            }
        } else {
            // No section matched — prepend the whole block with a blank separator line
            lines.add(0, "");
            for (int j = blockLines.size() - 1; j >= 0; j--) {
                lines.add(0, blockLines.get(j));
            }
        }

        return String.join("\n", lines);
    }

    private static List<String> buildBlock(String[] segments, int startDepth, int startIndent, String newValue) {
        List<String> result = new ArrayList<>();
        for (int d = startDepth; d < segments.length; d++) {
            int indent = startIndent + (d - startDepth) * 2;
            String line = " ".repeat(indent) + segments[d] + ":";
            if (d == segments.length - 1) {
                line += " " + newValue;
            }
            result.add(line);
        }
        return result;
    }

    private static String extractKey(String stripped) {
        int colon = stripped.indexOf(':');
        return colon < 0 ? stripped.trim() : stripped.substring(0, colon).trim();
    }
}
