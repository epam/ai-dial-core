package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecretFieldProcessorTest {

    private static final BucketInfo BUCKET = new BucketInfo("platform", "platform/");
    private static final ObjectMapper M = new ObjectMapper();

    @Mock
    private CredentialEncryptionService encryptionService;

    private SecretFieldProcessor processor;
    private ResourceDescriptor descriptor;

    @BeforeEach
    void setUp() {
        processor = new SecretFieldProcessor(encryptionService, BUCKET);
        descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.PROJECT_KEY, "platform", "platform/", "test-key");
    }

    @Test
    void encryptFields_encryptsPlaintextKey() {
        Key key = new Key();
        key.setKey("plain-secret");
        when(encryptionService.encrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenReturn("CIPHER".getBytes(StandardCharsets.UTF_8));

        processor.encryptFields(key, descriptor);

        String expected = "ENC[" + Base64.getEncoder().encodeToString("CIPHER".getBytes(StandardCharsets.UTF_8)) + "]";
        assertEquals(expected, key.getKey());
    }

    @Test
    void encryptFields_skipsAlreadyEncryptedEnvelope() {
        Key key = new Key();
        key.setKey("ENC[abc]");

        processor.encryptFields(key, descriptor);

        assertEquals("ENC[abc]", key.getKey());
        verify(encryptionService, never()).encrypt(any(), any(), any());
    }

    @Test
    void encryptFields_skipsSecretReference() {
        Key key = new Key();
        key.setKey("${SECRET:azure-prod-key}");

        processor.encryptFields(key, descriptor);

        assertEquals("${SECRET:azure-prod-key}", key.getKey());
        verify(encryptionService, never()).encrypt(any(), any(), any());
    }

    @Test
    void decryptFields_decryptsEnvelope() {
        Key key = new Key();
        String envelope = "ENC[" + Base64.getEncoder().encodeToString("cipher".getBytes(StandardCharsets.UTF_8)) + "]";
        key.setKey(envelope);
        when(encryptionService.decrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenReturn("plain-secret".getBytes(StandardCharsets.UTF_8));

        processor.decryptFields(key, descriptor);

        assertEquals("plain-secret", key.getKey());
    }

    @Test
    void decryptFields_throwsSecurityExceptionOnFailure() {
        Key key = new Key();
        String envelope = "ENC[" + Base64.getEncoder().encodeToString("cipher".getBytes(StandardCharsets.UTF_8)) + "]";
        key.setKey(envelope);
        when(encryptionService.decrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenThrow(new RuntimeException("decryption failure"));

        SecurityException ex = assertThrows(SecurityException.class,
                () -> processor.decryptFields(key, descriptor));
        // Field name surfaces in the message (operator visibility on which secret blew up).
        assertEquals(true, ex.getMessage().contains("key"));
    }

    @Test
    void decryptFields_passesPlaintextThroughUnchanged() {
        Key key = new Key();
        key.setKey("plain-passthrough");

        processor.decryptFields(key, descriptor);

        assertEquals("plain-passthrough", key.getKey());
        verify(encryptionService, never()).decrypt(any(), any(), any());
    }

    @Test
    void decryptFields_walksIntoNestedUpstreams() {
        Upstream up = new Upstream();
        up.setKey("ENC[" + Base64.getEncoder().encodeToString("up-cipher".getBytes(StandardCharsets.UTF_8)) + "]");
        up.setExtraData("ENC[" + Base64.getEncoder().encodeToString("xd-cipher".getBytes(StandardCharsets.UTF_8)) + "]");
        Model model = new Model();
        model.setUpstreams(List.of(up));
        when(encryptionService.decrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenAnswer(inv -> {
                    byte[] in = inv.getArgument(1);
                    String s = new String(in, StandardCharsets.UTF_8);
                    return ("plain-" + s).getBytes(StandardCharsets.UTF_8);
                });

        processor.decryptFields(model, descriptor);

        assertEquals("plain-up-cipher", model.getUpstreams().get(0).getKey());
        assertEquals("plain-xd-cipher", model.getUpstreams().get(0).getExtraData());
    }

    @Test
    void resolveSecret_envelopeDecrypts() {
        when(encryptionService.decrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenReturn("plain".getBytes(StandardCharsets.UTF_8));
        String envelope = "ENC[" + Base64.getEncoder().encodeToString("c".getBytes(StandardCharsets.UTF_8)) + "]";

        String result = processor.resolveSecret(envelope, descriptor);

        assertEquals("plain", result);
    }

    @Test
    void resolveSecret_secretReferenceUnchanged() {
        String result = processor.resolveSecret("${SECRET:foo}", descriptor);
        assertEquals("${SECRET:foo}", result);
        verify(encryptionService, never()).decrypt(any(), any(), any());
    }

    @Test
    void validateNoMaskSentinel_throwsOnTopLevelMask() throws Exception {
        ObjectNode node = (ObjectNode) M.readTree("{\"key\": \"***\"}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> processor.validateNoMaskSentinel(node, Key.class));
        assertEquals("Secret field 'key' contains the mask sentinel '***'. "
                + "Provide a real secret value or omit the field.", ex.getMessage());
    }

    @Test
    void validateNoMaskSentinel_throwsOnNestedUpstreamMask() throws Exception {
        ObjectNode node = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"x\",\"key\":\"***\"}]}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> processor.validateNoMaskSentinel(node, Model.class));
        assertEquals("Secret field 'key' contains the mask sentinel '***'. "
                + "Provide a real secret value or omit the field.", ex.getMessage());
    }

    @Test
    void validateNoMaskSentinel_acceptsRealValue() throws Exception {
        ObjectNode node = (ObjectNode) M.readTree("{\"key\": \"real-secret\"}");
        // No exception expected.
        processor.validateNoMaskSentinel(node, Key.class);
    }

    @Test
    void mergePreservingOmittedSecrets_copiesCiphertextWhenAbsent() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree("{\"key\": \"ENC[abc]\", \"role\": \"r\"}");
        ObjectNode request = (ObjectNode) M.readTree("{\"role\": \"r2\"}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Key.class);

        assertEquals("ENC[abc]", merged.get("key").asText());
        assertEquals("r2", merged.get("role").asText());
    }

    @Test
    void mergePreservingOmittedSecrets_replacesMaskSentinel() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree("{\"key\": \"ENC[abc]\"}");
        ObjectNode request = (ObjectNode) M.readTree("{\"key\": \"***\"}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Key.class);

        assertEquals("ENC[abc]", merged.get("key").asText());
    }

    @Test
    void mergePreservingOmittedSecrets_keepsExplicitNewSecret() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree("{\"key\": \"ENC[abc]\"}");
        ObjectNode request = (ObjectNode) M.readTree("{\"key\": \"new-plain\"}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Key.class);

        assertEquals("new-plain", merged.get("key").asText());
    }

    @Test
    void encryptFields_isNullSafe() {
        Key key = new Key();
        processor.encryptFields(key, descriptor);
        assertNull(key.getKey());
        verify(encryptionService, never()).encrypt(any(), any(), any());
    }

    @Test
    void maskInPayload_replacesPlaintextSecretAtTopLevel() throws Exception {
        ObjectNode payload = (ObjectNode) M.readTree("{\"key\": \"super-secret\", \"role\": \"r\"}");

        ObjectNode masked = SecretFieldProcessor.maskInPayload(payload, Key.class);

        assertEquals("***", masked.get("key").asText());
        assertEquals("r", masked.get("role").asText());
        assertEquals("super-secret", payload.get("key").asText(), "input must not be mutated");
    }

    @Test
    void maskInPayload_recursesIntoUpstreams() throws Exception {
        ObjectNode payload = (ObjectNode) M.readTree(
                "{\"name\":\"m\",\"upstreams\":[{\"endpoint\":\"e\",\"key\":\"sk-leak\",\"extraData\":\"{\\\"region\\\":\\\"us\\\"}\"}]}");

        ObjectNode masked = SecretFieldProcessor.maskInPayload(payload, Model.class);

        ObjectNode up = (ObjectNode) masked.get("upstreams").get(0);
        assertEquals("***", up.get("key").asText());
        assertEquals("***", up.get("extraData").asText());
        assertEquals("e", up.get("endpoint").asText());
    }

    @Test
    void maskInPayload_returnsNullForNonObject() {
        assertNull(SecretFieldProcessor.maskInPayload(null, Key.class));
    }
}
