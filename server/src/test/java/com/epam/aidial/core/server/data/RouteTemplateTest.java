package com.epam.aidial.core.server.data;

import org.junit.jupiter.api.Test;

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
}
