package com.epam.aidial.core.config;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.config.databind.JsonToStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Per-interface configuration for an {@link Upstream}. The peer of {@link DeploymentInterface}, but
 * carrying a complete {@code endpoint} rather than a base url: an upstream is the provider itself,
 * not an adapter Core routes an ingress path into.
 *
 * <p>Every field overrides its {@link Upstream}-level namesake for this interface only; a field left
 * unset here falls back to the upstream's value.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpstreamInterface {

    /**
     * The complete provider url for this interface. When absent, it is formed from
     * {@link Upstream#getBaseUrl()} and {@link InterfaceType#getApiPath()}.
     */
    @JsonAlias({"endpoint", "dial:endpoint"})
    private String endpoint;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @EncryptedField
    @JsonAlias({"key", "dial:key"})
    private String key;
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    @JsonAlias({"extraData", "dial:extraData"})
    private String extraData;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @EncryptedField
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    @JsonAlias({"secretExtraData", "dial:secretExtraData"})
    private String secretExtraData;

    public UpstreamInterface(String endpoint) {
        this.endpoint = endpoint;
    }
}
