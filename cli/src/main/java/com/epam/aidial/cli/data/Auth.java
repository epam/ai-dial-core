package com.epam.aidial.cli.data;

import lombok.Data;

@Data
public class Auth {
    private AuthType type;
    private String keyEnvVar;
}
