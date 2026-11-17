package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfacePathMapping;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.Strings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the two deployments an {@link Alias} turns into: the pass that serves one call of it, and the view that
 * listings show. Both take the alias's name and the target's operational fields; neither changes the alias or the
 * target, which are shared by every request reading the config.
 */
@UtilityClass
public class AliasPasses {

    /**
     * The deployment serving one call of {@code alias}, shown as {@code presentation}: it forwards to
     * {@code localBaseUrl}, Core's own address, for {@code target}, so that the target runs as a second Core pass.
     *
     * @param targetName     the name {@code target} is called by: a config name, or a resource url, already path-encoded
     * @param resourceTarget whether {@code targetName} is a resource url
     */
    public Deployment build(Alias alias, AliasPresentation presentation, Deployment target, String targetName,
                            boolean resourceTarget, String localBaseUrl) {
        String base = Strings.CS.removeEnd(localBaseUrl, "/");
        String targetSegment = resourceTarget ? targetName : UrlUtil.encodePathSegment(targetName);
        if (presentation == AliasPresentation.TOOLSET) {
            AliasToolSetPass pass = new AliasToolSetPass(alias, targetName, resourceTarget);
            pass.setName(alias.getName());
            pass.setForwardPerRequestKey(true);
            if (target instanceof ToolSet toolSet) {
                pass.setEndpoint(base + "/v1/toolset/" + targetSegment + "/mcp");
                pass.setAllowedTools(toolSet.getAllowedTools());
                pass.setTransport(toolSet.getTransport());
            } else {
                pass.setEndpoint(base + "/v1/deployments/" + targetSegment + "/mcp");
                pass.setAllowedTools(((Application) target).getMcp().getAllowedTools());
            }
            return pass;
        }

        AliasApplicationPass pass = new AliasApplicationPass(alias, targetName, resourceTarget);
        pass.setName(alias.getName());
        // the target is named in the body of a Responses or Messages request, which EnhanceDeploymentRequestFn sets to
        // it; a body names a resource by its decoded url, the form the router hands to the deployment lookup
        pass.setOverrideName(resourceTarget ? UrlUtil.decodePath(targetName) : targetName);
        pass.setFeatures(passFeatures(target.getFeatures(), base, targetSegment));
        pass.setInputAttachmentTypes(target.getInputAttachmentTypes());
        pass.setMaxInputAttachments(target.getMaxInputAttachments());
        Map<String, DeploymentInterface> interfaces = new LinkedHashMap<>();
        for (InterfaceType type : InterfaceType.values()) {
            if (!DeploymentEndpointUtil.isInterfaceDeclared(target, type)) {
                continue;
            }
            DeploymentInterface declared = target.getInterfaces() == null ? null : target.getInterfaces().get(type.getValue());
            DeploymentInterface passInterface = new DeploymentInterface();
            passInterface.setBaseUrl(base);
            passInterface.setFeatures(declared == null ? null : declared.getFeatures());
            passInterface.setOverridePaths(overridePaths(type, targetSegment));
            interfaces.put(type.getValue(), passInterface);
        }
        pass.setInterfaces(interfaces);
        if (target instanceof Application application && application.getMcp() != null) {
            Application.Mcp mcp = new Application.Mcp();
            mcp.setEndpoint(base + "/v1/deployments/" + targetSegment + "/mcp");
            mcp.setAllowedTools(application.getMcp().getAllowedTools());
            pass.setMcp(mcp);
        }
        return pass;
    }

    /**
     * The target's features, with the endpoints of the deployment feature requests (rate, tokenize, truncate prompt,
     * configuration) moved to Core's own for the target, so that these requests run as a target pass too. Rate always
     * goes there: a target without a rate endpoint still records the rating. Never changes {@code target}.
     */
    private static Features passFeatures(Features target, String base, String targetSegment) {
        Features features = Features.merge(new Features(), target);
        String deployment = base + "/v1/deployments/" + targetSegment;
        features.setRateEndpoint(base + "/v1/" + targetSegment + "/rate");
        if (features.getTokenizeEndpoint() != null) {
            features.setTokenizeEndpoint(deployment + "/tokenize");
        }
        if (features.getTruncatePromptEndpoint() != null) {
            features.setTruncatePromptEndpoint(deployment + "/truncate_prompt");
        }
        if (features.getConfigurationEndpoint() != null) {
            features.setConfigurationEndpoint(deployment + "/configuration");
        }
        return features;
    }

    private static Map<String, String> overridePaths(InterfaceType type, String targetSegment) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> Map.of(InterfacePathMapping.POST_AZURE_OPENAI_CHAT_COMPLETIONS.getValue(),
                    "/openai/deployments/" + targetSegment + "/chat/completions");
            case OPENAI_EMBEDDINGS -> Map.of(InterfacePathMapping.POST_AZURE_OPENAI_EMBEDDINGS.getValue(),
                    "/openai/deployments/" + targetSegment + "/embeddings");
            case OPENAI_RESPONSES, ANTHROPIC_MESSAGES -> Map.of();
        };
    }

    /**
     * The deployment a listing shows for {@code alias}: of the class {@code presentation} names, with the alias's
     * name, roles and display fields, and the target's features, pricing, limits, defaults and interfaces. The fields
     * that describe what the alias serves (display name, description, icon, intro, keywords) fall back to the
     * target's; those that describe the alias's own entry (display version, author, dates, catalog fields) do not. It
     * carries no endpoint, credentials, default headers or other internals of the target; a toolset view carries the
     * target's auth settings without their secrets, so that a caller knows to sign in.
     */
    public Deployment view(Alias alias, AliasPresentation presentation, Deployment target) {
        Deployment view = switch (presentation) {
            case MODEL -> modelView(target);
            case APPLICATION -> applicationView(target);
            case TOOLSET -> toolSetView(target);
        };
        copyOperational(target, view);
        copyDisplay(alias, view);
        if (view.getDisplayName() == null) {
            view.setDisplayName(target.getDisplayName());
        }
        if (view.getDescription() == null) {
            view.setDescription(target.getDescription());
        }
        if (view.getIconUrl() == null) {
            view.setIconUrl(target.getIconUrl());
        }
        if (view.getIntro() == null) {
            view.setIntro(target.getIntro());
        }
        if ((alias.getDescriptionKeywords() == null || alias.getDescriptionKeywords().isEmpty()) && target.getDescriptionKeywords() != null) {
            view.setDescriptionKeywords(target.getDescriptionKeywords());
        }
        return view;
    }

    private static Model modelView(Deployment target) {
        Model view = new Model();
        if (target instanceof Model model) {
            view.setType(model.getType());
            view.setTokenizerModel(model.getTokenizerModel());
            view.setLimits(model.getLimits());
            view.setPricing(model.getPricing());
            view.setEmbeddingDimensions(model.getEmbeddingDimensions());
            view.setProvider(model.getProvider());
            view.setDeprecationDate(model.getDeprecationDate());
            view.setEndOfService(model.getEndOfService());
        } else {
            view.setType(ModelType.CHAT);
        }
        return view;
    }

    private static Application applicationView(Deployment target) {
        Application view = new Application();
        if (target instanceof Application application && application.getMcp() != null) {
            // tells listings that the alias serves MCP; the target's endpoint stays out
            view.setMcp(new Application.Mcp());
        }
        return view;
    }

    private static ToolSet toolSetView(Deployment target) {
        ToolSet view = new ToolSet();
        if (target instanceof ToolSet toolSet) {
            view.setTransport(toolSet.getTransport());
            view.setAllowedTools(toolSet.getAllowedTools());
            view.setProvider(toolSet.getProvider());
            view.setVendorWebsite(toolSet.getVendorWebsite());
            if (toolSet.getAuthSettings() != null) {
                view.setAuthSettings(toolSet.getAuthSettings().toBuilder()
                        .clientSecret(null)
                        .clientSecretHint(null)
                        .codeVerifier(null)
                        .build());
            }
        } else {
            view.setAllowedTools(((Application) target).getMcp().getAllowedTools());
        }
        return view;
    }

    /**
     * The fields listings read to say what the deployment serves. The url fields are kept only so that
     * {@link DeploymentEndpointUtil#isInterfaceDeclared} answers for the view as for the target; no listing writes them.
     */
    private static void copyOperational(Deployment target, Deployment view) {
        view.setEndpoint(target.getEndpoint());
        view.setResponsesEndpoint(target.getResponsesEndpoint());
        view.setBaseUrl(target.getBaseUrl());
        Map<String, DeploymentInterface> interfaces = new LinkedHashMap<>();
        if (target.getInterfaces() != null) {
            target.getInterfaces().forEach((type, declared) -> {
                if (declared != null) {
                    DeploymentInterface copy = new DeploymentInterface();
                    copy.setBaseUrl(declared.getBaseUrl());
                    copy.setMode(declared.getMode());
                    copy.setTranslator(declared.getTranslator());
                    copy.setFeatures(declared.getFeatures());
                    copy.setDefaults(declared.getDefaults());
                    interfaces.put(type, copy);
                }
            });
        }
        view.setInterfaces(interfaces);
        view.setFeatures(target.getFeatures());
        view.setInputAttachmentTypes(target.getInputAttachmentTypes());
        view.setMaxInputAttachments(target.getMaxInputAttachments());
        view.setDefaults(target.getDefaults());
        view.setResponsesDefaults(target.getResponsesDefaults());
        view.setMaxRetryAttempts(target.getMaxRetryAttempts());
    }

    private static void copyDisplay(Alias alias, Deployment view) {
        view.setName(alias.getName());
        view.setUserRoles(alias.getUserRoles());
        view.setDisplayName(alias.getDisplayName());
        view.setDisplayVersion(alias.getDisplayVersion());
        view.setIconUrl(alias.getIconUrl());
        view.setDescription(alias.getDescription());
        view.setIntro(alias.getIntro());
        view.setDescriptionKeywords(alias.getDescriptionKeywords() == null ? List.of() : alias.getDescriptionKeywords());
        view.setAuthor(alias.getAuthor());
        view.setCreatedAt(alias.getCreatedAt());
        view.setUpdatedAt(alias.getUpdatedAt());
        view.setCatalogSchemaId(alias.getCatalogSchemaId());
        view.setCatalogProperties(alias.getCatalogProperties());
    }
}
