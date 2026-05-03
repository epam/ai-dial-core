package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class SecretFieldProcessor {

    public static final String ENC_PREFIX = "ENC[";
    public static final String ENC_SUFFIX = "]";
    public static final String SECRET_REF_PREFIX = "${SECRET:";
    public static final String MASK_SENTINEL = "***";

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
        walk(entity, aad, true);
    }

    public void decryptFields(Object entity, ResourceDescriptor descriptor) {
        if (entity == null) {
            return;
        }
        byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        walk(entity, aad, false);
    }

    public String resolveSecret(String value, ResourceDescriptor descriptor) {
        if (value == null) {
            return null;
        }
        if (value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX)) {
            byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
            return decryptEnvelope(value, aad, "value");
        }
        return value;
    }

    public void validateNoMaskSentinel(JsonNode requestNode, Class<?> entityClass) {
        if (requestNode == null || !requestNode.isObject()) {
            return;
        }
        for (Field field : declaredFieldsIncludingInherited(entityClass)) {
            String name = field.getName();
            if (field.isAnnotationPresent(EncryptedField.class)) {
                JsonNode child = requestNode.get(name);
                if (child != null && !child.isNull() && MASK_SENTINEL.equals(child.asText())) {
                    throw new IllegalArgumentException("Secret field '" + name
                            + "' contains the mask sentinel '***'. Provide a real secret value or omit the field.");
                }
            }
            Class<?> nested = elementClassWithEncryptedField(field);
            if (nested != null) {
                JsonNode arr = requestNode.get(name);
                if (arr != null && arr.isArray()) {
                    for (JsonNode item : arr) {
                        validateNoMaskSentinel(item, nested);
                    }
                }
            }
        }
    }

    public static ObjectNode maskInPayload(JsonNode payload, Class<?> entityClass) {
        if (!(payload instanceof ObjectNode object)) {
            return null;
        }
        ObjectNode masked = object.deepCopy();
        applyMask(masked, entityClass);
        return masked;
    }

    private static void applyMask(ObjectNode target, Class<?> entityClass) {
        for (Field field : declaredFieldsIncludingInherited(entityClass)) {
            String name = field.getName();
            if (field.isAnnotationPresent(EncryptedField.class)) {
                JsonNode current = target.get(name);
                if (current != null && !current.isNull()) {
                    target.put(name, MASK_SENTINEL);
                }
            }
            Class<?> nestedType = elementClassWithEncryptedField(field);
            if (nestedType != null) {
                JsonNode arr = target.get(name);
                if (arr != null && arr.isArray()) {
                    for (JsonNode item : arr) {
                        if (item instanceof ObjectNode itemObj) {
                            applyMask(itemObj, nestedType);
                        }
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
                boolean omitted = current == null || current.isNull()
                        || (current.isTextual() && MASK_SENTINEL.equals(current.asText()));
                if (omitted) {
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
                if (targetArr != null && targetArr.isArray() && sourceArr != null && sourceArr.isArray()) {
                    int n = Math.min(targetArr.size(), sourceArr.size());
                    for (int i = 0; i < n; i++) {
                        JsonNode targetItem = targetArr.get(i);
                        JsonNode sourceItem = sourceArr.get(i);
                        if (targetItem instanceof ObjectNode targetObj && sourceItem.isObject()) {
                            mergeInto(targetObj, sourceItem, nestedType);
                        }
                    }
                }
            }
        }
    }

    private void walk(Object entity, byte[] aad, boolean encrypt) {
        if (entity == null) {
            return;
        }
        Class<?> cls = entity.getClass();
        for (Field field : declaredFieldsIncludingInherited(cls)) {
            field.setAccessible(true);
            try {
                if (field.isAnnotationPresent(EncryptedField.class) && field.getType() == String.class) {
                    String value = (String) field.get(entity);
                    String transformed = encrypt ? encryptValue(value, aad, field.getName())
                            : decryptValue(value, aad, field.getName());
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
        if (value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX)) {
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
        List<Field> result = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.isSynthetic()) {
                    result.add(f);
                }
            }
            c = c.getSuperclass();
        }
        return result;
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

    private static boolean classHasEncryptedField(Class<?> cls) {
        if (cls == null || cls.isPrimitive() || cls.getName().startsWith("java.")) {
            return false;
        }
        for (Field f : declaredFieldsIncludingInherited(cls)) {
            if (f.isAnnotationPresent(EncryptedField.class)) {
                return true;
            }
        }
        return false;
    }
}
