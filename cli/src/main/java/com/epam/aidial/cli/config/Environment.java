package com.epam.aidial.cli.config;

import lombok.Data;

import java.util.Map;

@Data
public class Environment {
    private String apiUrl;
    private Auth auth;
    private Map<String, Object> vars;
}
