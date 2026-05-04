package com.epam.aidial.cli.config;

import lombok.Data;

import java.util.Map;

@Data
public class CliProfile {
    private Defaults defaults;
    private Map<String, Environment> environments;
    private Map<String, Object> templates;
}
