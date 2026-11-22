package com.epam.aidial.core.server.data;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RouteTemplateTest {

    @Test
    void consentAndManagementRoutesCannotCrossMatch() {
        String management = "/v1/applications/my-app/external-services/dial";
        String consent = "/v1/applications/my-app/external-services/dial/consent";

        assertTrue(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT.matches(management));
        assertTrue(RouteTemplate.EXTERNAL_SERVICE_CONSENT.matches(consent));
        assertFalse(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT.matches(consent));
        assertFalse(RouteTemplate.EXTERNAL_SERVICE_CONSENT.matches(management));
    }

    @Test
    void consentRoutesStaySeparateForPathologicalNames() {
        // The service id disallows '/', and both patterns are $-anchored, so neither an app path containing
        // an "external-services" segment nor a service literally named "consent" can cross the boundary.
        String consentUnderNestedAppPath = "/v1/applications/public/my/external-services/app1/external-services/dial/consent";
        String serviceNamedConsent = "/v1/applications/my-app/external-services/consent";

        assertTrue(RouteTemplate.EXTERNAL_SERVICE_CONSENT.matches(consentUnderNestedAppPath));
        assertFalse(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT.matches(consentUnderNestedAppPath));
        assertTrue(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT.matches(serviceNamedConsent));
        assertFalse(RouteTemplate.EXTERNAL_SERVICE_CONSENT.matches(serviceNamedConsent));
    }

    @Test
    void complexResourceRoutesMatchSkillsAndProjectsOnly() {
        for (String group : new String[] {"skills", "projects"}) {
            Matcher resource = RouteTemplate.COMPLEX_RESOURCE.getPattern().matcher("/v2/" + group + "/bucket/dir/name");
            assertTrue(resource.matches());
            assertEquals(group, resource.group("group"));
            assertTrue(RouteTemplate.COMPLEX_RESOURCE_FILE.matches("/v2/" + group + "/bucket/name/files/a/b.txt"));
            assertTrue(RouteTemplate.RESOURCE_FOLDER.matches("/v2/" + group + "/bucket/dir/"));
            assertTrue(RouteTemplate.COMPLEX_RESOURCE_METADATA.matches("/v2/metadata/" + group + "/bucket/dir/"));
            assertTrue(RouteTemplate.COMPLEX_RESOURCE_FILE_METADATA.matches("/v2/metadata/" + group + "/bucket/name/files"));
        }

        assertFalse(RouteTemplate.COMPLEX_RESOURCE.matches("/v2/prompts/bucket/name"));
        assertFalse(RouteTemplate.COMPLEX_RESOURCE_METADATA.matches("/v2/metadata/prompts/bucket/name"));
    }
}
