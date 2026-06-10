package com.epam.aidial.cli.service;

import com.epam.aidial.cli.data.CliProfile;
import com.epam.aidial.cli.data.Defaults;
import com.epam.aidial.cli.data.OutputFormat;
import com.epam.aidial.cli.data.ProfileLoader;
import com.epam.aidial.cli.exception.CliConfigException;

public final class OutputFormatResolver {

    private OutputFormatResolver() {
    }

    public static OutputFormatDto resolve(CliOptionsDto opts) {
        if (opts.output() != null) {
            return opts.output();
        }
        CliProfile profile;
        try {
            profile = ProfileLoader.load(opts.configPath());
        } catch (CliConfigException e) {
            return OutputFormatDto.TABLE;
        }
        Defaults defaults = profile.getDefaults();
        if (defaults != null && defaults.getOutput() != null) {
            return toDto(defaults.getOutput());
        }
        return OutputFormatDto.TABLE;
    }

    public static OutputFormatDto toDto(OutputFormat format) {
        return switch (format) {
            case JSON -> OutputFormatDto.JSON;
            case YAML -> OutputFormatDto.YAML;
            case TABLE -> OutputFormatDto.TABLE;
        };
    }
}
