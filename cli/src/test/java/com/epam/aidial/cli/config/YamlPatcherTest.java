package com.epam.aidial.cli.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlPatcherTest {

    @Test
    void replacesExistingLeafValue() {
        String yaml = """
                defaults:
                  env: local
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                defaults:
                  env: "staging"
                """);
    }

    @Test
    void preservesIndentationOfExistingLeaf() {
        String yaml = "defaults:\n    env: local\n";

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("defaults:\n    env: \"staging\"\n");
    }

    @Test
    void skipsTopLevelEnvKey() {
        String yaml = """
                env: local
                defaults:
                  env: "local"
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                env: local
                defaults:
                  env: "staging"
                """);
    }

    @Test
    void skipsNestedDefaultsEnvInAnotherSection() {
        String yaml = """
                model:
                  defaults:
                    env: nested
                defaults:
                  env: "local"
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                model:
                  defaults:
                    env: nested
                defaults:
                  env: "staging"
                """);
    }

    @Test
    void insertsLeafAfterSectionHeaderWhenKeyMissing() {
        String yaml = """
                defaults:
                  output: table
                environments:
                  dev:
                    api_url: http://x
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                defaults:
                  env: "staging"
                  output: table
                environments:
                  dev:
                    api_url: http://x
                """);
    }

    @Test
    void prependsEntirePathWhenSectionMissing() {
        String yaml = """
                environments:
                  dev:
                    api_url: http://x
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                defaults:
                  env: "staging"

                environments:
                  dev:
                    api_url: http://x
                """);
    }

    @Test
    void createsEntirePathForEmptyYaml() {
        String result = YamlPatcher.patch("", "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("defaults:\n  env: \"staging\"\n\n");
    }

    @Test
    void handlesNullYaml() {
        String result = YamlPatcher.patch(null, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("defaults:\n  env: \"staging\"\n\n");
    }

    @Test
    void preservesCommentsAndBlankLines() {
        String yaml = """
                # dial-cli config
                defaults:
                  # preferred output format
                  output: table
                  env: "local"

                # environments
                environments:
                  dev:
                    api_url: http://x
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                # dial-cli config
                defaults:
                  # preferred output format
                  output: table
                  env: "staging"

                # environments
                environments:
                  dev:
                    api_url: http://x
                """);
    }

    @Test
    void preservesIfForTags() {
        String yaml = """
                defaults:
                  env: "local"
                templates:
                  t:
                    fields:
                      !if ${vars.x} == 'true':
                        y: true
                      !for { in: "${params.r}", as: r }:
                      - endpoint: "${r}"
                """;

        String result = YamlPatcher.patch(yaml, "defaults.env", "\"staging\"");

        assertThat(result).isEqualTo("""
                defaults:
                  env: "staging"
                templates:
                  t:
                    fields:
                      !if ${vars.x} == 'true':
                        y: true
                      !for { in: "${params.r}", as: r }:
                      - endpoint: "${r}"
                """);
    }

    @Test
    void supportsSingleSegmentPath() {
        String yaml = "env: local\n";

        String result = YamlPatcher.patch(yaml, "env", "\"staging\"");

        assertThat(result).isEqualTo("env: \"staging\"\n");
    }

    @Test
    void supportsThreeSegmentPath() {
        String yaml = """
                a:
                  b:
                    c: old
                """;

        String result = YamlPatcher.patch(yaml, "a.b.c", "\"new\"");

        assertThat(result).isEqualTo("""
                a:
                  b:
                    c: "new"
                """);
    }

    @Test
    void insertsMissingIntermediateLevels() {
        String yaml = """
                a:
                  other: x
                """;

        String result = YamlPatcher.patch(yaml, "a.b.c", "\"v\"");

        assertThat(result).isEqualTo("""
                a:
                  b:
                    c: "v"
                  other: x
                """);
    }
}
