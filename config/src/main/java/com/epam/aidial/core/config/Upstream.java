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

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Upstream {

    @JsonAlias({"endpoint", "dial:endpoint"})
    private String endpoint;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAlias({"responsesEndpoint", "dial:responsesEndpoint"})
    private String responsesEndpoint;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @EncryptedField
    @JsonAlias({"key", "dial:key"})
    private String key;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    @JsonAlias({"extraData", "dial:extraData"})
    private String extraData;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @EncryptedField
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    @JsonAlias({"secretExtraData", "dial:secretExtraData"})
    private String secretExtraData;
    @JsonAlias({"weight", "dial:weight"})
    private int weight = 1;
    @JsonAlias({"tier", "dial:tier"})
    private int tier = 0;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAlias({"id", "dial:id"})
    private String id;
    /**
     * Root url that every {@link #interfaces} entry declaring no {@code endpoint} of its own hangs
     * {@link InterfaceType#getApiPath()} off. Unlike a deployment's {@code base_url}, the ingress
     * path plays no part — an upstream is addressed where its own API spec says it lives.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAlias({"baseUrl", "base_url", "dial:baseUrl"})
    private String baseUrl;
    /**
     * Provider urls keyed by interface-type value. Peer of endpoint/responsesEndpoint.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonAlias({"interfaces", "dial:interfaces"})
    private Map<String, UpstreamInterface> interfaces = Map.of();
}