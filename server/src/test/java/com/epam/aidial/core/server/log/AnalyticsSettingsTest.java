package com.epam.aidial.core.server.log;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnalyticsSettingsTest {

    @Test
    public void testInlineFileDefaults() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject());

        assertTrue(settings.collectInlineFiles());
        assertTrue(settings.collectTruncatedInlineFiles());
        assertFalse(settings.collectInlineFileIndex());
        assertTrue(settings.collectDuplicateAssembledResponse());
    }

    @Test
    public void testInlineFileSettingsAreParsed() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject()
                .put("collectInlineFiles", false)
                .put("collectTruncatedInlineFiles", false)
                .put("collectInlineFileIndex", true)
                .put("collectDuplicateAssembledResponse", false));

        assertFalse(settings.collectInlineFiles());
        assertFalse(settings.collectTruncatedInlineFiles());
        assertTrue(settings.collectInlineFileIndex());
        assertFalse(settings.collectDuplicateAssembledResponse());
    }

    @Test
    public void testBundledInlineFileDefaultsMatchCodeDefaults() throws Exception {
        // the test classpath has its own aidial.settings.json without an analytics block, so read the bundled file by path
        Path bundledPath = Path.of("src/main/resources/aidial.settings.json");
        JsonObject bundled = new JsonObject(Files.readString(bundledPath, StandardCharsets.UTF_8)).getJsonObject("analytics");
        AnalyticsSettings defaults = AnalyticsSettings.from(new JsonObject());

        assertEquals(defaults.collectInlineFiles(), bundled.getBoolean("collectInlineFiles"));
        assertEquals(defaults.collectTruncatedInlineFiles(), bundled.getBoolean("collectTruncatedInlineFiles"));
        assertEquals(defaults.collectInlineFileIndex(), bundled.getBoolean("collectInlineFileIndex"));
        assertEquals(defaults.collectDuplicateAssembledResponse(), bundled.getBoolean("collectDuplicateAssembledResponse"));
    }

    @Test
    public void testDefaults() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject());

        assertFalse(settings.collectClaims());
        assertFalse(settings.collectAllClaims());
        assertTrue(settings.claimsAllowlist().isEmpty());
        assertFalse(settings.claimsEnabled());
        assertFalse(settings.collectHeaders());
        assertTrue(settings.headersBlacklist().isEmpty());
        // the allowlist is disabled unless the key is present
        assertNull(settings.headersAllowlist());
    }

    @Test
    public void testClaimPathsAreSplitAndKeptInOrder() {
        AnalyticsSettings settings = claims("email", "resource_access.roles");

        assertEquals(List.of("email", "resource_access.roles"), names(settings));
        assertEquals(List.of("resource_access", "roles"), settings.claimsAllowlist().get(1).segments());
    }

    @Test
    public void testClaimPathsAreTrimmedAndDeduplicated() {
        AnalyticsSettings settings = claims(" email ", "email", "", "  ");

        assertEquals(List.of("email"), names(settings));
    }

    @Test
    public void testWildcardIsDetectedAndExcludedFromThePaths() {
        AnalyticsSettings settings = claims("*", "email");

        assertTrue(settings.collectAllClaims());
        assertEquals(List.of("email"), names(settings));
        assertTrue(settings.claimsEnabled());
    }

    @Test
    public void testMalformedPathsAreRejected() {
        // "." would otherwise resolve to the root of the claim payload and dump every claim
        AnalyticsSettings settings = claims(".", "..", ".email", "email..upn", "email.", "email");

        assertEquals(List.of("email"), names(settings));
        assertFalse(settings.collectAllClaims());
    }

    @Test
    public void testClaimsEnabledBySettingAlone() {
        assertTrue(AnalyticsSettings.from(new JsonObject().put("collectClaims", true)).claimsEnabled());
        assertTrue(claims("email").claimsEnabled());
        assertTrue(claims("*").claimsEnabled());
        assertFalse(claims().claimsEnabled());
    }

    @Test
    public void testInvalidHeaderPatternIsIgnored() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject()
                .put("collectHeaders", true)
                .put("headersBlacklist", new JsonArray(List.of("authorization", "x-[", "  "))));

        assertEquals(List.of("authorization"), settings.headersBlacklist().stream().map(Object::toString).toList());
    }

    @Test
    public void testHeaderPatternsAreCaseInsensitive() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject()
                .put("headersAllowlist", new JsonArray(List.of("x-stainless-.*"))));

        Assertions.assertNotNull(settings.headersAllowlist());
        assertTrue(settings.headersAllowlist().getFirst().matcher("X-Stainless-Lang").matches());
    }

    private static AnalyticsSettings claims(String... claimPaths) {
        return AnalyticsSettings.from(new JsonObject().put("claimsAllowlist", new JsonArray(List.of(claimPaths))));
    }

    private static List<String> names(AnalyticsSettings settings) {
        return settings.claimsAllowlist().stream().map(AnalyticsSettings.ClaimPath::name).toList();
    }
}
