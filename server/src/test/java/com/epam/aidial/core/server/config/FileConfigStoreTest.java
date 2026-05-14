package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.security.ApiKeyStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class FileConfigStoreTest {

    @Mock
    private Vertx vertx;

    @Mock
    private ApiKeyStore apiKeyStore;

    @Test
    public void testLoad_ArrayMergeStrategy_Overwrite() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(true), apiKeyStore, List.of());
        Set<String> expectedUserRoles = Set.of("second_role1");

        Config config = fileConfigStore.get();

        Set<String> actualUserRoles = config.getModels().get("testModel").getUserRoles();
        assertEquals(expectedUserRoles, actualUserRoles);
    }

    @Test
    public void testLoad_ArrayMergeStrategy_Concat() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(false), apiKeyStore, List.of());
        Set<String> expectedUserRoles = Set.of("first_role1", "second_role1");

        Config config = fileConfigStore.get();

        Set<String> actualUserRoles = config.getModels().get("testModel").getUserRoles();
        assertEquals(expectedUserRoles, actualUserRoles);
    }

    @Test
    public void testLoad_DefaultArrayMergeStrategy_Concat() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(null), apiKeyStore, List.of());
        Set<String> expectedUserRoles = Set.of("first_role1", "second_role1");

        Config config = fileConfigStore.get();

        Set<String> actualUserRoles = config.getModels().get("testModel").getUserRoles();
        assertEquals(expectedUserRoles, actualUserRoles);
    }

    @Test
    public void testLoad_OnlyValidToolSets() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(null), apiKeyStore, List.of());
        Set<String> expectedToolSetNames = Set.of("toolset-1_2");

        Config config = fileConfigStore.get();

        Set<String> actualToolSetNames = config.getToolsets().keySet();
        assertEquals(1, actualToolSetNames.size());
        assertEquals(expectedToolSetNames, actualToolSetNames);
    }

    @Test
    public void testLoad_RejectsCanonicalShapedFileKey() {
        JsonObject settings = new JsonObject();
        settings.put("files", new JsonArray(List.of(
                "com/epam/aidial/core/server/config/canonical-key.config.json")));
        settings.put("reload", 1000);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new FileConfigStore(vertx, settings, apiKeyStore, List.of()));
        assertTrue(error.getMessage().contains("keys/platform/my-key"), error.getMessage());
        assertTrue(error.getMessage().contains("canonical-ID shape"), error.getMessage());
    }

    @Test
    public void testInitialOnReloadCallbacksFiredOnInitialLoad() {
        AtomicReference<Config> seen = new AtomicReference<>();
        Consumer<Config> callback = seen::set;

        FileConfigStore fileConfigStore = new FileConfigStore(
                vertx, prepareSettings(null), apiKeyStore, List.of(callback));

        Config loaded = fileConfigStore.get();
        assertNotNull(seen.get());
        assertSame(loaded, seen.get());
    }

    private static JsonObject prepareSettings(@Nullable Boolean overwriteArrays) {
        JsonObject settings = new JsonObject();

        settings.put("files", new JsonArray(
                List.of(
                        "com/epam/aidial/core/server/config/first.config.json",
                        "com/epam/aidial/core/server/config/second.config.json"
                ))
        );
        settings.put("reload", 1000);

        if (overwriteArrays != null) {
            settings.put("jsonMergeStrategy", new JsonObject().put("overwriteArrays", overwriteArrays));
        }

        return settings;
    }
}
