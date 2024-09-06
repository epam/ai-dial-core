package com.epam.aidial.core.util;

import com.epam.aidial.core.config.Upstream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonToStringDeserializerTest {

    @ParameterizedTest
    @MethodSource("datasource")
    void deserialize(String json, Object expected) throws IOException {
        var upstream = ProxyUtil.MAPPER.readValue(json, Upstream.class);

        assertNotNull(upstream);
        assertEquals(upstream.getExtraData(), expected);
    }

    private static List<Arguments> datasource() {
        return List.of(
                Arguments.of("{\"extraData\":{\"key\":\"value\"}}", "{\"key\":\"value\"}"),
                Arguments.of("{\"extraData\":\"str\"}", "str"),
                Arguments.of("{\"extraData\":0}", "0"),
                Arguments.of("{\"extraData\":null}", null),
                Arguments.of("{}", null)
        );
    }
}
