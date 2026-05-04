package com.epam.aidial.cli.config;

import lombok.Data;

@Data
public class Auth {
    private String type;
    private String keyEnvVar;
}
