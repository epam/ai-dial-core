package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SecretFieldProcessor {

    private static final String ENC_PREFIX = "ENC[";
    private static final String ENC_SUFFIX = "]";
    private static final String SECRET_REF_PREFIX = "${SECRET:";

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
        byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        walk(entity, aad, true, false);
    }

    public void decryptFields(Object entity, ResourceDescriptor descriptor) {
        if (entity == null) {
            return;
        }
        byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        walk(entity, aad, false, false);
    }

    /**
     * As {@link #decryptFields}, but a field that fails to decrypt is set to null and logged instead of
     * aborting the walk - used where one bad secret must not block every other field in the same object
     * graph from decrypting, or block the write that follows from completing.
     */
    public void decryptFieldsLenient(Object entity, ResourceDescriptor descriptor) {
        if (entity == null) {
            return;
        }
        byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        walk(entity, aad, false, true);
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
            Class<?> objectType = objectClassWithEncryptedField(field);
            if (objectType != null && target.get(name) instanceof ObjectNode nested) {
                applyStrip(nested, objectType);
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
            Class<?> objectType = objectClassWithEncryptedField(field);
            if (objectType != null
                    && target.get(name) instanceof ObjectNode targetObj
                    && source.get(name) instanceof ObjectNode sourceObj) {
                mergeInto(targetObj, sourceObj, objectType);
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

    // Pairs each target (request) element with its source (blob) element to preserve omitted secrets:
    // by "endpoint", then "baseUrl" (for endpoint-less, interface-routed upstreams), falling back to
    // same-index pairing when neither is present. Mixing identified and unidentified elements in one
    // array can let an identity match consume the slot an unidentified element would otherwise take.
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
            return matchByField(sources, consumed, "endpoint", endpointNode.textValue());
        }
        JsonNode baseUrlNode = targetObj.get("baseUrl");
        if (baseUrlNode != null && baseUrlNode.isTextual()) {
            return matchByField(sources, consumed, "baseUrl", baseUrlNode.textValue());
        }
        if (targetIndex < sources.size() && !consumed[targetIndex] && sources.get(targetIndex).isObject()) {
            return targetIndex;
        }
        return -1;
    }

    private static int matchByField(ArrayNode sources, boolean[] consumed, String field, String value) {
        for (int j = 0; j < sources.size(); j++) {
            if (consumed[j] || !(sources.get(j) instanceof ObjectNode sourceObj)) {
                continue;
            }
            JsonNode sourceValue = sourceObj.get(field);
            if (sourceValue != null && sourceValue.isTextual() && value.equals(sourceValue.textValue())) {
                return j;
            }
        }
        return -1;
    }

    private void walk(Object entity, byte[] aad, boolean encrypt, boolean lenient) {
        if (entity == null) {
            return;
        }
        Class<?> cls = entity.getClass();
        for (Field field : declaredFieldsIncludingInherited(cls)) {
            try {
                if (field.isAnnotationPresent(EncryptedField.class) && field.getType() == String.class) {
                    String value = (String) field.get(entity);
                    String transformed = transform(value, aad, field.getName(), cls, encrypt, lenient);
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
                recurseInto(child, aad, encrypt, lenient);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Reflection failure on " + cls.getName() + "." + field.getName(), e);
            }
        }
    }

    // Lenient mode only ever applies to decrypt: an encrypt failure must still fail the write rather
    // than silently persist null in place of a real secret.
    private String transform(String value, byte[] aad, String fieldName, Class<?> cls, boolean encrypt, boolean lenient) {
        if (encrypt) {
            return encryptValue(value, aad, fieldName);
        }
        if (!lenient) {
            return decryptValue(value, aad, fieldName);
        }
        try {
            return decryptValue(value, aad, fieldName);
        } catch (SecurityException e) {
            log.warn("Can't decrypt field '{}' on {}, dropping it: {}", fieldName, cls.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private void recurseInto(Object child, byte[] aad, boolean encrypt, boolean lenient) {
        if (child == null) {
            return;
        }
        if (child instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && classHasEncryptedField(item.getClass())) {
                    walk(item, aad, encrypt, lenient);
                }
            }
        } else if (child instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value != null && classHasEncryptedField(value.getClass())) {
                    walk(value, aad, encrypt, lenient);
                }
            }
        } else if (classHasEncryptedField(child.getClass())) {
            walk(child, aad, encrypt, lenient);
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
        if (!Collection.class.isAssignableFrom(field.getType())) {
            return null;
        }
        Class<?> elementClass = resolveNestedClass(field);
        return (elementClass != null && classHasEncryptedField(elementClass)) ? elementClass : null;
    }

    /**
     * The value type of a {@code Map}-valued field carrying encrypted members, e.g.
     * {@code Upstream.interfaces}. Its JSON shape is an object keyed by name, so the JsonNode-level
     * passes have to descend into it the same way they descend into arrays.
     */
    private static Class<?> valueClassWithEncryptedField(Field field) {
        if (!Map.class.isAssignableFrom(field.getType())) {
            return null;
        }
        Class<?> valueClass = resolveNestedClass(field);
        return (valueClass != null && classHasEncryptedField(valueClass)) ? valueClass : null;
    }

    /**
     * The type of a plain (non-{@code Collection}, non-{@code Map}) object-valued field that itself
     * carries — directly or transitively — an {@link EncryptedField}. Lets {@link #mergeInto}/
     * {@link #applyStrip} descend through a bare nested-object field the same way they already
     * descend through array- and map-valued fields.
     */
    private static Class<?> objectClassWithEncryptedField(Field field) {
        Class<?> type = field.getType();
        if (field.isAnnotationPresent(EncryptedField.class)
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || type.isPrimitive()
                || type.getName().startsWith("java.")) {
            return null;
        }
        return classHasEncryptedField(type) ? type : null;
    }

    private static boolean classHasEncryptedField(Class<?> cls) {
        if (cls == null || cls.isPrimitive() || cls.getName().startsWith("java.")) {
            return false;
        }
        return HAS_ENCRYPTED_FIELD_CACHE.computeIfAbsent(cls, SecretFieldProcessor::computeHasEncryptedField);
    }

    /**
     * Transitive: a class "has" an encrypted field if it declares one directly, or if any field's
     * type (or, for a {@code Collection}/{@code Map} field, its element/value type) does. This is
     * what lets {@link #recurseInto} descend through e.g. {@code Application.routes} into
     * {@code Route.upstreams} to reach {@code Upstream.key} — {@code Route} itself carries no
     * {@code @EncryptedField}. {@code visiting} guards against infinite recursion on a cyclic type
     * graph; {@link #HAS_ENCRYPTED_FIELD_CACHE} only gets populated once the outer call returns, so
     * it can't protect against a cycle mid-computation on its own.
     */
    private static boolean computeHasEncryptedField(Class<?> cls) {
        return computeHasEncryptedField(cls, new HashSet<>());
    }

    private static boolean computeHasEncryptedField(Class<?> cls, Set<Class<?>> visiting) {
        if (!visiting.add(cls)) {
            return false;
        }
        try {
            for (Field f : declaredFieldsIncludingInherited(cls)) {
                if (f.isAnnotationPresent(EncryptedField.class)) {
                    return true;
                }
                Class<?> nested = resolveNestedClass(f);
                if (nested != null && !nested.isPrimitive() && !nested.getName().startsWith("java.")
                        && computeHasEncryptedField(nested, visiting)) {
                    return true;
                }
            }
            return false;
        } finally {
            visiting.remove(cls);
        }
    }

    /**
     * A field's own type, or — for a {@code Collection}/{@code Map} field — its generic
     * element/value type. Shared by {@link #computeHasEncryptedField} and by
     * {@link #elementClassWithEncryptedField}/{@link #valueClassWithEncryptedField}'s own
     * generic-type resolution.
     */
    private static Class<?> resolveNestedClass(Field field) {
        Class<?> type = field.getType();
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            if (field.getGenericType() instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                int idx = Map.class.isAssignableFrom(type) ? 1 : 0;
                if (args.length > idx && args[idx] instanceof Class<?> c) {
                    return c;
                }
            }
            return null;
        }
        return type;
    }
}
