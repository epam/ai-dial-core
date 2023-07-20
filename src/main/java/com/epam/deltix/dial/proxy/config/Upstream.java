package com.epam.deltix.dial.proxy.config;

import lombok.Data;

@Data
public class Upstream {
    private String endpoint;
    private String key;
}