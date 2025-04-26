package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Application;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.util.Map;
import javax.annotation.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ApplicationData extends DeploymentData {
    {
        setObject("application");
        setScaleSettings(null);
    }

    /**
     * Indicates whether the application is invalid.
     * Only applicable for schema-rich applications.
     * Set to true when validation fails (e.g., missing required properties or schema violations).
     * Null when the application is valid.
     * This is a read-only property (not parsed from JSON).
     */
    @Nullable
    public Boolean invalid = null;

    @Nullable
    private Map<String, Object> applicationProperties; //all custom application properties will land there

    @Nullable
    @JsonAlias({"applicationTypeSchemaId", "application_type_schema_id"})
    private URI applicationTypeSchemaId;

    private Application.Function function;
}