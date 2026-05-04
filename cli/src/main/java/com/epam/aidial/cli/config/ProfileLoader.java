package com.epam.aidial.cli.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProfileLoader {

    static final Path DEFAULT_PATH = Paths.get(System.getProperty("user.home"), ".dial-cli", "config.yaml");

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProfileLoader() {
    }

    public static CliProfile load(Path path) {
        Path resolved = (path != null) ? path : DEFAULT_PATH;
        if (!Files.exists(resolved)) {
            return new CliProfile();
        }
        try {
            CliProfile profile = MAPPER.readValue(resolved.toFile(), CliProfile.class);
            return (profile != null) ? profile : new CliProfile();
        } catch (IOException e) {
            throw new CliConfigException("Failed to parse CLI profile at " + resolved, e);
        }
    }
}
