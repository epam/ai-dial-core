package com.epam.aidial.cli.config;

import com.epam.aidial.cli.template.ControlFlowExpander;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

public final class ProfileLoader {

    static final Path DEFAULT_PATH = Paths.get(System.getProperty("user.home"), ".dial-cli", "config.yaml");
    static final String CONFIG_PATH_ENV_VAR = "DIAL_CLI_CONFIG";

    static Function<String, String> envLookup = System::getenv;

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private ProfileLoader() {
    }

    public static CliProfile load(Path path) {
        Path resolved = resolvePath(path);
        if (!Files.exists(resolved)) {
            return new CliProfile();
        }
        try {
            String raw = Files.readString(resolved, StandardCharsets.UTF_8);
            // Templates may carry '!if'/'!for' tags; rewrite them to sentinel keys so the
            // standard YAML mapper can parse the profile without a custom Constructor.
            String rewritten = ControlFlowExpander.rewriteYaml(raw);
            CliProfile profile = MAPPER.readValue(rewritten, CliProfile.class);
            return (profile != null) ? profile : new CliProfile();
        } catch (IOException e) {
            throw new CliConfigException("Failed to parse CLI profile at " + resolved, e);
        }
    }

    public static void save(Path path, CliProfile profile) {
        Path resolved = resolvePath(path);
        Path parent = resolved.toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, ".dial-cli-", ".yaml.tmp");
            MAPPER.writeValue(tmp.toFile(), profile);
            Files.move(tmp, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new CliConfigException("Failed to write CLI profile at " + resolved, e);
        }
    }

    static Path resolvePath(Path explicit) {
        if (explicit != null) {
            return explicit;
        }
        String envValue = envLookup.apply(CONFIG_PATH_ENV_VAR);
        if (envValue != null && !envValue.isBlank()) {
            return Paths.get(envValue);
        }
        return DEFAULT_PATH;
    }
}
