package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.AliasTarget;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasTargetsTest {

    @Test
    void parsesConfigDeploymentPaths() {
        assertEquals(new AliasTargets.TargetRef(ResourceTypes.MODEL, "gpt-4"), AliasTargets.parse("models/gpt-4"));
        assertEquals(new AliasTargets.TargetRef(ResourceTypes.APPLICATION, "app"), AliasTargets.parse("applications/app"));
        assertEquals(new AliasTargets.TargetRef(ResourceTypes.TOOL_SET, "search"), AliasTargets.parse("toolsets/search"));
        assertNull(AliasTargets.parse("aliases/other"));
    }

    @Test
    void refusesOtherPaths() {
        assertNull(AliasTargets.parse(null));
        assertNull(AliasTargets.parse("gpt-4"));
        assertNull(AliasTargets.parse("models/"));
        assertNull(AliasTargets.parse("/gpt-4"));
        assertNull(AliasTargets.parse("models/bucket/gpt-4"));
        assertNull(AliasTargets.parse("prompts/gpt-4"));
    }

    @Test
    void resolvesInTheMapOfItsType() {
        Config config = new Config();
        Model model = new Model();
        config.setModels(Map.of("x", model));
        Application application = new Application();
        config.setApplications(Map.of("x", application));
        ToolSet toolSet = new ToolSet();
        config.setToolsets(Map.of("x", toolSet));

        assertSame(model, AliasTargets.resolve(config, AliasTargets.parse("models/x")));
        assertSame(application, AliasTargets.resolve(config, AliasTargets.parse("applications/x")));
        assertSame(toolSet, AliasTargets.resolve(config, AliasTargets.parse("toolsets/x")));
        assertNull(AliasTargets.resolve(config, AliasTargets.parse("models/y")));
    }

    @Test
    void presentationFitsTheTargetType() {
        Application mcpApp = new Application();
        mcpApp.setMcp(new Application.Mcp());

        assertTrue(AliasTargets.fits(AliasPresentation.MODEL, new Model()));
        assertTrue(AliasTargets.fits(AliasPresentation.MODEL, new Application()));
        assertTrue(AliasTargets.fits(AliasPresentation.APPLICATION, new Model()));
        assertTrue(AliasTargets.fits(AliasPresentation.TOOLSET, new ToolSet()));
        assertTrue(AliasTargets.fits(AliasPresentation.TOOLSET, mcpApp));
        assertFalse(AliasTargets.fits(AliasPresentation.MODEL, new ToolSet()));
        assertFalse(AliasTargets.fits(AliasPresentation.APPLICATION, new ToolSet()));
        assertFalse(AliasTargets.fits(AliasPresentation.TOOLSET, new Model()));
        assertFalse(AliasTargets.fits(AliasPresentation.TOOLSET, new Application()));
    }

    @Test
    void targetThatNoLongerFitsIsIncompatible() {
        Config config = new Config();
        config.setModels(Map.of("m", new Model()));
        config.setToolsets(Map.of("t", new ToolSet()));
        Application schemaApp = new Application();
        schemaApp.setApplicationTypeSchemaId(URI.create("https://schemas/app"));
        config.setApplications(Map.of("s", schemaApp));

        assertTrue(AliasTargets.isTargetIncompatible(alias(AliasPresentation.MODEL, "toolsets/t"), config));
        assertFalse(AliasTargets.isTargetIncompatible(alias(AliasPresentation.MODEL, "models/m"), config));
        assertFalse(AliasTargets.isTargetIncompatible(alias(AliasPresentation.MODEL, "models/gone"), config));
        // the mcp of a schema-based application is in its schema, read only by a call
        assertFalse(AliasTargets.isTargetIncompatible(alias(AliasPresentation.TOOLSET, "applications/s"), config));
        assertTrue(AliasTargets.validate(alias(AliasPresentation.TOOLSET, "applications/s"), config).isEmpty());
        assertFalse(AliasTargets.isTargetIncompatible(alias(AliasPresentation.MODEL, "applications/public/app"), config));
    }

    private static Alias alias(AliasPresentation presentation, String path) {
        Alias alias = new Alias();
        alias.setName("a");
        alias.setPresentAs(presentation);
        alias.setTarget(new AliasTarget().setPath(path));
        return alias;
    }
}
