package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.LocalizedValueDeserializer;
import com.epam.aidial.core.config.databind.LocalizedValueSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A free-text value that is either a plain string (the value for the default locale) or a
 * BCP-47 {@code locale -> value} map carrying one or more translations. Plain strings are the
 * legacy/majority form and are wire-identical to a raw string field; the map form is only
 * emitted/accepted when more than one locale is present.
 */
@JsonDeserialize(using = LocalizedValueDeserializer.class)
@JsonSerialize(using = LocalizedValueSerializer.class)
public final class LocalizedValue {

    private final String plainValue;
    private final Map<String, String> localeMap;

    private LocalizedValue(String plainValue, Map<String, String> localeMap) {
        this.plainValue = plainValue;
        this.localeMap = localeMap;
    }

    public static LocalizedValue of(String value) {
        return value == null ? null : new LocalizedValue(value, null);
    }

    public static LocalizedValue of(Map<String, String> localeMap) {
        return localeMap == null ? null : new LocalizedValue(null, new LinkedHashMap<>(localeMap));
    }

    @JsonIgnore
    public boolean isMap() {
        return localeMap != null;
    }

    public String getPlainValue() {
        return plainValue;
    }

    public Map<String, String> getLocaleMap() {
        return localeMap;
    }

    /**
     * Collapses a single-entry map keyed by {@code defaultLocale} down to a plain string, leaving
     * every other shape (plain string, empty/multi-entry map) untouched. Used to keep
     * single-language deployments wire-identical to their pre-localization representation.
     */
    public LocalizedValue normalize(String defaultLocale) {
        if (localeMap != null && localeMap.size() == 1 && localeMap.containsKey(defaultLocale)) {
            return LocalizedValue.of(localeMap.get(defaultLocale));
        }
        return this;
    }

    /**
     * Resolves a definite string value for internal (non-localized) Core usages: the requested
     * locale if present, else the default locale, else the first available value.
     */
    public String resolve(String locale, String defaultLocale) {
        if (plainValue != null) {
            return plainValue;
        }
        if (localeMap.containsKey(locale)) {
            return localeMap.get(locale);
        }
        if (localeMap.containsKey(defaultLocale)) {
            return localeMap.get(defaultLocale);
        }
        return localeMap.values().stream().findFirst().orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LocalizedValue other)) {
            return false;
        }
        return Objects.equals(plainValue, other.plainValue) && Objects.equals(localeMap, other.localeMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(plainValue, localeMap);
    }

    @Override
    public String toString() {
        return plainValue != null ? plainValue : String.valueOf(localeMap);
    }
}
