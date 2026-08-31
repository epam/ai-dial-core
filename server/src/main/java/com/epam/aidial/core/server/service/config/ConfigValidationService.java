package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.GlobalSettings;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.AdminManifest;
import com.epam.aidial.core.server.data.ValidationResult;
import com.epam.aidial.core.server.data.ValidationStatus;
import com.epam.aidial.core.server.service.config.ConfigManifestSupport.ParsedName;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Precheck engine behind {@code /v1/admin/validate} and the {@code precheck=true} phase of
 * {@code /v1/admin/apply}: validates a manifest against {@code scratch} without writing anything.
 * Shares manifest-shape helpers with {@link ConfigApplyService} via {@link ConfigManifestSupport}.
 */
public class ConfigValidationService {

    private final ResourceService resourceService;
    private final boolean softValidation;

    public ConfigValidationService(ResourceService resourceService, boolean softValidation) {
        this.resourceService = resourceService;
        this.softValidation = softValidation;
    }

    public ValidationResult validateOnly(AdminManifest entry, Config scratch) {
        String id = entry.name();
        ParsedName parsed;
        try {
            parsed = ConfigManifestSupport.parseName(entry);
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        try {
            switch (entry.kind()) {
                case "Settings" -> {
                    if (!ConfigManifestSupport.SETTINGS_SINGLETON_NAME.equals(parsed.name())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Settings name must be 'global'");
                    }
                    ConfigEntityCodec.treeToEntity(entry.spec(), GlobalSettings.class);
                }
                case "Model" -> {
                    Model model = ConfigEntityCodec.treeToEntity(entry.spec(), Model.class);
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validatePricing(model, warnings);
                    ConfigPostProcessor.validateUpstreamInterfaces(model, warnings);
                    ConfigPostProcessor.validateCrossReferences(model, scratch, warnings);
                    UpstreamExtraDataMerger.validateNoOverlap(model);
                    if (!warnings.isEmpty() && !softValidation) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                    String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.MODEL, parsed);
                    if (dupError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                    }
                }
                case "Interceptor" -> {
                    ConfigEntityCodec.treeToEntity(entry.spec(), Interceptor.class);
                    String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.INTERCEPTOR, parsed);
                    if (dupError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                    }
                }
                case "Role" -> ConfigEntityCodec.treeToEntity(entry.spec(), Role.class);
                case "Route" -> ConfigEntityCodec.treeToEntity(entry.spec(), Route.class);
                case "Key" -> {
                    Key key = ConfigEntityCodec.treeToEntity(entry.spec(), Key.class);
                    if (StringUtils.isBlank(key.getKey())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Key.key must be provided explicitly");
                    }
                    if (StringUtils.isBlank(key.getProject())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Project key is undefined");
                    }
                    if (StringUtils.isBlank(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
                        return new ValidationResult(id, ValidationStatus.FAILED,
                                "Invalid key: at least one role must be assigned to the key " + key.getProject());
                    }
                }
                case "Application" -> {
                    ConfigEntityCodec.treeToEntity(entry.spec(), Application.class);
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.bucket())) {
                        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.APPLICATION, parsed);
                        if (dupError != null) {
                            return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                        }
                    }
                }
                case "ToolSet" -> {
                    ConfigEntityCodec.treeToEntity(entry.spec(), ToolSet.class);
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.bucket())) {
                        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.TOOL_SET, parsed);
                        if (dupError != null) {
                            return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                        }
                    }
                }
                case "Schema" -> {
                    String schemaError = ConfigManifestSupport.validateSchema(entry, parsed, scratch,
                            ResourceTypes.APP_TYPE_SCHEMA, resourceService);
                    if (schemaError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, schemaError);
                    }
                }
                case "CatalogSchema" -> {
                    String schemaError = ConfigManifestSupport.validateSchema(entry, parsed, scratch,
                            ResourceTypes.CATALOG_SCHEMA, resourceService);
                    if (schemaError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, schemaError);
                    }
                }
                default -> {
                    return new ValidationResult(id, ValidationStatus.FAILED, "Unknown kind: " + entry.kind());
                }
            }
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        return new ValidationResult(id, ValidationStatus.VALID, null);
    }
}
