package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.data.ModelData;
import com.epam.aidial.core.server.data.PricingData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FieldSelectionTest {

    private static final Set<String> ID_AND_OBJECT = Set.of("id", "object");

    private static final String ROWS = """
            [
              {
                "id": "gpt-4", "object": "model", "display_name": "GPT 4", "display_version": "1.0",
                "features": {"rate": true, "tokenize": false, "configuration": true, "reasoning_efforts": ["low", "high"]},
                "interface_configs": {
                  "openaiChatCompletions": {"features": {"reasoning_efforts": ["low"], "rate": true}, "defaults": {"temperature": 1}},
                  "anthropicMessages": {"features": {"reasoning_efforts": ["max"], "rate": false}}
                },
                "routes": {"r1": {"paths": ["/a", "/b"], "methods": ["GET"]}}
              },
              {"id": "app", "object": "application", "display_name": "App", "application_properties": {"p": "v"}}
            ]
            """;

    @Test
    void emptyOrBlankValueGivesNoSelection() {
        assertNull(parse(null));
        assertNull(parse(""));
        assertNull(parse("  "));
    }

    @Test
    void topLevelNamesAreKeptInResponseOrderWithIdAndObject() {
        JsonNode projected = project("display_version,display_name");

        assertEquals(2, projected.size());
        assertEquals(List.of("id", "object", "display_name", "display_version"), names(projected.get(0)));
        assertEquals(List.of("id", "object", "display_name"), names(projected.get(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"features", "id", "object", "id,object"})
    void idAndObjectAreAlwaysKept(String fields) {
        for (JsonNode row : project(fields)) {
            assertTrue(row.has("id"));
            assertTrue(row.has("object"));
        }
    }

    @Test
    void namingOnlyIdOrObjectReturnsExactlyBoth() {
        assertEquals(List.of("id", "object"), names(project("id").get(0)));
        assertEquals(List.of("id", "object"), names(project("object").get(0)));
    }

    @Test
    void nestedSelectionKeepsNamedMembersOfObjectsAndArrayElements() {
        JsonNode first = project("display_name,features(rate,tokenize),routes(*(paths))").get(0);

        assertEquals(List.of("id", "object", "display_name", "features", "routes"), names(first));
        assertEquals(List.of("rate", "tokenize"), names(first.get("features")));
        assertEquals(List.of("paths"), names(first.get("routes").get("r1")));

        Map<String, Object> row = map("{\"a\": [{\"b\": 1, \"c\": 2}, \"s\", 3]}");
        assertEquals(read("{\"a\": [{\"b\": 1}, \"s\", 3]}"), read(parse("a(b)").serialize(row)));
    }

    @Test
    void alwaysKeptNamesAreChosenByTheCaller() {
        Map<String, Object> row = map("{\"id\": \"x\", \"name\": \"n\", \"kind\": \"k\", \"size\": 1}");

        assertEquals(List.of("name", "kind"), names(read(FieldSelection.parse("name", Set.of("kind")).serialize(row))));
        assertEquals(List.of("name"), names(read(FieldSelection.parse("name", Set.of()).serialize(row))));
    }

    @Test
    void alwaysKeptNamesApplyOnlyAtTheTopLevel() {
        Map<String, Object> row = map("{\"id\": \"x\", \"a\": {\"id\": 1, \"b\": 2}}");

        assertEquals(read("{\"id\": \"x\", \"a\": {\"b\": 2}}"), read(parse("a(b)").serialize(row)));
    }

    @Test
    void selectionOnValueWithoutMembersLeavesItUnchanged() {
        JsonNode first = project("display_version(major),features(reasoning_efforts(x))").get(0);

        assertEquals("1.0", first.get("display_version").asText());
        assertEquals(read("{\"reasoning_efforts\": [\"low\", \"high\"]}"), first.get("features"));
    }

    @Test
    void wildcardSelectsEveryKeyOfMap() {
        JsonNode configs = project("interface_configs(*(features(reasoning_efforts)))").get(0).get("interface_configs");

        assertEquals(read("""
                {
                  "openaiChatCompletions": {"features": {"reasoning_efforts": ["low"]}},
                  "anthropicMessages": {"features": {"reasoning_efforts": ["max"]}}
                }
                """), configs);
    }

    @Test
    void repeatedNamesAreMerged() {
        assertEquals(List.of("rate", "tokenize"), names(project("features(rate),features(tokenize)").get(0).get("features")));
        assertEquals(4, project("features,features(rate)").get(0).get("features").size());
        assertEquals(4, project("*,features(rate)").get(0).get("features").size());
        assertEquals(List.of("rate", "tokenize"), names(project("features(rate),*(tokenize)").get(0).get("features")));

    }

    @Test
    void beanMembersAreSelectedLikeMapMembers() {
        JsonNode features = read(FieldSelection.parse("rate,tokenize", Set.of()).serialize(new FeaturesData()));

        assertEquals(List.of("rate", "tokenize"), names(features));
    }

    @Test
    void valueWrittenByCustomSerializerIsNotNarrowed() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "x");
        row.put("display_name", LocalizedValue.of(new LinkedHashMap<>(Map.of("en", "a", "de", "b"))));

        assertEquals(read("{\"id\": \"x\", \"display_name\": {\"en\": \"a\", \"de\": \"b\"}}"),
                read(parse("display_name(en)").serialize(row)));
    }

    @Test
    void decisionTreePricingRateIsReturnedWhole() throws Exception {
        PricingRate tree = new PricingRate();
        Condition condition = new Condition();
        condition.setField("prompt_tokens");
        condition.setOperator(Operator.values()[0]);
        condition.setValue(100);
        tree.setTest(condition);
        tree.setIfTrue(PricingRate.flat("1"));
        tree.setIfFalse(PricingRate.flat("2"));
        PricingData pricing = new PricingData();
        pricing.setPrompt(tree);
        ModelData model = new ModelData();
        model.setId("m");
        model.setPricing(pricing);

        JsonNode expectedTree = ProxyUtil.MAPPER.valueToTree(tree);
        for (String fields : List.of("*", "pricing", "pricing(prompt)", "pricing(prompt(test))")) {
            JsonNode prompt = read(parse(fields).serialize(List.of(model))).get(0).get("pricing").get("prompt");
            assertEquals(expectedTree, prompt, fields);
        }
        assertEquals(read(ProxyUtil.MAPPER.writeValueAsString(List.of(model))), read(parse("*").serialize(List.of(model))));
    }

    @Test
    void unionOfNameAndWildcardServesEveryRow() {
        Map<String, Object> row = rows().get(0);

        JsonNode projected = read(parse("features(rate),*(tokenize)").serialize(List.of(row, row)));

        assertEquals(List.of("rate", "tokenize"), names(projected.get(0).get("features")));
        assertEquals(projected.get(0), projected.get(1));
    }

    @Test
    void unknownNamesAreIgnored() {
        JsonNode projected = project("display_name,descriptio,application_properties");

        assertEquals(List.of("id", "object", "display_name"), names(projected.get(0)));
        assertEquals(List.of("id", "object", "display_name", "application_properties"), names(projected.get(1)));
    }

    @Test
    void topLevelWildcardKeepsEverything() {
        assertEquals(read(ROWS), project("*"));
    }

    @Test
    void whitespaceAroundNamesIsIgnored() {
        JsonNode first = project(" display_name , features ( rate ) ").get(0);

        assertEquals(List.of("id", "object", "display_name", "features"), names(first));
        assertEquals(List.of("rate"), names(first.get("features")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"features(rate", "features)", "features(rate))", "id,,display_name", "a(,b)", "()", "(a)", "a,", ",a", "a(b)c", "a(b)(c)"})
    void malformedSelectionIsRejected(String fields) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(fields));
        assertTrue(error.getMessage().contains("position"), error.getMessage());
    }

    @Test
    void selectionOverTheLengthLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("a".repeat(FieldSelection.MAX_LENGTH + 1)));
        parse("a".repeat(FieldSelection.MAX_LENGTH));
    }

    @Test
    void lengthLimitCountsBytes() {
        String twoByteCharacter = Character.toString(0xE9);
        assertThrows(IllegalArgumentException.class, () -> parse(twoByteCharacter.repeat(FieldSelection.MAX_LENGTH / 2 + 1)));
        parse(twoByteCharacter.repeat(FieldSelection.MAX_LENGTH / 2));
    }

    @Test
    void selectionOverTheDepthLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("a(b(c(d(e(f)))))"));
        parse("a(b(c(d(e))))");
    }

    private static FieldSelection parse(String fields) {
        return FieldSelection.parse(fields, ID_AND_OBJECT);
    }

    private static JsonNode project(String fields) {
        return read(parse(fields).serialize(rows()));
    }

    private static List<Map<String, Object>> rows() {
        return readValue(ROWS, new TypeReference<>() { });
    }

    private static Map<String, Object> map(String json) {
        return readValue(json, new TypeReference<>() { });
    }

    @SneakyThrows
    private static <T> T readValue(String json, TypeReference<T> type) {
        return ProxyUtil.MAPPER.readValue(json, type);
    }

    @SneakyThrows
    private static JsonNode read(String json) {
        return ProxyUtil.MAPPER.readTree(json);
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
