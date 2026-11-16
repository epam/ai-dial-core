package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.util.Map;

/**
 * A deployment that picks the deployment serving a request. Apart from how it serves its interfaces and an optional
 * app-type schema with the properties it validates, it declares no field of its own: the fields of another deployment
 * kind ({@code upstreams}, {@code limits}, {@code pricing}) are rejected by the config mapper because this class does
 * not declare them.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Router extends Deployment {

    // left out when unset: a Core without these fields fails on an unknown key, so an untyped router stays readable
    @JsonAlias({"applicationTypeSchemaId", "application_type_schema_id"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private URI applicationTypeSchemaId;

    @JsonAlias({"applicationProperties", "application_properties"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> applicationProperties;

    /**
     * How the router serves every interface it declares. Absent means {@link Mode#PROXY}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Mode mode;

    /**
     * The decide request's settings, when {@link #mode} is {@link Mode#DECISION}. Absent means the defaults.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Decision decision;

    @Getter
    @AllArgsConstructor
    public enum Mode {
        /**
         * The client's request is forwarded to the router, which calls the model it picked back through Core.
         */
        PROXY("proxy"),
        /**
         * Core asks the router which deployment serves the request and serves that one itself: the client's request
         * is never forwarded to the router.
         */
        DECISION("decision");

        @JsonValue
        private final String value;
    }

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Decision {
        /**
         * Whether the decide request carries the client's request body, byte for byte. Absent means false.
         */
        private Boolean body;

        /**
         * How long the decide request may take, connecting included. Absent means 5000 ms.
         */
        private Long timeoutMs;

        /**
         * How many decide requests one client request may need, the first included, from 1 to 10. Absent means 3.
         */
        private Integer maxDecisions;
    }
}
