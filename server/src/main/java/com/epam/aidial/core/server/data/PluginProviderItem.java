package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * A plugin provider Core loaded at start, as {@code GET /v1/admin/plugin-providers} lists it. A field the provider does
 * not have is left out: {@code directory} and {@code version} for a provider on Core's classpath.
 *
 * @param id             the id {@code plugins} entries name in {@code pluginId}
 * @param targets        where a plugin url may name its entries, a hint: {@code model}, {@code application},
 *                       {@code router}, {@code translator}
 * @param directory      its plugin directory {@code <group>/<plugin>}
 * @param version        the {@code Implementation-Version} of the jar that registers it
 * @param description    what it does
 * @param settingsSchema the JSON Schema (draft-07) Core checks the settings of its entries against
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginProviderItem(String id, List<String> targets, String directory, String version, String description,
                                 ObjectNode settingsSchema) {
}
