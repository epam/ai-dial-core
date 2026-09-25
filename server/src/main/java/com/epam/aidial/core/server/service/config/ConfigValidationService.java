package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.config.KeyValidator;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.ValidationWarning;
import com.epam.aidial.core.server.data.config.manifest.AdminApplicationManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminCatalogSchemaManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminInterceptorManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminKeyManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminModelManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminRoleManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminRouteManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminSchemaManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminSettingsManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminToolSetManifest;
import com.epam.aidial.core.server.data.config.manifest.AdminTranslatorManifest;
import com.epam.aidial.core.server.data.config.manifest.ValidationResult;
import com.epam.aidial.core.server.data.config.manifest.ValidationStatus;
import com.epam.aidial.core.server.service.CatalogSchemaService;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.validation.CatalogSchemaValidationException;
import com.epam.aidial.core.server.validation.ValidationUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import jakarta.validation.ConstraintViolationException;

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
    private final CatalogSchemaService catalogSchemaService;

    public ConfigValidationService(ResourceService resourceService, boolean softValidation,
                                   CatalogSchemaService catalogSchemaService) {
        this.resourceService = resourceService;
        this.softValidation = softValidation;
        this.catalogSchemaService = catalogSchemaService;
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
                case AdminSettingsManifest settingsManifest -> {
                    if (!ConfigManifestSupport.SETTINGS_SINGLETON_NAME.equals(parsed.name().name())) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Settings name must be 'global'");
                    }
                    ValidationUtil.validate(settingsManifest.spec());
                }
                case AdminModelManifest modelManifest -> {
                    Model model = modelManifest.spec();
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validateOverridePaths(model, warnings);
                    boolean invalidOverridePaths = !warnings.isEmpty();
                    ConfigPostProcessor.validatePricing(model, warnings);
                    ConfigPostProcessor.validateUpstreamInterfaces(model, warnings);
                    ConfigPostProcessor.validateCrossReferences(model, scratch, warnings);
                    UpstreamExtraDataMerger.validateNoOverlap(model);
                    // Override paths stay fatal in soft mode, matching ConfigApplyService#applyModel — otherwise
                    // precheck greenlights a batch whose real-apply phase refuses the model mid-write.
                    if (!warnings.isEmpty() && (invalidOverridePaths || !softValidation)) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                    try {
                        catalogSchemaService.validate(model);
                    } catch (CatalogSchemaValidationException e) {
                        return new ValidationResult(id, ValidationStatus.FAILED, "Catalog properties validation failed: " + e.getMessage());
                    }
                    String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(scratch, ResourceTypes.MODEL, parsed.name());
                    if (dupError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                    }
                }
                case AdminInterceptorManifest interceptorManifest -> {
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validateOverridePaths(interceptorManifest.spec(), warnings);
                    if (!warnings.isEmpty()) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                    String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(
                            scratch, ResourceTypes.INTERCEPTOR, parsed.name());
                    if (dupError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                    }
                }
                case AdminTranslatorManifest translatorManifest -> {
                    Translator translator = translatorManifest.spec();
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validateTranslator(id, translator, warnings);
                    if (!warnings.isEmpty()) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                }
                case AdminRoleManifest roleManifest -> { }
                case AdminRouteManifest routeManifest -> { }
                case AdminKeyManifest keyManifest -> {
                    Key key = keyManifest.spec();
                    String error = KeyValidator.validateRequiredFields(key);
                    if (error == null) {
                        String canonicalId = MergedConfigStore.canonicalId(
                                ResourceTypes.PROJECT_KEY, parsed.name().bucket(), parsed.name().name());
                        Key prior = scratch.getKeys().get(canonicalId);
                        String oldSecret = prior == null ? null : prior.getKey();
                        error = KeyValidator.validateSecretNotTaken(scratch, canonicalId, key, oldSecret);
                    }
                    if (error != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, error);
                    }
                }
                case AdminApplicationManifest applicationManifest -> {
                    List<ValidationWarning> warnings = new ArrayList<>();
                    ConfigPostProcessor.validateOverridePaths(applicationManifest.spec(), warnings);
                    if (!warnings.isEmpty()) {
                        return new ValidationResult(id, ValidationStatus.FAILED, ConfigManifestSupport.joinWarnings(warnings));
                    }
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.name().bucket())) {
                        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(
                                scratch, ResourceTypes.APPLICATION, parsed.name());
                        if (dupError != null) {
                            return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                        }
                    }
                }
                case AdminToolSetManifest toolSetManifest -> {
                    if (ResourceDescriptor.PLATFORM_BUCKET.equals(parsed.name().bucket())) {
                        String dupError = ConfigManifestSupport.validateDeploymentIdUniqueness(
                                scratch, ResourceTypes.TOOL_SET, parsed.name());
                        if (dupError != null) {
                            return new ValidationResult(id, ValidationStatus.FAILED, dupError);
                        }
                    }
                }
                case AdminSchemaManifest schemaManifest -> {
                    String schemaError = ConfigManifestSupport.validateSchema(schemaManifest.spec(), parsed.name(), scratch,
                            ResourceTypes.APP_TYPE_SCHEMA, resourceService);
                    if (schemaError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, schemaError);
                    }
                }
                case AdminCatalogSchemaManifest catalogSchemaManifest -> {
                    String schemaError = ConfigManifestSupport.validateSchema(catalogSchemaManifest.spec(), parsed.name(), scratch,
                            ResourceTypes.CATALOG_SCHEMA, resourceService);
                    if (schemaError != null) {
                        return new ValidationResult(id, ValidationStatus.FAILED, schemaError);
                    }
                }
            }
        } catch (IllegalArgumentException | ConstraintViolationException ex) {
            return new ValidationResult(id, ValidationStatus.FAILED, ex.getMessage());
        }
        return new ValidationResult(id, ValidationStatus.VALID, null);
    }
}
