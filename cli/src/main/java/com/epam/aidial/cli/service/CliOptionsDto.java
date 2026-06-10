package com.epam.aidial.cli.service;

import java.nio.file.Path;

public record CliOptionsDto(
        String env,
        Path configPath,
        String apiUrl,
        Path apiKeyFile,
        OutputFormatDto output,
        boolean dryRun
) {
}
