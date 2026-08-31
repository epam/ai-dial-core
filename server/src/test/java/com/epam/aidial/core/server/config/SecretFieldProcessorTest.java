package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        // AES-GCM IV 12 + tag 16; mirrors DataEncryptionService defaults (pinned in DataEncryptionServiceTest).
        lenient().when(encryptionService.minEncryptedLength()).thenReturn(28);
        processor = new SecretFieldProcessor(encryptionService, BUCKET);
        descriptor = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.PROJECT_KEY, "platform", "platform/", "test-key");
    }

    // Structurally valid envelope: Base64 of >= 28 bytes (AES-GCM IV 12 + tag 16 minimum).
    private static String validEnvelope(String seed) {
        byte[] bytes = new byte[28];
        byte[] src = seed.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = src[i % src.length];
        }
        return "ENC[" + Base64.getEncoder().encodeToString(bytes) + "]";
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
        String envelope = validEnvelope("already-encrypted-payload-bytes");
        key.setKey(envelope);

        processor.encryptFields(key, descriptor);

        assertEquals(envelope, key.getKey());
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
        up.setSecretExtraData("ENC[" + Base64.getEncoder().encodeToString("xd-cipher".getBytes(StandardCharsets.UTF_8)) + "]");
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
        assertEquals("plain-xd-cipher", model.getUpstreams().get(0).getSecretExtraData());
    }

    @Test
    void decryptFields_walksIntoUpstreamInterfaces() {
        UpstreamInterface anthropic = new UpstreamInterface();
        anthropic.setKey("ENC[" + Base64.getEncoder().encodeToString("iface-cipher".getBytes(StandardCharsets.UTF_8)) + "]");
        Upstream up = new Upstream();
        up.setInterfaces(Map.of("anthropicMessages", anthropic));
        Model model = new Model();
        model.setUpstreams(List.of(up));
        when(encryptionService.decrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenAnswer(inv -> {
                    byte[] in = inv.getArgument(1);
                    return ("plain-" + new String(in, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                });

        processor.decryptFields(model, descriptor);

        assertEquals("plain-iface-cipher",
                model.getUpstreams().get(0).getInterfaces().get("anthropicMessages").getKey());
    }

    @Test
    void stripEncryptedFields_dropsSecretsNestedInUpstreamInterfaces() {
        ObjectNode payload = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode upstream = payload.putArray("upstreams").addObject();
        upstream.put("key", "ENC[up]");
        ObjectNode anthropic = upstream.putObject("interfaces").putObject("anthropicMessages");
        anthropic.put("endpoint", "http://anthropic/v1/messages");
        anthropic.put("key", "ENC[iface]");
        anthropic.put("secretExtraData", "ENC[iface-xd]");

        ObjectNode stripped = SecretFieldProcessor.stripEncryptedFields(payload, Model.class);

        ObjectNode strippedInterface = (ObjectNode) stripped.get("upstreams").get(0)
                .get("interfaces").get("anthropicMessages");
        assertFalse(strippedInterface.has("key"));
        assertFalse(strippedInterface.has("secretExtraData"));
        // non-secret members survive
        assertEquals("http://anthropic/v1/messages", strippedInterface.get("endpoint").asText());
    }

    @Test
    void mergePreservingOmittedSecrets_preservesSecretsNestedInUpstreamInterfaces() {
        ObjectNode existing = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode existingUpstream = existing.putArray("upstreams").addObject();
        existingUpstream.put("endpoint", "http://provider");
        existingUpstream.putObject("interfaces").putObject("anthropicMessages").put("key", "ENC[prior]");

        ObjectNode request = ProxyUtil.MAPPER.createObjectNode();
        ObjectNode requestUpstream = request.putArray("upstreams").addObject();
        requestUpstream.put("endpoint", "http://provider");
        requestUpstream.putObject("interfaces").putObject("anthropicMessages")
                .put("endpoint", "http://anthropic/v1/messages");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("ENC[prior]", merged.get("upstreams").get(0)
                .get("interfaces").get("anthropicMessages").get("key").asText());
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
    void mergePreservingOmittedSecrets_copiesCiphertextWhenAbsent() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree("{\"key\": \"ENC[abc]\", \"role\": \"r\"}");
        ObjectNode request = (ObjectNode) M.readTree("{\"role\": \"r2\"}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Key.class);

        assertEquals("ENC[abc]", merged.get("key").asText());
        assertEquals("r2", merged.get("role").asText());
    }

    @Test
    void mergePreservingOmittedSecrets_treatsMaskAsLiteralValue() throws Exception {
        // Slice U.4: the "***" sentinel was retired. A textual "***" in the request body is a real
        // value (re-encrypted on write), not a signal to preserve. Only null / missing preserves.
        ObjectNode existing = (ObjectNode) M.readTree("{\"key\": \"ENC[abc]\"}");
        ObjectNode request = (ObjectNode) M.readTree("{\"key\": \"***\"}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Key.class);

        assertEquals("***", merged.get("key").asText());
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
    void stripEncryptedFields_dropsSecretAtTopLevel() throws Exception {
        ObjectNode payload = (ObjectNode) M.readTree("{\"key\": \"super-secret\", \"role\": \"r\"}");

        ObjectNode stripped = SecretFieldProcessor.stripEncryptedFields(payload, Key.class);

        assertFalse(stripped.has("key"), () -> "key must be removed: " + stripped);
        assertEquals("r", stripped.get("role").asText());
        assertEquals("super-secret", payload.get("key").asText(), "input must not be mutated");
    }

    @Test
    void stripEncryptedFields_recursesIntoUpstreams() throws Exception {
        ObjectNode payload = (ObjectNode) M.readTree(
                "{\"name\":\"m\",\"upstreams\":[{\"endpoint\":\"e\",\"key\":\"sk-leak\","
                        + "\"extraData\":\"{\\\"region\\\":\\\"us\\\"}\",\"secretExtraData\":\"sk-secret\"}]}");

        ObjectNode stripped = SecretFieldProcessor.stripEncryptedFields(payload, Model.class);

        ObjectNode up = (ObjectNode) stripped.get("upstreams").get(0);
        assertFalse(up.has("key"), () -> "upstream key must be removed: " + up);
        assertFalse(up.has("secretExtraData"), () -> "upstream secretExtraData must be removed: " + up);
        assertEquals("{\"region\":\"us\"}", up.get("extraData").asText(), () -> "upstream extraData must be kept: " + up);
        assertEquals("e", up.get("endpoint").asText());
    }

    @Test
    void stripEncryptedFields_returnsNullForNonObject() {
        assertNull(SecretFieldProcessor.stripEncryptedFields(null, Key.class));
    }

    @Test
    void reorderPreservesCorrectSecretPerEndpoint() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"},{\"endpoint\":\"B\",\"key\":\"ENC[b]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"B\"},{\"endpoint\":\"A\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("B", merged.get("upstreams").get(0).get("endpoint").asText());
        assertEquals("ENC[b]", merged.get("upstreams").get(0).get("key").asText());
        assertEquals("A", merged.get("upstreams").get(1).get("endpoint").asText());
        assertEquals("ENC[a]", merged.get("upstreams").get(1).get("key").asText());
    }

    @Test
    void insertAtIndexZeroGetsNoSecret() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"C\"},{\"endpoint\":\"A\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        ObjectNode c = (ObjectNode) merged.get("upstreams").get(0);
        assertEquals("C", c.get("endpoint").asText());
        assertFalse(c.has("key") && !c.get("key").isNull(), () -> "C must get no preserved secret: " + c);
        assertEquals("ENC[a]", merged.get("upstreams").get(1).get("key").asText());
    }

    @Test
    void removalLeavesRemainingCorrect() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"},"
                        + "{\"endpoint\":\"B\",\"key\":\"ENC[b]\"},{\"endpoint\":\"C\",\"key\":\"ENC[c]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\"},{\"endpoint\":\"C\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("ENC[a]", merged.get("upstreams").get(0).get("key").asText());
        assertEquals("ENC[c]", merged.get("upstreams").get(1).get("key").asText());
    }

    @Test
    void duplicateEndpointsMatchInRelativeOrder() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"tier\":0,\"key\":\"ENC[a0]\"},"
                        + "{\"endpoint\":\"A\",\"tier\":1,\"key\":\"ENC[a1]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\"},{\"endpoint\":\"A\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("ENC[a0]", merged.get("upstreams").get(0).get("key").asText());
        assertEquals("ENC[a1]", merged.get("upstreams").get(1).get("key").asText());
    }

    @Test
    void newUpstreamWithExplicitKeyKept() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"D\",\"key\":\"new-plain\"},{\"endpoint\":\"A\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("new-plain", merged.get("upstreams").get(0).get("key").asText());
        assertEquals("ENC[a]", merged.get("upstreams").get(1).get("key").asText());
    }

    @Test
    void secretExtraDataAlsoPreservedByEndpoint() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"secretExtraData\":\"ENC[xa]\"},"
                        + "{\"endpoint\":\"B\",\"secretExtraData\":\"ENC[xb]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"B\"},{\"endpoint\":\"A\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("ENC[xb]", merged.get("upstreams").get(0).get("secretExtraData").asText());
        assertEquals("ENC[xa]", merged.get("upstreams").get(1).get("secretExtraData").asText());
    }

    @Test
    void mixedKeyedUnkeyedLosesSecretOnConsumedIndexSlot() throws Exception {
        // Contract pin (not a fix): an endpoint-keyed element and an endpoint-less element in the
        // same request array can silently lose a stored secret. element0 endpoint-matches B (consumes
        // slot 1); element1 has no endpoint so it strict-index-pairs with slot 1, finds it consumed,
        // and preserves nothing. Deterministic by structure — clients must not mix the two forms.
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"},{\"endpoint\":\"B\",\"key\":\"ENC[b]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"B\"},{}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("ENC[b]", merged.get("upstreams").get(0).get("key").asText());
        ObjectNode element1 = (ObjectNode) merged.get("upstreams").get(1);
        assertFalse(element1.has("key"),
                () -> "element1 must get no preserved secret (slot 1 consumed by endpoint match): " + element1);
    }

    @Test
    void allUnkeyedElementsPairByIndex() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"},"
                        + "{\"endpoint\":\"B\",\"key\":\"ENC[b]\"},{\"endpoint\":\"C\",\"key\":\"ENC[c]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{},{},{}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        assertEquals("ENC[a]", merged.get("upstreams").get(0).get("key").asText());
        assertEquals("ENC[b]", merged.get("upstreams").get(1).get("key").asText());
        assertEquals("ENC[c]", merged.get("upstreams").get(2).get("key").asText());
    }

    @Test
    void unkeyedElementBeyondSourceBoundsGetsNothing() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{},{\"key\":\"new-plain\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        // element0 index-pairs with slot 0; element1 index 1 is out of source bounds (size 1) → no
        // preservation, its explicit value survives (re-encrypted on write).
        assertEquals("ENC[a]", merged.get("upstreams").get(0).get("key").asText());
        assertEquals("new-plain", merged.get("upstreams").get(1).get("key").asText());
    }

    @Test
    void unkeyedFirstThenEndpointMatchOnSameSlot() throws Exception {
        ObjectNode existing = (ObjectNode) M.readTree(
                "{\"upstreams\":[{\"endpoint\":\"A\",\"key\":\"ENC[a]\"},{\"endpoint\":\"B\",\"key\":\"ENC[b]\"}]}");
        ObjectNode request = (ObjectNode) M.readTree(
                "{\"upstreams\":[{},{\"endpoint\":\"A\"}]}");

        ObjectNode merged = processor.mergePreservingOmittedSecrets(existing, request, Model.class);

        // element0 (no endpoint) strict-index-pairs slot 0 → preserves ENC[a], consumes slot 0.
        // element1 endpoint=A then finds slot 0 consumed and no other A source → preserves nothing.
        assertEquals("ENC[a]", merged.get("upstreams").get(0).get("key").asText());
        ObjectNode element1 = (ObjectNode) merged.get("upstreams").get(1);
        assertFalse(element1.has("key"),
                () -> "element1 endpoint=A must get nothing (slot 0 already consumed): " + element1);
    }

    @Test
    void plaintextShapedLikeEnvelopeGetsEncrypted() {
        Key key = new Key();
        key.setKey("ENC[not-base64!]");
        when(encryptionService.encrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenReturn("CIPHER".getBytes(StandardCharsets.UTF_8));

        processor.encryptFields(key, descriptor);

        String expected = "ENC[" + Base64.getEncoder().encodeToString("CIPHER".getBytes(StandardCharsets.UTF_8)) + "]";
        assertEquals(expected, key.getKey());
        verify(encryptionService).encrypt(eq(BUCKET), any(byte[].class), any(byte[].class));
    }

    @Test
    void tooShortEnvelopeShapedPlaintextGetsEncrypted() {
        Key key = new Key();
        // "YQ==" decodes to a single byte, well below the 28-byte AES-GCM minimum.
        key.setKey("ENC[YQ==]");
        when(encryptionService.encrypt(eq(BUCKET), any(byte[].class), any(byte[].class)))
                .thenReturn("CIPHER".getBytes(StandardCharsets.UTF_8));

        processor.encryptFields(key, descriptor);

        String expected = "ENC[" + Base64.getEncoder().encodeToString("CIPHER".getBytes(StandardCharsets.UTF_8)) + "]";
        assertEquals(expected, key.getKey());
        verify(encryptionService).encrypt(eq(BUCKET), any(byte[].class), any(byte[].class));
    }

    @Test
    void legacyValidEnvelopePassesThroughUnchanged() {
        Key key = new Key();
        String envelope = validEnvelope("legacy-ciphertext-bytes");
        key.setKey(envelope);

        processor.encryptFields(key, descriptor);

        assertEquals(envelope, key.getKey());
        verify(encryptionService, never()).encrypt(any(), any(), any());
    }

    @Test
    void invalidBase64EnvelopeThrowsOnDecrypt() {
        Key key = new Key();
        key.setKey("ENC[!!!]");

        assertThrows(SecurityException.class, () -> processor.decryptFields(key, descriptor));
        verify(encryptionService, never()).decrypt(any(), any(), any());
    }
}
