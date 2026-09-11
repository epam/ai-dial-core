package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeaturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Field> featureFields() {
        return Arrays.stream(Features.class.getDeclaredFields()).filter(field -> !field.isSynthetic());
    }

    @ParameterizedTest
    @MethodSource("featureFields")
    void everyFeatureCanBeInheritedAndOverridden(Field field) throws Exception {
        field.setAccessible(true);
        Object inherited = field.getType() == Boolean.class ? true
                : field.getType() == String.class ? "http://deployment" : List.of("low", "high");
        Object overridden = field.getType() == Boolean.class ? false
                : field.getType() == String.class ? "http://interface" : List.of();
        Features base = new Features();
        field.set(base, inherited);
        Features overrides = new Features();

        assertEquals(inherited, field.get(Features.merge(base, overrides)));
        field.set(overrides, overridden);
        assertEquals(overridden, field.get(Features.merge(base, overrides)));
        assertEquals(overridden, field.get(Features.merge(null, overrides)));
        assertEquals(inherited, field.get(base));
        assertEquals(overridden, field.get(overrides));
    }

    @Test
    void absentFeaturesStayAbsent() {
        assertNull(Features.merge(null, null));
        assertEquals(new Features(), Features.merge(null, new Features()));
    }

    @Test
    void emptyReasoningEffortsSurviveSerialization() throws Exception {
        Features features = MAPPER.readValue("{\"reasoning_efforts\":[]}", Features.class);

        assertEquals("{\"reasoning_efforts\":[]}", MAPPER.writeValueAsString(features));
        assertEquals("{}", MAPPER.writeValueAsString(new Features()));
    }
}
