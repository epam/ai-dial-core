package com.epam.deltix.dial.proxy.config;

import lombok.Data;

@Data
public class Key {
    private String key;
    private String project;
    private String role;
    private boolean userAuth;
}
