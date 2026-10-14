package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and serialization state of a declared resource dependency, and of the
 * {@code resourceDependencies} section on {@link Application}. Target-language validation is
 * write-time validation and is covered by the server-side validator's tests.
 */
public class ResourceDependencyTest {

    // Definition bodies reach the POJO through ProxyUtil's mapper, which accepts enum names
    // case-insensitively — so the design's lowercase input ("read", "write") parses, while
    // serialization emits the enum names ("READ", "WRITE"); the normalization is expected.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

    @Test
    void parsesTheSnakeCaseFormWithDefaults() throws Exception {
        String json = """
                {
                    "kind": "dial.resourceLink",
                    "link_id": "lnk_1",
                    "target": {"path": "current-user/skills/"},
                    "access": ["read", "write"],
                    "required": true
                }
                """;
        ResourceDependency dependency = MAPPER.readValue(json, ResourceDependency.class);

        assertEquals(ResourceDependency.KIND, dependency.getKind());
        assertEquals("lnk_1", dependency.getLinkId());
        assertEquals("current-user/skills/", dependency.getTarget().getPath());
        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE), dependency.getAccess());
        assertTrue(dependency.isRequired());
    }

    @Test
    void parsesTheCamelCaseForm() throws Exception {
        ResourceDependency dependency = MAPPER.readValue(
                "{\"linkId\": \"lnk_2\", \"target\": {\"path\": \"files/public/policies/\"}}",
                ResourceDependency.class);

        assertEquals("lnk_2", dependency.getLinkId());
        assertEquals("files/public/policies/", dependency.getTarget().getPath());
    }

    @Test
    void defaultsWhenOptionalFieldsAreAbsent() throws Exception {
        ResourceDependency dependency = MAPPER.readValue(
                "{\"kind\": \"dial.resourceLink\", \"target\": {\"path\": \"current-user/\"}}",
                ResourceDependency.class);

        assertNull(dependency.getLinkId());
        assertTrue(dependency.getAccess().isEmpty());
        assertFalse(dependency.isRequired());
    }

    @Test
    void roundTripsThroughSerialization() throws Exception {
        String json = """
                {
                    "kind": "dial.resourceLink",
                    "linkId": "lnk_1",
                    "target": {"path": "current-user/skills/"},
                    "access": ["write"],
                    "required": true
                }
                """;
        ResourceDependency dependency = MAPPER.readValue(json, ResourceDependency.class);

        assertEquals(dependency, MAPPER.readValue(MAPPER.writeValueAsString(dependency), ResourceDependency.class));
    }

    @Test
    void omitsDefaultValuesFromSerialization() throws Exception {
        ResourceDependency dependency = new ResourceDependency()
                .setKind(ResourceDependency.KIND)
                .setTarget(new ResourceDependency.Target().setPath("current-user/"));

        String json = MAPPER.writeValueAsString(dependency);

        assertFalse(json.contains("required"));
        assertFalse(json.contains("access"));
        assertEquals("{\"kind\":\"dial.resourceLink\",\"target\":{\"path\":\"current-user/\"}}", json);
    }

    @Test
    void serializesAccessAsEnumNamesWhileAcceptingLowercaseInput() throws Exception {
        ResourceDependency dependency = MAPPER.readValue("""
                {"kind": "dial.resourceLink", "target": {"path": "files/public/f/"}, "access": ["read", "write"]}
                """, ResourceDependency.class);

        String json = MAPPER.writeValueAsString(dependency);

        assertTrue(json.contains("\"READ\""));
        assertTrue(json.contains("\"WRITE\""));
    }

    @Test
    void applicationSectionParsesBothForms() throws Exception {
        String snake = """
                {"name": "app", "resource_dependencies": [{"link_id": "lnk_1", "target": {"path": "public/folder/"}}]}
                """;
        String camel = """
                {"name": "app", "resourceDependencies": [{"linkId": "lnk_1", "target": {"path": "public/folder/"}}]}
                """;

        assertEquals(MAPPER.readValue(snake, Application.class).getResourceDependencies(),
                MAPPER.readValue(camel, Application.class).getResourceDependencies());
    }

    @Test
    void absentSectionYieldsEmptyListAndIsOmittedFromSerialization() throws Exception {
        Application withoutSection = MAPPER.readValue("{\"name\": \"app\"}", Application.class);

        assertTrue(withoutSection.getResourceDependencies().isEmpty());
        assertFalse(MAPPER.writeValueAsString(withoutSection).contains("resource_dependencies"));
    }

    @Test
    void copyConstructorCarriesTheSection() {
        Application source = new Application()
                .setResourceDependencies(List.of(new ResourceDependency()
                        .setKind(ResourceDependency.KIND)
                        .setLinkId("lnk_1")
                        .setTarget(new ResourceDependency.Target().setPath("current-user/skills/"))
                        .setAccess(Set.of(ResourceAccessType.WRITE))
                        .setRequired(true)));

        Application copy = new Application(source);

        assertSame(source.getResourceDependencies(), copy.getResourceDependencies());
    }
}
