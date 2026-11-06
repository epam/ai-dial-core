package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.AliasTarget;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasPassesTest {

    private static final String SELF = "http://localhost:8080/";

    @Test
    void modelPassForwardsToCoreForTheTarget() {
        Model target = new Model();
        target.setName("gpt-4");
        target.setType(ModelType.CHAT);
        target.setBaseUrl("http://adapter");
        target.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface(),
                "openaiResponses", new DeploymentInterface()));

        Deployment pass = AliasPasses.build(alias(AliasPresentation.MODEL), AliasPresentation.MODEL, target, "gpt-4", false, SELF);

        assertInstanceOf(Application.class, pass);
        assertInstanceOf(AliasPass.class, pass);
        assertEquals("assistant", pass.getName());
        assertEquals("gpt-4", pass.getOverrideName());
        assertEquals("gpt-4", ((AliasPass) pass).getTargetName());
        assertNull(pass.getEndpoint());
        assertEquals(Set.of("openaiChatCompletions", "openaiResponses"), pass.getInterfaces().keySet());
        DeploymentInterface chat = pass.getInterfaces().get("openaiChatCompletions");
        assertEquals("http://localhost:8080", chat.getBaseUrl());
        assertEquals(Map.of("postAzureOpenaiChatCompletions", "/openai/deployments/gpt-4/chat/completions"), chat.getOverridePaths());
        assertEquals(Map.of(), pass.getInterfaces().get("openaiResponses").getOverridePaths());
        assertEquals("http://localhost:8080/openai/deployments/gpt-4/chat/completions",
                DeploymentEndpointUtil.resolveRequestUri(pass, InterfaceType.OPENAI_CHAT_COMPLETIONS, Map.of(),
                        "/openai/deployments/assistant/chat/completions", null));
    }

    @Test
    void legacyEndpointTargetGetsTheInterfacesItServes() {
        Model target = new Model();
        target.setType(ModelType.EMBEDDING);
        target.setEndpoint("http://adapter/embeddings");

        Deployment pass = AliasPasses.build(alias(AliasPresentation.MODEL), AliasPresentation.MODEL, target, "ada", false, SELF);

        assertEquals(Set.of("openaiEmbeddings"), pass.getInterfaces().keySet());
    }

    @Test
    void mcpApplicationKeepsItsMcpBehindTheAlias() {
        Application target = mcpApplication();

        Application pass = (Application) AliasPasses.build(alias(AliasPresentation.APPLICATION), AliasPresentation.APPLICATION, target, "mcp-app", false, SELF);

        assertEquals("http://localhost:8080/v1/deployments/mcp-app/mcp", pass.getMcp().getEndpoint());
        assertEquals(List.of("search"), pass.getMcp().getAllowedTools());
    }

    @Test
    void toolsetPassForwardsToTheToolsetOrTheMcpApplication() {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("http://search/mcp");
        toolSet.setAllowedTools(List.of("find"));

        ToolSet pass = (ToolSet) AliasPasses.build(alias(AliasPresentation.TOOLSET), AliasPresentation.TOOLSET, toolSet, "search", false, SELF);
        assertEquals("http://localhost:8080/v1/toolset/search/mcp", pass.getEndpoint());
        assertTrue(pass.isForwardPerRequestKey());
        assertEquals(List.of("find"), pass.getAllowedTools());

        ToolSet appPass = (ToolSet) AliasPasses.build(alias(AliasPresentation.TOOLSET), AliasPresentation.TOOLSET, mcpApplication(), "mcp-app", false, SELF);
        assertEquals("http://localhost:8080/v1/deployments/mcp-app/mcp", appPass.getEndpoint());
        assertEquals(List.of("search"), appPass.getAllowedTools());
    }

    @Test
    void targetNameIsOnePathSegment() {
        Model target = new Model();
        target.setEndpoint("http://adapter/chat");

        Deployment pass = AliasPasses.build(alias(AliasPresentation.MODEL), AliasPresentation.MODEL, target, "gpt 4%", false, SELF);

        assertEquals("/openai/deployments/gpt%204%25/chat/completions",
                pass.getInterfaces().get("openaiChatCompletions").getOverridePaths().get("postAzureOpenaiChatCompletions"));
        assertEquals("gpt 4%", pass.getOverrideName());
    }

    @Test
    void modelViewTakesDisplayFromTheAliasAndPricingFromTheTarget() {
        Model target = new Model();
        target.setName("gpt-4");
        target.setType(ModelType.CHAT);
        target.setDisplayName(LocalizedValue.of("GPT-4"));
        target.setEndpoint("http://adapter/chat");
        Pricing pricing = new Pricing();
        target.setPricing(pricing);
        DeploymentInterface chat = new DeploymentInterface();
        chat.setBaseUrl("http://adapter");
        chat.setDefaultHeaders(Map.of("x-secret", "1"));
        target.setInterfaces(Map.of("openaiChatCompletions", chat));
        target.setDefaultHeaders(Map.of("x-secret", "2"));
        target.setUserRoles(Set.of("power-user"));

        Model view = (Model) AliasPasses.view(alias(AliasPresentation.MODEL), AliasPresentation.MODEL, target);

        assertEquals("assistant", view.getName());
        assertEquals(LocalizedValue.of("Assistant"), view.getDisplayName());
        assertEquals(Set.of("default-user"), view.getUserRoles());
        assertEquals(pricing, view.getPricing());
        assertEquals(ModelType.CHAT, view.getType());
        assertTrue(DeploymentEndpointUtil.isInterfaceDeclared(view, InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertEquals(Map.of(), view.getInterfaces().get("openaiChatCompletions").getDefaultHeaders());
        assertEquals(Map.of(), view.getDefaultHeaders());
    }

    @Test
    void applicationShownAsModelIsChatModel() {
        Application target = new Application();
        target.setEndpoint("http://app/chat");

        Model view = (Model) AliasPasses.view(alias(AliasPresentation.MODEL), AliasPresentation.MODEL, target);

        assertEquals(ModelType.CHAT, view.getType());
        assertNull(view.getPricing());
    }

    @Test
    void applicationAndToolsetViewsHideTheMcpEndpoint() {
        Application appView = (Application) AliasPasses.view(alias(AliasPresentation.APPLICATION), AliasPresentation.APPLICATION, mcpApplication());
        assertNotNull(appView.getMcp());
        assertNull(appView.getMcp().getEndpoint());

        ToolSet toolSetView = (ToolSet) AliasPasses.view(alias(AliasPresentation.TOOLSET), AliasPresentation.TOOLSET, mcpApplication());
        assertNull(toolSetView.getEndpoint());
        assertEquals(List.of("search"), toolSetView.getAllowedTools());
        assertFalse(toolSetView.isForwardPerRequestKey());
    }

    @Test
    void viewFallsBackToWhatTheTargetServesButNotToItsVersion() {
        Model target = new Model();
        target.setName("gpt-4");
        target.setDisplayName(LocalizedValue.of("GPT-4"));
        target.setDescription(LocalizedValue.of("The target."));
        target.setDisplayVersion("0613");
        target.setIconUrl("http://icons/gpt-4.png");
        target.setIntro(LocalizedValue.of("Ask me."));
        target.setDescriptionKeywords(List.of("chat"));
        target.setAuthor("Platform team");
        Alias alias = alias(AliasPresentation.MODEL);
        alias.setDisplayName(null);

        Model view = (Model) AliasPasses.view(alias, AliasPresentation.MODEL, target);

        assertEquals(LocalizedValue.of("GPT-4"), view.getDisplayName());
        assertEquals(LocalizedValue.of("The target."), view.getDescription());
        assertEquals("http://icons/gpt-4.png", view.getIconUrl());
        assertEquals(LocalizedValue.of("Ask me."), view.getIntro());
        assertEquals(List.of("chat"), view.getDescriptionKeywords());
        assertNull(view.getDisplayVersion());
        assertNull(view.getAuthor());
        assertEquals("assistant", view.getName());

        alias.setDisplayName(LocalizedValue.of("Assistant"));
        alias.setDescription(LocalizedValue.of("Own."));
        Model own = (Model) AliasPasses.view(alias, AliasPresentation.MODEL, target);
        assertEquals(LocalizedValue.of("Assistant"), own.getDisplayName());
        assertEquals(LocalizedValue.of("Own."), own.getDescription());
    }

    @Test
    void toolsetViewTellsHowToSignInWithoutTheSecrets() {
        ToolSet target = new ToolSet();
        target.setName("search");
        target.setEndpoint("http://tools/mcp");
        target.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("client")
                .clientSecret("secret")
                .codeVerifier("verifier")
                .build());

        ToolSet view = (ToolSet) AliasPasses.view(alias(AliasPresentation.TOOLSET), AliasPresentation.TOOLSET, target);

        assertEquals(AuthenticationType.OAUTH, view.getAuthSettings().getAuthenticationType());
        assertEquals("client", view.getAuthSettings().getClientId());
        assertNull(view.getAuthSettings().getClientSecret());
        assertNull(view.getAuthSettings().getCodeVerifier());
        assertEquals("secret", target.getAuthSettings().getClientSecret());
    }

    private static Application mcpApplication() {
        Application target = new Application();
        target.setName("mcp-app");
        Application.Mcp mcp = new Application.Mcp();
        mcp.setEndpoint("http://app/mcp");
        mcp.setAllowedTools(List.of("search"));
        target.setMcp(mcp);
        return target;
    }

    private static Alias alias(AliasPresentation presentation) {
        Alias alias = new Alias();
        alias.setName("assistant");
        alias.setPresentAs(presentation);
        alias.setDisplayName(LocalizedValue.of("Assistant"));
        alias.setUserRoles(Set.of("default-user"));
        alias.setTarget(new AliasTarget().setPath("models/gpt-4"));
        return alias;
    }
}
