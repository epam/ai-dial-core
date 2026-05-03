package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.util.List;

public class EncryptedFieldMaskModifier extends BeanSerializerModifier {

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        for (int i = 0; i < beanProperties.size(); i++) {
            BeanPropertyWriter writer = beanProperties.get(i);
            AnnotatedMember member = writer.getMember();
            if (member != null && member.hasAnnotation(EncryptedField.class)) {
                beanProperties.set(i, new MaskingWriter(writer, isOriginallyWriteOnly(member)));
            }
        }
        return beanProperties;
    }

    private static boolean isOriginallyWriteOnly(AnnotatedMember member) {
        JsonProperty jp = member.getAnnotation(JsonProperty.class);
        return jp != null && jp.access() == JsonProperty.Access.WRITE_ONLY;
    }

    private static final class MaskingWriter extends BeanPropertyWriter {
        private static final String MASK = "***";

        private final boolean originalWriteOnly;

        MaskingWriter(BeanPropertyWriter base, boolean originalWriteOnly) {
            super(base);
            this.originalWriteOnly = originalWriteOnly;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = get(bean);
            if (value == null) {
                // WRITE_ONLY-marked secrets stay invisible when unset to preserve pre-existing
                // response shape; un-marked @EncryptedField fields keep their natural null projection.
                if (originalWriteOnly) {
                    return;
                }
                gen.writeNullField(getName());
                return;
            }
            gen.writeStringField(getName(), MASK);
        }

        @Override
        public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = get(bean);
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(MASK);
        }
    }
}
