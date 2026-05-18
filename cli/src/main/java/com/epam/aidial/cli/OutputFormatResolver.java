package com.epam.aidial.cli;

import com.epam.aidial.cli.config.CliConfigException;
import com.epam.aidial.cli.config.CliProfile;
import com.epam.aidial.cli.config.Defaults;
import com.epam.aidial.cli.config.ProfileLoader;

public final class OutputFormatResolver {

    private OutputFormatResolver() {
    }

    static OutputFormat resolve(DialCli root) {
        if (root.output != null) {
            return root.output;
        }
        CliProfile profile;
        try {
            profile = ProfileLoader.load(root.configPath);
        } catch (CliConfigException e) {
            return OutputFormat.TABLE;
        }
        Defaults defaults = profile.getDefaults();
        if (defaults != null && defaults.getOutput() != null) {
            return defaults.getOutput();
        }
        return OutputFormat.TABLE;
    }
}
