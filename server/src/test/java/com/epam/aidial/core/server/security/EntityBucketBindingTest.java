package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.data.RouteTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the slice 1S.0 routing + allowlist scaffold. No HTTP / no Redis — pure
 * structural assertions on {@link RouteTemplate#CONFIG_RESOURCE} and
 * {@link EntityBucketBinding#isAllowed(String, String)}.
 */
class EntityBucketBindingTest {

    @Test
    void configResourceRegexMatchesAdminConfigTypes() {
        // U.0 (2026-05-20): the path group is mandatory — the trailing-slash variant matches with
        // empty path (handler returns 404 for the empty path), the no-slash variant does NOT match
        // and falls through to the default 404.
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/models/platform/gpt-4"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/models/platform/"));
        assertFalse(RouteTemplate.CONFIG_RESOURCE.matches("/v1/models/platform"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/interceptors/platform/guardrail"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/roles/platform/viewer"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/keys/platform/proxyKey1"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/routes/platform/route1"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/schemas/platform/example"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE.matches("/v1/settings/platform/global"));
    }

    @Test
    void configResourceMetadataRegexMatches() {
        // U.0 sibling metadata route. Same alternation, mandatory path group.
        assertTrue(RouteTemplate.CONFIG_RESOURCE_METADATA.matches("/v1/metadata/models/platform/"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE_METADATA.matches("/v1/metadata/models/platform/gpt-4"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE_METADATA.matches("/v1/metadata/interceptors/platform/"));
        assertTrue(RouteTemplate.CONFIG_RESOURCE_METADATA.matches("/v1/metadata/settings/platform/"));
        assertFalse(RouteTemplate.CONFIG_RESOURCE_METADATA.matches("/v1/metadata/widgets/public/foo"));
        assertFalse(RouteTemplate.CONFIG_RESOURCE_METADATA.matches("/v1/models/platform/gpt-4"));
    }

    @Test
    void configResourceRegexRejectsNonAdminConfigTypes() {
        // RESOURCE-bound types stay out of CONFIG_RESOURCE — they route via existing RouteTemplate.RESOURCE.
        assertFalse(RouteTemplate.CONFIG_RESOURCE.matches("/v1/conversations/Userbucket123/conv1"));
        assertFalse(RouteTemplate.CONFIG_RESOURCE.matches("/v1/applications/public/app"));
        // Files keep their dedicated FILES route.
        assertFalse(RouteTemplate.CONFIG_RESOURCE.matches("/v1/files/Userbucket123/file.txt"));
        // Unknown type — caller cannot enumerate the closed alternation.
        assertFalse(RouteTemplate.CONFIG_RESOURCE.matches("/v1/widgets/public/foo"));
    }

    @Test
    void allowsKnownPairs() {
        assertTrue(EntityBucketBinding.isAllowed("models", EntityBucketBinding.PLATFORM_BUCKET));
        assertTrue(EntityBucketBinding.isAllowed("schemas", EntityBucketBinding.PLATFORM_BUCKET));
        assertTrue(EntityBucketBinding.isAllowed("interceptors", EntityBucketBinding.PLATFORM_BUCKET));
        assertTrue(EntityBucketBinding.isAllowed("settings", EntityBucketBinding.PLATFORM_BUCKET));
        assertTrue(EntityBucketBinding.isAllowed("applications", "public"));
        assertTrue(EntityBucketBinding.isAllowed("toolsets", "public"));
        // files / prompts / conversations also accept user buckets via the wildcard.
        assertTrue(EntityBucketBinding.isAllowed("files", "Userbucket123"));
        assertTrue(EntityBucketBinding.isAllowed("prompts", "Userbucket123"));
    }

    @Test
    void rejectsMismatchedPairs() {
        assertFalse(EntityBucketBinding.isAllowed("interceptors", "public"));
        assertFalse(EntityBucketBinding.isAllowed("keys", "public"));
        assertFalse(EntityBucketBinding.isAllowed("models", "public"));
        assertFalse(EntityBucketBinding.isAllowed("schemas", "public"));
        // Wildcard does not extend to the platform bucket — only public + user buckets.
        assertFalse(EntityBucketBinding.isAllowed("files", EntityBucketBinding.PLATFORM_BUCKET));
        // Unknown entity type.
        assertFalse(EntityBucketBinding.isAllowed("widgets", "public"));
    }
}
