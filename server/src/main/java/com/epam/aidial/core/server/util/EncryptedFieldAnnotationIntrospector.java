package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

/**
 * Forces {@code @EncryptedField} fields to be visible to serializers regardless of any
 * {@code @JsonProperty(access = WRITE_ONLY)} on the same field. The masking and blob-write
 * {@link com.fasterxml.jackson.databind.ser.BeanSerializerModifier}s require the property in
 * the writer list — without this override Jackson skips it at serialization time. WRITE_ONLY
 * remains as defense-in-depth for code paths that use an unconfigured {@code ObjectMapper}.
 */
public class EncryptedFieldAnnotationIntrospector extends JacksonAnnotationIntrospector {

    @Override
    public JsonProperty.Access findPropertyAccess(Annotated annotated) {
        if (annotated.hasAnnotation(EncryptedField.class)) {
            return JsonProperty.Access.READ_WRITE;
        }
        return super.findPropertyAccess(annotated);
    }
}
