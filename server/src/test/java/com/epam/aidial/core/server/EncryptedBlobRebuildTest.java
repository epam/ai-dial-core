package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptedBlobRebuildTest extends ResourceBaseTest {

    @Test
    void testEncEnvelopeDecryptsIntoConfig() throws Exception {
        SecretFieldProcessor processor = readSecretFieldProcessor();
        assertNotNull(processor);

        // Build a Key entity, encrypt its secret using the running runtime's processor, then
        // serialize the resulting blob (with ENC[...] envelope) to storage.
        Key entity = new Key();
        entity.setKey("plain-rebuild-secret");
        entity.setProject("test-project");
        entity.setRole("default");
        String name = "enc-envelope-key";
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.PROJECT_KEY, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, name);

        processor.encryptFields(entity, descriptor);
        String envelopedKey = entity.getKey();
        assertTrue(envelopedKey != null && envelopedKey.startsWith("ENC["),
                () -> "encrypt should produce ENC[...] envelope: " + envelopedKey);

        // Persist the blob with the ENC[...] string already in the JSON.
        String body = "{\"key\":\"" + envelopedKey + "\",\"project\":\"test-project\",\"role\":\"default\"}";
        putBlob(ResourceTypes.PROJECT_KEY, name, body);
        reload();

        // Rebuild path must have decrypted ENC[...] back to plaintext in the in-memory Config.
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        Key restored = store.get().getKeys().get("keys/platform/" + name);
        assertNotNull(restored, () -> "key entity must reach the merged Config");
        assertEquals("plain-rebuild-secret", restored.getKey());
    }

    @Test
    void testMalformedEnvelopeRoutesToInvalidWithDecryptionError() {
        String name = "broken-enc-key";
        // Base64-decode of !!!! fails → SecurityException → invalid-entity routing.
        String body = "{\"key\":\"ENC[!!!!]\",\"project\":\"test\",\"role\":\"default\"}";
        putBlob(ResourceTypes.PROJECT_KEY, name, body);
        reload();

        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        var invalid = store.getInvalidEntities().get(ResourceTypes.PROJECT_KEY);
        assertNotNull(invalid, () -> "PROJECT_KEY invalid bucket must exist: "
                + store.getInvalidEntities());
        var record = invalid.get("keys/platform/" + name);
        assertNotNull(record, () -> "broken-enc-key must surface as invalid: " + invalid);
        assertTrue(record.getReason().toLowerCase().contains("decryption"),
                () -> "expected decryption-related reason: " + record.getReason());
        // Decryption-failed entity must NOT be present in the runtime Config (locked 2S.9 invariant).
        assertFalse(store.get().getKeys().containsKey("keys/platform/" + name),
                () -> "decryption-failed key must not reach Config.keys");
    }

    @Test
    void testPlaintextBlobPassesThrough() {
        String name = "plaintext-key";
        String body = "{\"key\":\"plain-passthrough\",\"project\":\"test\",\"role\":\"default\"}";
        putBlob(ResourceTypes.PROJECT_KEY, name, body);
        reload();

        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        Key restored = store.get().getKeys().get("keys/platform/" + name);
        assertNotNull(restored, () -> "plaintext key must reach the merged Config");
        assertEquals("plain-passthrough", restored.getKey());
    }

    private void reload() {
        Response resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, resp.status());
    }

    private void putBlob(ResourceTypes type, String name, String body) {
        ResourceService resourceService = dial.getProxy().getResourceService();
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                type, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, name);
        resourceService.putResource(descriptor, body, EtagHeader.ANY, null, false);
    }

    private SecretFieldProcessor readSecretFieldProcessor() throws Exception {
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        Field f = MergedConfigStore.class.getDeclaredField("secretFieldProcessor");
        f.setAccessible(true);
        return (SecretFieldProcessor) f.get(store);
    }
}
