package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.util.Map;

/**
 * A deployment that picks the deployment serving a request. Apart from an optional app-type schema and the
 * properties it validates, it declares no field of its own: the fields of another deployment kind
 * ({@code upstreams}, {@code limits}, {@code pricing}) are rejected by the config mapper because this class does
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
}
