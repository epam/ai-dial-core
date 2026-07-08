package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Route;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    private Map<String, Route> routes;

    private String viewerUrl;

    private String editorUrl;

    /** Per-user view of the app's external services with sign-in status; never any credential material. */
    @Nullable
    @JsonAlias({"externalServices", "external_services"})
    private Map<String, ExternalService> externalServices;

    @JsonIgnore
    public static ApplicationData mapApplication(Application application) {
        ApplicationData data = new ApplicationData();
        data.setInvalid(application.getInvalid());
        data.setId(application.getName());
        data.setApplication(application.getName());
        if (application.getDisplayName() != null) {
            data.setDisplayName(application.getDisplayName());
        } else {
            data.setDisplayName(application.getName());
        }
        data.setDisplayVersion(application.getDisplayVersion());
        data.setIconUrl(application.getIconUrl());
        data.setDescription(application.getDescription());
        data.setIntro(application.getIntro());
        data.setFeatures(FeaturesData.createFeatures(application.getFeatures()));
        data.setInputAttachmentTypes(application.getInputAttachmentTypes());
        data.setMaxInputAttachments(application.getMaxInputAttachments());
        data.setDefaults(application.getDefaults());
        data.setResponsesDefaults(application.getResponsesDefaults());
        data.setDescriptionKeywords(application.getDescriptionKeywords());

        data.setApplicationTypeSchemaId(application.getApplicationTypeSchemaId());
        data.setApplicationProperties(application.getApplicationProperties());
        String reference = application.getReference();
        data.setReference(reference == null ? application.getName() : reference);
        data.setFunction(application.getFunction());
        data.setMaxRetryAttempts(application.getMaxRetryAttempts());

        if (application.getAuthor() != null) {
            data.setOwner(application.getAuthor());
        }
        if (application.getCreatedAt() != null) {
            data.setCreatedAt(application.getCreatedAt());
        }
        if (application.getUpdatedAt() != null) {
            data.setUpdatedAt(application.getUpdatedAt());
        }

        data.setRoutes(application.getRoutes());
        data.setViewerUrl(application.getViewerUrl());
        data.setEditorUrl(application.getEditorUrl());

        return data;
    }
}