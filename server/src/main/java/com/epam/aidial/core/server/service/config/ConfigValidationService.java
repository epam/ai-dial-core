package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.config.apply.AdminApplicationManifest;
import com.epam.aidial.core.server.data.config.apply.AdminCatalogSchemaManifest;
import com.epam.aidial.core.server.data.config.apply.AdminInterceptorManifest;
import com.epam.aidial.core.server.data.config.apply.AdminKeyManifest;
import com.epam.aidial.core.server.data.config.apply.AdminManifest;
import com.epam.aidial.core.server.data.config.apply.AdminModelManifest;
import com.epam.aidial.core.server.data.config.apply.AdminRoleManifest;
import com.epam.aidial.core.server.data.config.apply.AdminRouteManifest;
import com.epam.aidial.core.server.data.config.apply.AdminSchemaManifest;
import com.epam.aidial.core.server.data.config.apply.AdminSettingsManifest;
import com.epam.aidial.core.server.data.config.apply.AdminToolSetManifest;
import com.epam.aidial.core.server.data.config.apply.AdminTranslatorManifest;
import com.epam.aidial.core.server.data.config.apply.ValidationResult;
import com.epam.aidial.core.server.data.config.apply.ValidationStatus;
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
        ConfigManifestSupport.ParsedManifest parsed;
        try {
            parsed = ConfigManifestSupport.parseManifest(entry);
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        try {
            switch (parsed.manifest()) {
                case AdminSettingsManifest settings -> {
                    if (!ConfigManifestSupport.SETTINGS_SINGLETON_NAME.equals(parsed.name().name())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Settings name must be 'global'");
                    }
                }
                case AdminModelManifest modelManifest -> {
                    Model model = modelManifest.spec();
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validatePricing(model, warnings);
                    ConfigPostProcessor.validateUpstreamInterfaces(model, warnings);
                    ConfigPostProcessor.validateCrossReferences(model, scratch, warnings);
                    UpstreamExtraDataMerger.validateNoOverlap(model);
                    if (!warnings.isEmpty() && !softValidation) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                    String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.MODEL, parsed.name());
                    if (dupError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                    }
                }
                case AdminInterceptorManifest interceptor -> {
                    String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(
                            scratch, ResourceTypes.INTERCEPTOR, parsed.name());
                    if (dupError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                    }
                }
                case AdminTranslatorManifest translatorManifest -> {
                    Translator translator = translatorManifest.spec();
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validateTranslator(translator, warnings);
                    if (!warnings.isEmpty()) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                }
                case AdminRoleManifest role -> { }
                case AdminRouteManifest route -> { }
                case AdminKeyManifest keyManifest -> {
                    Key key = keyManifest.spec();
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
                case AdminApplicationManifest application -> {
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.name().bucket())) {
                        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(
                                scratch, ResourceTypes.APPLICATION, parsed.name());
                        if (dupError != null) {
                            return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                        }
                    }
                }
                case AdminToolSetManifest toolSet -> {
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.name().bucket())) {
                        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(
                                scratch, ResourceTypes.TOOL_SET, parsed.name());
                        if (dupError != null) {
                            return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                        }
                    }
                }
                case AdminSchemaManifest schema -> {
                    String schemaError = ConfigManifestSupport.validateSchema(schema.spec(), parsed.name(), scratch,
                            ResourceTypes.APP_TYPE_SCHEMA, resourceService);
                    if (schemaError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, schemaError);
                    }
                }
                case AdminCatalogSchemaManifest catalogSchema -> {
                    String schemaError = ConfigManifestSupport.validateSchema(catalogSchema.spec(), parsed.name(), scratch,
                            ResourceTypes.CATALOG_SCHEMA, resourceService);
                    if (schemaError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, schemaError);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        return new ValidationResult(id, ValidationStatus.VALID, null);
    }
}
