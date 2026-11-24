package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;

/**
 * A plugin instance Core runs in process, registered under {@link Config#getPlugins()} by name. {@link #pluginId}
 * names the plugin code, a provider Core loaded at start; {@link #settings} configure this instance.
 *
 * <p>Only the provider reads the settings, and Core checks them with it on every write and config rebuild. They hold
 * no secrets: Core stores and returns them as written.
 */
@Data
public class Plugin {

    /**
     * The id of the provider that serves this entry.
     */
    private final String pluginId;

    /**
     * The settings of this instance, an empty object when none are given.
     */
    private final ObjectNode settings;

    public Plugin(String pluginId, ObjectNode settings) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin pluginId cannot be null or blank");
        }
        this.pluginId = pluginId;
        this.settings = settings == null ? JsonNodeFactory.instance.objectNode() : settings;
    }

    /**
     * Binds a body that names the provider with {@code pluginId} or with its deprecated alias {@code type}. The alias is
     * read only: Core writes {@code pluginId}.
     */
    @JsonCreator
    static Plugin fromJson(@JsonProperty("pluginId") String pluginId,
                           @JsonProperty("type") String type,
                           @JsonProperty("settings") ObjectNode settings) {
        if (pluginId != null && type != null) {
            throw new IllegalArgumentException("Plugin has both pluginId and its deprecated alias type: keep pluginId only");
        }
        return new Plugin(pluginId == null ? type : pluginId, settings);
    }
}
