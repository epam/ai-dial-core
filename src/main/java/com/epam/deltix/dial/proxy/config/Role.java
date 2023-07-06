package com.epam.deltix.dial.proxy.config;

import lombok.Data;

import java.util.Map;

@Data
public class Role {
    private String name;
    private Map<String, Limit> limits;
}