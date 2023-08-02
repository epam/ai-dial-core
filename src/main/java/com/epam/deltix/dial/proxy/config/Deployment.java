package com.epam.deltix.dial.proxy.config;

import lombok.Data;

import java.util.Set;

@Data
public abstract class Deployment {
    private String name;
    private String endpoint;
    private String displayName;
    private String iconUrl;
    private String description;
    private Set<String> userRoles = Set.of();
}