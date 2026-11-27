package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Outcome;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The short-name resolver reports where a deployment name comes from (file, platform, skipped, not found or unknown) from the signals of a
 * real {@link MergedConfigStore}. A lag between the store and the blob storage is set up with {@link #platformBlobExists}.
 */
public class ShortNameResolverTest extends MergedConfigStoreTestSupport {

    private ShortNameResolution resolve(MergedConfigStore store, String name) {
        return newResolver(store).resolve(name);
    }

    private ShortNameResolution resolveInterceptor(MergedConfigStore store, String name) {
        return newResolver(store).resolveInterceptor(name);
    }

    private ShortNameResolver newResolver(MergedConfigStore store) {
        return new ShortNameResolver(new ConfigSnapshot(store.get(), store.getFileSourcedConfig(), store.getInvalidEntities()), resourceService);
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

    @Test
    public void testFileOnlyEntityIsFileOrigin() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.FILE);
        assertEquals("m", result.name());
    }

    @Test
    public void testPlatformOnlyEntityIsPlatformOrigin() {
        fileConfigIs(new Config());
        stubBlobs(blob(ResourceTypes.MODEL, "m", modelBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.PLATFORM);
    }

    @Test
    public void testPlatformBlobWinsOverFileEntityForEveryShortNameType() {
        Config file = new Config();
        file.setApplications(new LinkedHashMap<>(Map.of("an-app", appWithEndpoint(FILE_ENDPOINT))));
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint(FILE_ENDPOINT);
        file.setInterceptors(new LinkedHashMap<>(Map.of("an-interceptor", interceptor)));
        fileConfigIs(file);
        stubBlobs(blob(ResourceTypes.APPLICATION, "an-app", "{\"endpoint\": \"" + BLOB_ENDPOINT + "\"}"),
                blob(ResourceTypes.INTERCEPTOR, "an-interceptor", "{\"endpoint\": \"" + BLOB_ENDPOINT + "\"}"));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        for (Object[] expected : new Object[][] {{"an-app", ResourceTypes.APPLICATION},
                {"an-interceptor", ResourceTypes.INTERCEPTOR}}) {
            ShortNameResolution result = resolve(store, (String) expected[0]);

            assertFound(result, (ResourceTypes) expected[1], Origin.PLATFORM);
        }
    }

    @Test
    public void testUnparsableBlobOverFileEntityStillResolvesAsFile() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs(blob(ResourceTypes.MODEL, "m", "{ this is not json "));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.FILE);
    }

    @Test
    public void testSkippedApplicationOfTheSameNameDoesNotHideTheFileModel() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "shared"));
        stubBlobs(blob(ResourceTypes.APPLICATION, "shared", applicationBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "shared");

        assertFound(result, ResourceTypes.MODEL, Origin.FILE);
    }

    @Test
    public void testFileInstanceWithValidBlobAndNoSkippedRecordIsPlatformOrigin() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobExists(ResourceTypes.MODEL, "m", true);

        ShortNameResolution result = resolve(store, "m");

        assertFound(result, ResourceTypes.MODEL, Origin.PLATFORM);
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

    @Test
    public void testSkippedEntityWithNothingHoldingTheNameIsSkipped() {
        fileConfigIs(new Config());
        String body = "{\"type\":\"chat\",\"endpoint\":\"" + BLOB_ENDPOINT + "\",\"interceptors\":[\"missing\"]}";
        stubBlobs(blob(ResourceTypes.MODEL, "m", body));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "m");

        assertEquals(Outcome.SKIPPED, result.outcome(), "the skipped record wins over the existing blob");
    }

    @Test
    public void testNameThatNothingHoldsAndNothingSkippedIsNotFound() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "m"));
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution result = resolve(store, "nobody");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
        assertEquals("nobody", result.name());
    }

    @Test
    public void testValidPlatformBlobAbsentFromTheMergedMapIsOriginUnknown() {
        fileConfigIs(new Config());
        stubBlobs();
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);
        platformBlobExists(ResourceTypes.APPLICATION, "late", true);

        assertOriginUnknown(resolve(store, "late"));
    }

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

        assertFound(resolveInterceptor(store, "only-interceptor"), ResourceTypes.INTERCEPTOR, Origin.FILE);
        assertEquals(Outcome.NOT_FOUND, resolveInterceptor(store, "only-model").outcome(),
                "a model of that name is not an interceptor");
        assertFound(resolve(store, "only-model"), ResourceTypes.MODEL, Origin.FILE);
    }

    /** A snapshot that a rebuild would refuse because two types hold the name; only the replica path of a real store can leave it behind. */
    private ShortNameResolver newResolverOver(Config merged, Config file) {
        return new ShortNameResolver(new ConfigSnapshot(merged, file, Map.of()), resourceService);
    }

    @Test
    public void testNameHeldByTwoDeploymentMapsIsOriginUnknown() {
        Config merged = fileConfig(model(FILE_ENDPOINT), "shared");
        merged.setApplications(new LinkedHashMap<>(Map.of("shared", appWithEndpoint(BLOB_ENDPOINT))));
        ShortNameResolver resolver = newResolverOver(merged, fileConfig(model(FILE_ENDPOINT), "shared"));
        platformBlobExists(ResourceTypes.APPLICATION, "shared", true);

        assertOriginUnknown(resolver.resolve("shared"));
        platformBlobExists(ResourceTypes.APPLICATION, "shared", false);
        assertOriginUnknown(resolver.resolve("shared"));
    }

    @Test
    public void testInterceptorLookupResolvesTheInterceptorWhileAnotherTypeAlsoHoldsTheName() {
        Config merged = fileConfig(model(FILE_ENDPOINT), "shared");
        Interceptor interceptor = new Interceptor();
        interceptor.setEndpoint(BLOB_ENDPOINT);
        merged.setInterceptors(new LinkedHashMap<>(Map.of("shared", interceptor)));
        ShortNameResolver resolver = newResolverOver(merged, fileConfig(model(FILE_ENDPOINT), "shared"));
        platformBlobExists(ResourceTypes.INTERCEPTOR, "shared", true);

        assertOriginUnknown(resolver.resolve("shared"));
        assertFound(resolver.resolveInterceptor("shared"), ResourceTypes.INTERCEPTOR, Origin.PLATFORM);
    }

    @Test
    public void testValueThatIsNotShortNameIsRefused() {
        ShortNameResolver resolver = newResolverOver(new Config(), new Config());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("models/platform/m"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveInterceptor("a/b"));
    }

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
    }

    @Test
    public void testSkippedEntityOfAnotherTypeDoesNotMakeAnInterceptorLookupSkipped() {
        fileConfigIs(fileConfig(model(FILE_ENDPOINT), "shared"));
        stubBlobs(blob(ResourceTypes.APPLICATION, "shared", applicationBody(BLOB_ENDPOINT)));
        MergedConfigStore store = newStore(MergedConfigStore.MODE_SKIP);

        ShortNameResolution unhinted = resolve(store, "shared");
        ShortNameResolution hinted = resolveInterceptor(store, "shared");

        assertEquals(Outcome.FOUND, unhinted.outcome(), "precondition: the file model holds the name");
        assertEquals(Outcome.NOT_FOUND, hinted.outcome(), "no interceptor holds the name");
    }
}
