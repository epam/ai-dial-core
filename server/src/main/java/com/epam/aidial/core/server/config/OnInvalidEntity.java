package com.epam.aidial.core.server.config;

/**
 * What a config rebuild does with an entity that fails validation: {@link #ABORT} fails the rebuild and keeps the
 * previous config, {@link #SKIP} drops that entity alone and records it as invalid. The setting is
 * {@code config.onInvalidEntity}.
 */
public enum OnInvalidEntity {
    ABORT,
    SKIP;

    /** Any value other than {@code skip} (case-insensitive), including none, is {@link #ABORT}. */
    public static OnInvalidEntity fromSetting(String value) {
        return SKIP.name().equalsIgnoreCase(value) ? SKIP : ABORT;
    }
}
