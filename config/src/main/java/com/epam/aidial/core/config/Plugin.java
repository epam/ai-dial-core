package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;

/**
 * A plugin instance Core runs in process, registered under {@link Config#getPlugins()} by name. {@link #type} names
 * the plugin code, a provider Core loaded at start; {@link #settings} configure this instance.
 *
 * <p>Only the provider reads the settings, and Core checks them with it on every write and config rebuild. They hold
 * no secrets: Core stores and returns them as written.
 */
@Data
public class Plugin {

    /**
     * The {@code type} of the provider that serves this entry.
     */
    private final String type;

    /**
     * The settings of this instance, an empty object when none are given.
     */
    private final ObjectNode settings;

    @JsonCreator
    public Plugin(@JsonProperty(value = "type", required = true) String type,
                  @JsonProperty("settings") ObjectNode settings) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Plugin type cannot be null or blank");
        }
        this.type = type;
        this.settings = settings == null ? JsonNodeFactory.instance.objectNode() : settings;
    }
}
