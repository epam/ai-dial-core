package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.service.ShortNameResolution;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Outcome;
import com.epam.aidial.core.server.service.ShortNameResolver;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The short-name resolver reports where a deployment name comes from (file, platform, skipped, not found or unknown) from the signals of the merged store.
 *
 * <p>Every scenario is built on a real {@link MergedConfigStore}, so the signals are the ones the store produces.
 * Where a scenario needs a lag between the store and the blob storage, it is set up explicitly with
 * {@link #platformBlobExists}. The resolver is built from one explicit snapshot: the merged {@code Config}, the
 * skipped entities and the file-sourced {@code Config}.
 */
public class ShortNameResolverTest extends MergedConfigStoreTestSupport {

    private ShortNameResolution resolve(MergedConfigStore store, String name) {
        return resolve(store, null, name);
    }

    private ShortNameResolution resolve(MergedConfigStore store, ResourceTypes hint, String name) {
        return new ShortNameResolver(store.get(), store.getInvalidEntities(), store.getFileSourcedConfig(), resourceService)
                .resolve(hint, name);
    }

    private static void assertFound(ShortNameResolution result, ResourceTypes type, Origin origin) {
        assertEquals(Outcome.FOUND, result.outcome());
        assertEquals(type, result.type());
        assertEquals(origin, result.origin());
    }

    private static void assertOriginUnknown(ShortNameResolution result) {
        assertEquals(Outcome.ORIGIN_UNKNOWN, result.outcome());
        assertNull(result.origin(), "no origin is claimed when the signals do not establish one");
    }

    // ---- FOUND: FILE and PLATFORM ---------------------------------------------------------------------------

    @Test
    public void testFileOnlyEntityIsFileOrigin() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.FILE);
        assertEquals("m", result.name());
        assertNull(result.canonicalId(), "a file entity has no platform canonical id");
        assertFalse(result.hasShadowedEntity());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    public void testPlatformOnlyEntityIsPlatformOriginWithCanonicalId() {
        fileConfigIs(new Config());
        stubBlobs(blob(ResourceTypes.MODEL, "m", modelBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.PLATFORM);
        assertEquals("models/platform/m", result.canonicalId());
        assertFalse(result.hasShadowedEntity());
    }

    @Test
    public void testFileEntityTakesPrecedenceOverPlatformBlobForEveryShortNameType() {
        Config file = new Config();
        file.setApplications(new LinkedHashMap<>(Map.of("an-app", appWithEndpoint(FILE_ENDPOINT))));
        ToolSet toolset = new ToolSet();
        toolset.setEndpoint(FILE_ENDPOINT);
        file.setToolsets(new LinkedHashMap<>(Map.of("a-toolset", toolset)));
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint(FILE_ENDPOINT);
        file.setInterceptors(new LinkedHashMap<>(Map.of("an-interceptor", interceptor)));
        fileConfigIs(file);
        stubBlobs(blob(ResourceTypes.APPLICATION, "an-app", "{\"endpoint\": \"" + BLOB_ENDPOINT + "\"}"),
                blob(ResourceTypes.TOOL_SET, "a-toolset", "{\"endpoint\": \"" + BLOB_ENDPOINT + "\", \"transport\": \"HTTP\"}"),
                blob(ResourceTypes.INTERCEPTOR, "an-interceptor", "{\"endpoint\": \"" + BLOB_ENDPOINT + "\"}"));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        for (Object[] expected : new Object[][] {{"an-app", ResourceTypes.APPLICATION}, {"a-toolset", ResourceTypes.TOOL_SET},
                {"an-interceptor", ResourceTypes.INTERCEPTOR}}) {
            ShortNameResolution result = resolve(store, (String) expected[0]);

            assertFound(result, (ResourceTypes) expected[1], Origin.FILE);
            assertNull(result.canonicalId(), expected[0] + ": the effective entity is the file entity");
            assertTrue(result.hasShadowedEntity(), expected[0] + ": the platform blob is shadowed");
        }
    }

    // ---- an invalid platform blob does not change the origin of a file entity --------------------------------

    @Test
    public void testUnparsableBlobOverFileEntityStillResolvesAsFileAndReportsTheSkippedBlob() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs(blob(ResourceTypes.MODEL, "m", "{ this is not json "));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.FILE);
        assertFalse(result.hasShadowedEntity(), "nothing is hidden: the blob never became effective");
        assertEquals(1, result.skipped().size());
        ShortNameResolution.Skipped skipped = result.skipped().get(0);
        assertEquals(ResourceTypes.MODEL, skipped.type());
        assertEquals("models/platform/m", skipped.canonicalId());
        assertEquals("api", skipped.source());
    }

    @Test
    public void testSkippedApplicationOfTheSameNameIsReportedWhileTheFileModelResolves() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "shared"));
        stubBlobs(blob(ResourceTypes.APPLICATION, "shared", applicationBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "shared");

        assertFound(result, ResourceTypes.MODEL, Origin.FILE);
        assertEquals(1, result.skipped().size(), "the application that lost the name is reported, not exported");
        ShortNameResolution.Skipped skipped = result.skipped().get(0);
        assertEquals(ResourceTypes.APPLICATION, skipped.type());
        assertEquals("applications/platform/shared", skipped.canonicalId());
        assertEquals("api", skipped.source());
    }

    // ---- ORIGIN_UNKNOWN: each disagreement of the three signals ----------------------------------------------------

    @Test
    public void testFileInstanceWithValidBlobAndNoSkippedRecordIsOriginUnknown() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobExists(ResourceTypes.MODEL, "m", true);

        assertOriginUnknown(resolve(store, "m"));
    }

    @Test
    public void testPlatformEntityWhoseBlobIsGoneIsOriginUnknown() {
        fileConfigIs(new Config());
        stubBlobs(blob(ResourceTypes.MODEL, "m", modelBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobExists(ResourceTypes.MODEL, "m", false);

        assertOriginUnknown(resolve(store, "m"));
    }

    @Test
    public void testMergedEntityOlderThanFileReloadIsOriginUnknown() {
        Config first = fileConfig(model(FILE_ENDPOINT), "m");
        fileConfigIs(first);
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        fileConfigIs(fileConfig(model("http://file.example/v2"), "m"));

        assertOriginUnknown(resolve(store, "m"));
    }

    // ---- SKIPPED, NOT_FOUND and a valid blob that the merged map does not hold -------------------------------------

    @Test
    public void testSkippedEntityWithNothingHoldingTheNameIsSkipped() {
        fileConfigIs(new Config());
        String body = "{\"type\":\"chat\",\"endpoint\":\"" + BLOB_ENDPOINT + "\",\"interceptors\":[\"missing\"]}";
        stubBlobs(blob(ResourceTypes.MODEL, "m", body));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertEquals(Outcome.SKIPPED, result.outcome(), "the skipped record wins over the existing blob");
        assertEquals(1, result.skipped().size());
        ShortNameResolution.Skipped skipped = result.skipped().get(0);
        assertEquals(ResourceTypes.MODEL, skipped.type());
        assertEquals("models/platform/m", skipped.canonicalId());
        assertEquals("api", skipped.source());
        assertFalse(skipped.reason().isBlank());
    }

    @Test
    public void testNameThatNothingHoldsAndNothingSkippedIsNotFound() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "nobody");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
        assertEquals("nobody", result.name());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    public void testValidPlatformBlobAbsentFromTheMergedMapIsOriginUnknown() {
        fileConfigIs(new Config());
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobExists(ResourceTypes.APPLICATION, "late", true);

        assertOriginUnknown(resolve(store, "late"));
    }

    // ---- the type hint -------------------------------------------------------------------------------------------

    @Test
    public void testInterceptorHintLooksOnlyAmongInterceptors() {
        Config file = new Config();
        file.setModels(new LinkedHashMap<>(Map.of("only-model", model(FILE_ENDPOINT))));
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint(FILE_ENDPOINT);
        file.setInterceptors(new LinkedHashMap<>(Map.of("only-interceptor", interceptor)));
        fileConfigIs(file);
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        assertFound(resolve(store, ResourceTypes.INTERCEPTOR, "only-interceptor"), ResourceTypes.INTERCEPTOR, Origin.FILE);
        assertEquals(Outcome.NOT_FOUND, resolve(store, ResourceTypes.INTERCEPTOR, "only-model").outcome(),
                "a model of that name is not an interceptor");
        assertFound(resolve(store, "only-model"), ResourceTypes.MODEL, Origin.FILE);
    }

    // ---- more than one deployment map holds the name ---------------------------------------------------------------

    @Test
    public void testNameHeldByTwoDeploymentMapsIsOriginUnknown() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "shared"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobBodyIs(ResourceTypes.APPLICATION, "shared", applicationBody(BLOB_ENDPOINT));
        store.applyReplicaEvent(platformDescriptor(ResourceTypes.APPLICATION, "shared"),
                ResourceEvent.Action.CREATE, null);
        Config merged = store.get();
        assertTrue(merged.getModels().containsKey("shared") && merged.getApplications().containsKey("shared"),
                "precondition: the replica path leaves both maps holding the name");
        platformBlobExists(ResourceTypes.APPLICATION, "shared", true);

        assertOriginUnknown(resolve(store, "shared"));
        platformBlobExists(ResourceTypes.APPLICATION, "shared", false);
        assertOriginUnknown(resolve(store, "shared"));
    }

    @Test
    public void testInterceptorHintResolvesTheInterceptorWhileAnotherTypeAlsoHoldsTheName() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "shared"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobBodyIs(ResourceTypes.INTERCEPTOR, "shared", interceptorBody(BLOB_ENDPOINT));
        store.applyReplicaEvent(platformDescriptor(ResourceTypes.INTERCEPTOR, "shared"), ResourceEvent.Action.CREATE, null);
        platformBlobExists(ResourceTypes.INTERCEPTOR, "shared", true);

        assertOriginUnknown(resolve(store, "shared"));
        assertFound(resolve(store, ResourceTypes.INTERCEPTOR, "shared"), ResourceTypes.INTERCEPTOR, Origin.PLATFORM);
    }

    // ---- skipped entities do not influence the entity that holds the name ------------------------------------------

    @Test
    public void testSkippedModelOfAnEarlierTypeDoesNotInfluenceTheApplicationThatHoldsTheName() {
        fileConfigIs(new Config());
        stubBlobs(blob(ResourceTypes.MODEL, "shared", "{ this is not json "),
                blob(ResourceTypes.APPLICATION, "shared", applicationBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        assertTrue(store.get().getApplications().containsKey("shared"), "precondition: the application is effective");
        assertTrue(store.getInvalidEntities().get(ResourceTypes.MODEL).containsKey("models/platform/shared"),
                "precondition: the model is recorded as skipped");

        ShortNameResolution result = resolve(store, "shared");

        assertFound(result, ResourceTypes.APPLICATION, Origin.PLATFORM);
        assertEquals(1, result.skipped().size(), "the skipped model is reported as before");
        assertEquals(ResourceTypes.MODEL, result.skipped().get(0).type());
    }

    // ---- contract of the resolver itself ---------------------------------------------------------------------------

    @Test
    public void testSkippedRecordsOfOtherTypesAreNotReportedForAnInterceptorLookup() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "shared"));
        stubBlobs(blob(ResourceTypes.APPLICATION, "shared", applicationBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution unhinted = resolve(store, "shared");
        ShortNameResolution hinted = resolve(store, ResourceTypes.INTERCEPTOR, "shared");

        assertEquals(List.of(ResourceTypes.APPLICATION), unhinted.skipped().stream().map(ShortNameResolution.Skipped::type).toList(),
                "precondition: the application that lost the name is recorded as skipped");
        assertEquals(Outcome.NOT_FOUND, hinted.outcome(), "no interceptor holds the name");
        assertTrue(hinted.skipped().isEmpty(), "a skipped application is not reported for an interceptor lookup");
    }
}
