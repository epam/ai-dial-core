package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SecretFieldProcessor {

    public static final String ENC_PREFIX = "ENC[";
    public static final String ENC_SUFFIX = "]";
    public static final String SECRET_REF_PREFIX = "${SECRET:";

    private static final ConcurrentHashMap<Class<?>, List<Field>> FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> HAS_ENCRYPTED_FIELD_CACHE = new ConcurrentHashMap<>();

    private final CredentialEncryptionService encryptionService;
    private final BucketInfo platformBucketInfo;

    public SecretFieldProcessor(CredentialEncryptionService encryptionService,
                                BucketInfo platformBucketInfo) {
        this.encryptionService = encryptionService;
        this.platformBucketInfo = platformBucketInfo;
    }

    public void encryptFields(Object entity, ResourceDescriptor descriptor) {
        if (entity == null) {
            return;
        }
        walk(entity, aad(descriptor), true);
    }

    public void decryptFields(Object entity, ResourceDescriptor descriptor) {
        if (entity == null) {
            return;
        }
        walk(entity, aad(descriptor), false);
    }

    public String resolveSecret(String value, ResourceDescriptor descriptor) {
        if (value == null) {
            return null;
        }
        if (value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX)) {
            return decryptEnvelope(value, aad(descriptor), "value");
        }
        return value;
    }

    private static byte[] aad(ResourceDescriptor descriptor) {
        return descriptor.getStableFilePath().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Strip every {@link EncryptedField}-annotated value (and any nested array elements that carry
     * the annotation) from {@code payload}. Used to project invalid-entity payloads on the admin
     * GET surface — the raw blob may still hold {@code ENC[...]} ciphertext (decryption_error
     * reason) and dropping the fields entirely keeps ciphertext out of the response.
     */
    public static ObjectNode stripEncryptedFields(JsonNode payload, Class<?> entityClass) {
        if (!(payload instanceof ObjectNode object)) {
            return null;
        }
        ObjectNode stripped = object.deepCopy();
        applyStrip(stripped, entityClass);
        return stripped;
    }

    private static void applyStrip(ObjectNode target, Class<?> entityClass) {
        for (Field field : declaredFieldsIncludingInherited(entityClass)) {
            String name = field.getName();
            if (field.isAnnotationPresent(EncryptedField.class)) {
                target.remove(name);
            }
            Class<?> nestedType = elementClassWithEncryptedField(field);
            if (nestedType != null) {
                JsonNode arr = target.get(name);
                if (arr != null && arr.isArray()) {
                    for (JsonNode item : arr) {
                        if (item instanceof ObjectNode itemObj) {
                            applyStrip(itemObj, nestedType);
                        }
                    }
                }
            }
            Class<?> valueType = valueClassWithEncryptedField(field);
            if (valueType != null && target.get(name) instanceof ObjectNode entries) {
                for (JsonNode entry : entries) {
                    if (entry instanceof ObjectNode entryObj) {
                        applyStrip(entryObj, valueType);
                    }
                }
            }
        }
    }

    public ObjectNode mergePreservingOmittedSecrets(JsonNode existingBlobNode,
                                                    JsonNode requestNode,
                                                    Class<?> entityClass) {
        if (!(requestNode instanceof ObjectNode)) {
            throw new IllegalArgumentException("requestNode must be an object");
        }
        ObjectNode merged = requestNode.deepCopy();
        if (existingBlobNode == null || !existingBlobNode.isObject()) {
            return merged;
        }
        mergeInto(merged, existingBlobNode, entityClass);
        return merged;
    }

    private void mergeInto(ObjectNode target, JsonNode source, Class<?> entityClass) {
        for (Field field : declaredFieldsIncludingInherited(entityClass)) {
            String name = field.getName();
            if (field.isAnnotationPresent(EncryptedField.class)) {
                JsonNode current = target.get(name);
                // Preserve-on-omit: a null or absent secret in the request body keeps the prior
                // ciphertext from the stored blob. Without the retired "***" mask sentinel, only
                // null / missing signals "omitted" — a literal string in the request is treated as
                // a real value and re-encrypted.
                if (current == null || current.isNull()) {
                    JsonNode existing = source.get(name);
                    if (existing != null && !existing.isNull()) {
                        target.set(name, existing.deepCopy());
                    }
                }
            }
            Class<?> nestedType = elementClassWithEncryptedField(field);
            if (nestedType != null) {
                JsonNode targetArr = target.get(name);
                JsonNode sourceArr = source.get(name);
                if (targetArr instanceof ArrayNode targets && sourceArr instanceof ArrayNode sources) {
                    mergeArray(targets, sources, nestedType);
                }
            }
            Class<?> valueType = valueClassWithEncryptedField(field);
            if (valueType != null
                    && target.get(name) instanceof ObjectNode targetEntries
                    && source.get(name) instanceof ObjectNode sourceEntries) {
                mergeMap(targetEntries, sourceEntries, valueType);
            }
        }
    }

    // A map keys itself, so entries pair by name rather than by the arrays' endpoint/index matching.
    private void mergeMap(ObjectNode targets, ObjectNode sources, Class<?> valueType) {
        Iterator<String> names = targets.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (targets.get(name) instanceof ObjectNode targetEntry
                    && sources.get(name) instanceof ObjectNode sourceEntry) {
                mergeInto(targetEntry, sourceEntry, valueType);
            }
        }
    }

    // Pair each target (request) element with its preserved source (blob) element. The matcher keys
    // on the canonical JSON name "endpoint" (blobs are always written via the canonical mapper, so
    // the field is present under that name) and falls back to index pairing when "endpoint" is
    // absent — preserving prior behavior for non-keyed arrays. Iteration follows the request, so the
    // desired set/order wins; duplicate endpoints match in relative order (stable two-pointer).
    // Contract: endpoint-less elements use strict same-index pairing only. A consumed or
    // out-of-bounds index slot yields no preservation (request value, possibly null, wins) — this is
    // deterministic and never throws. Because an endpoint match can consume the slot an endpoint-less
    // element would otherwise take, clients mixing endpoint-keyed and endpoint-less elements in one
    // array must supply secrets explicitly for the unkeyed elements (or avoid the mix).
    private void mergeArray(ArrayNode targets, ArrayNode sources, Class<?> nestedType) {
        boolean[] consumed = new boolean[sources.size()];
        for (int i = 0; i < targets.size(); i++) {
            if (!(targets.get(i) instanceof ObjectNode targetObj)) {
                continue;
            }
            int sourceIdx = matchSourceIndex(targetObj, sources, consumed, i);
            if (sourceIdx < 0) {
                continue;
            }
            consumed[sourceIdx] = true;
            mergeInto(targetObj, sources.get(sourceIdx), nestedType);
        }
    }

    private int matchSourceIndex(ObjectNode targetObj, ArrayNode sources, boolean[] consumed, int targetIndex) {
        JsonNode endpointNode = targetObj.get("endpoint");
        if (endpointNode != null && endpointNode.isTextual()) {
            String endpoint = endpointNode.textValue();
            for (int j = 0; j < sources.size(); j++) {
                if (consumed[j] || !(sources.get(j) instanceof ObjectNode sourceObj)) {
                    continue;
                }
                JsonNode sourceEndpoint = sourceObj.get("endpoint");
                if (sourceEndpoint != null && sourceEndpoint.isTextual()
                        && endpoint.equals(sourceEndpoint.textValue())) {
                    return j;
                }
            }
            return -1;
        }
        if (targetIndex < sources.size() && !consumed[targetIndex] && sources.get(targetIndex).isObject()) {
            return targetIndex;
        }
        return -1;
    }

    private void walk(Object entity, byte[] aad, boolean encrypt) {
        if (entity == null) {
            return;
        }
        Class<?> cls = entity.getClass();
        for (Field field : declaredFieldsIncludingInherited(cls)) {
            try {
                if (field.isAnnotationPresent(EncryptedField.class) && field.getType() == String.class) {
                    String value = (String) field.get(entity);
                    String transformed = encrypt ? encryptValue(value, aad, field.getName())
                            : decryptValue(value, aad, field.getName());
                    // Reference identity, not Objects.equals: encrypt/decrypt return the *input*
                    // reference unchanged on no-op paths (null/empty, already enveloped,
                    // ${secret:...} placeholders). Skipping field.set in those cases avoids a
                    // redundant reflective write.
                    if (transformed != value) {
                        field.set(entity, transformed);
                    }
                    continue;
                }
                Object child = field.get(entity);
                recurseInto(child, aad, encrypt);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Reflection failure on " + cls.getName() + "." + field.getName(), e);
            }
        }
    }

    private void recurseInto(Object child, byte[] aad, boolean encrypt) {
        if (child == null) {
            return;
        }
        if (child instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && classHasEncryptedField(item.getClass())) {
                    walk(item, aad, encrypt);
                }
            }
        } else if (child instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value != null && classHasEncryptedField(value.getClass())) {
                    walk(value, aad, encrypt);
                }
            }
        } else if (classHasEncryptedField(child.getClass())) {
            walk(child, aad, encrypt);
        }
    }

    private String encryptValue(String value, byte[] aad, String fieldName) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Only a structurally valid envelope (valid Base64, decoded length >= AES-GCM minimum) is
        // trusted as already-encrypted. An ENC[-shaped but malformed value falls through and is
        // encrypted as plaintext rather than blindly preserved.
        if (isValidEnvelope(value)) {
            return value;
        }
        if (value.startsWith(SECRET_REF_PREFIX)) {
            return value;
        }
        try {
            byte[] cipher = encryptionService.encrypt(platformBucketInfo,
                    value.getBytes(StandardCharsets.UTF_8), aad);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(cipher) + ENC_SUFFIX;
        } catch (RuntimeException e) {
            throw new SecurityException("Failed to encrypt field '" + fieldName + "': " + e.getMessage(), e);
        }
    }

    private String decryptValue(String value, byte[] aad, String fieldName) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX)) {
            return decryptEnvelope(value, aad, fieldName);
        }
        return value;
    }

    private boolean isValidEnvelope(String value) {
        if (!value.startsWith(ENC_PREFIX) || !value.endsWith(ENC_SUFFIX)) {
            return false;
        }
        String payload = value.substring(ENC_PREFIX.length(), value.length() - ENC_SUFFIX.length());
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // A decoded payload shorter than IV + GCM tag cannot be a real envelope.
        return decoded.length >= encryptionService.minEncryptedLength();
    }

    private String decryptEnvelope(String envelope, byte[] aad, String fieldName) {
        String payload = envelope.substring(ENC_PREFIX.length(), envelope.length() - ENC_SUFFIX.length());
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Failed to decrypt field '" + fieldName
                    + "': malformed Base64 envelope", e);
        }
        try {
            byte[] plain = encryptionService.decrypt(platformBucketInfo, raw, aad);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new SecurityException("Failed to decrypt field '" + fieldName + "': " + e.getMessage(), e);
        }
    }

    private static List<Field> declaredFieldsIncludingInherited(Class<?> cls) {
        return FIELDS_CACHE.computeIfAbsent(cls, SecretFieldProcessor::collectFields);
    }

    private static List<Field> collectFields(Class<?> cls) {
        List<Field> result = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.isSynthetic()) {
                    try {
                        f.setAccessible(true);
                    } catch (RuntimeException ignored) {
                        // JPMS refuses setAccessible on java.lang.Enum.name etc; classHasEncryptedField
                        // filters such types before walk would .get/.set their fields.
                    }
                    result.add(f);
                }
            }
            c = c.getSuperclass();
        }
        return List.copyOf(result);
    }

    private static Class<?> elementClassWithEncryptedField(Field field) {
        java.lang.reflect.Type generic = field.getGenericType();
        if (!(generic instanceof java.lang.reflect.ParameterizedType pt)) {
            return null;
        }
        if (!Collection.class.isAssignableFrom(field.getType())) {
            return null;
        }
        java.lang.reflect.Type[] args = pt.getActualTypeArguments();
        if (args.length != 1) {
            return null;
        }
        if (args[0] instanceof Class<?> elementClass && classHasEncryptedField(elementClass)) {
            return elementClass;
        }
        return null;
    }

    /**
     * The value type of a {@code Map}-valued field carrying encrypted members, e.g.
     * {@code Upstream.interfaces}. Its JSON shape is an object keyed by name, so the JsonNode-level
     * passes have to descend into it the same way they descend into arrays.
     */
    private static Class<?> valueClassWithEncryptedField(Field field) {
        java.lang.reflect.Type generic = field.getGenericType();
        if (!(generic instanceof java.lang.reflect.ParameterizedType pt)) {
            return null;
        }
        if (!Map.class.isAssignableFrom(field.getType())) {
            return null;
        }
        java.lang.reflect.Type[] args = pt.getActualTypeArguments();
        if (args.length != 2) {
            return null;
        }
        if (args[1] instanceof Class<?> valueClass && classHasEncryptedField(valueClass)) {
            return valueClass;
        }
        return null;
    }

    private static boolean classHasEncryptedField(Class<?> cls) {
        if (cls == null || cls.isPrimitive() || cls.getName().startsWith("java.")) {
            return false;
        }
        return HAS_ENCRYPTED_FIELD_CACHE.computeIfAbsent(cls, SecretFieldProcessor::computeHasEncryptedField);
    }

    private static boolean computeHasEncryptedField(Class<?> cls) {
        for (Field f : declaredFieldsIncludingInherited(cls)) {
            if (f.isAnnotationPresent(EncryptedField.class)) {
                return true;
            }
        }
        return false;
    }
}
