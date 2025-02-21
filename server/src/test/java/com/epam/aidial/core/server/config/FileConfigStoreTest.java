package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.security.ApiKeyStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

@ExtendWith(MockitoExtension.class)
public class FileConfigStoreTest {

    @Mock
    private Vertx vertx;

    @Mock
    private ApiKeyStore apiKeyStore;

    @Test
    public void testLoad_ArrayMergeStrategy_Overwrite() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(true), apiKeyStore);
        Set<String> expectedUserRoles = Set.of("second_role1");

        Config config = fileConfigStore.get();

        Set<String> actualUserRoles = config.getModels().get("testModel").getUserRoles();
        Assertions.assertEquals(expectedUserRoles, actualUserRoles);
    }

    @Test
    public void testLoad_ArrayMergeStrategy_Concat() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(false), apiKeyStore);
        Set<String> expectedUserRoles = Set.of("first_role1", "second_role1");

        Config config = fileConfigStore.get();

        Set<String> actualUserRoles = config.getModels().get("testModel").getUserRoles();
        Assertions.assertEquals(expectedUserRoles, actualUserRoles);
    }

    @Test
    public void testLoad_DefaultArrayMergeStrategy_Concat() {
        FileConfigStore fileConfigStore = new FileConfigStore(vertx, prepareSettings(null), apiKeyStore);
        Set<String> expectedUserRoles = Set.of("first_role1", "second_role1");

        Config config = fileConfigStore.get();

        Set<String> actualUserRoles = config.getModels().get("testModel").getUserRoles();
        Assertions.assertEquals(expectedUserRoles, actualUserRoles);
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
