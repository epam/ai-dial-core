package com.epam.aidial.core.mcp.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceIdTest {

    @Test
    void parsesFlatIdAcrossKnownTypes() {
        ResourceId model = ResourceId.parse("models/public/gpt-4");
        assertEquals("models", model.type());
        assertEquals("gpt-4", model.name());

        ResourceId app = ResourceId.parse("applications/public/my-app");
        assertEquals("applications", app.type());
        assertEquals("my-app", app.name());

        ResourceId settings = ResourceId.parse("settings/platform/global");
        assertEquals("global", settings.name());
    }

    @Test
    void parsesHierarchicalIdWithEmbeddedSlashes() {
        ResourceId id = ResourceId.parse("files/abc/photos/cover.png");
        assertEquals("files", id.type());
        assertEquals("abc", id.bucket());
        assertEquals("photos/cover.png", id.name());
    }

    @Test
    void rejectsUnknownType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ResourceId.parse("unknown/public/x"));
        assertTrue(ex.getMessage().contains("dial_describe_schema"));
    }

    @Test
    void rejectsMalformedId() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.parse("tooFew"));
        assertThrows(IllegalArgumentException.class, () -> ResourceId.parse("models/public/"));
    }

    @Test
    void parseListPathReturnsEmptySubPathByDefault() {
        ResourceId id = ResourceId.parseListPath("files/abc/");
        assertEquals("files", id.type());
        assertEquals("abc", id.bucket());
        assertEquals("", id.name());
    }

    @Test
    void parseListPathCapturesSubPath() {
        ResourceId id = ResourceId.parseListPath("files/abc/photos/");
        assertEquals("photos/", id.name());

        ResourceId nested = ResourceId.parseListPath("conversations/abc/2026/may/");
        assertEquals("2026/may/", nested.name());
    }

    @Test
    void toCorePathRoutesConfigTypeViaV1() {
        ResourceId id = ResourceId.parse("models/public/gpt-4");
        assertEquals("/v1/models/public/gpt-4", id.toCorePath("public"));
    }

    @Test
    void toCorePathRoutesFilesViaMetadata() {
        ResourceId id = ResourceId.parse("files/abc/photos/cover.png");
        assertEquals("/v1/metadata/files/abc/photos/cover.png", id.toCorePath("abc"));
        assertTrue(id.supportsRecursive());
    }

    @Test
    void toCorePathRoutesAppsAndPromptsViaResource() {
        // applications/toolsets/prompts/conversations: instance GETs use the RESOURCE route,
        // not the metadata route — only the listing uses /v1/metadata/.
        ResourceId app = ResourceId.parse("applications/public/my-app");
        assertEquals("/v1/applications/public/my-app", app.toCorePath("public"));

        ResourceId prompt = ResourceId.parse("prompts/abc/intro");
        assertEquals("/v1/prompts/abc/intro", prompt.toCorePath("abc"));
    }

    @Test
    void toMutationCorePathSkipsMetadataPrefixForFiles() {
        ResourceId fileId = ResourceId.parse("files/abc/photos/cover.png");
        assertEquals("/v1/metadata/files/abc/photos/cover.png", fileId.toCorePath("abc"));
        assertEquals("/v1/files/abc/photos/cover.png", fileId.toMutationCorePath("abc"));

        ResourceId model = ResourceId.parse("models/public/gpt-4");
        assertEquals(model.toCorePath("public"), model.toMutationCorePath("public"));

        ResourceId settings = ResourceId.parse("settings/platform/global");
        assertEquals("/v1/settings/platform/global", settings.toMutationCorePath("platform"));
    }

    @Test
    void toListCorePathRoutesPerType() {
        ResourceId models = ResourceId.parseListPath("models/public/");
        assertEquals("/v1/models/public/", models.toListCorePath("public"));
        assertFalse(models.supportsRecursive());

        ResourceId apps = ResourceId.parseListPath("applications/public/");
        assertEquals("/v1/metadata/applications/public/", apps.toListCorePath("public"));
        assertTrue(apps.supportsRecursive());

        ResourceId filesSub = ResourceId.parseListPath("files/abc/photos/");
        assertEquals("/v1/metadata/files/abc/photos/", filesSub.toListCorePath("abc"));
    }
}
