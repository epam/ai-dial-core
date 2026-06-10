package com.epam.aidial.cli.service;

public enum OutputFormatDto {
    JSON, YAML, TABLE;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
