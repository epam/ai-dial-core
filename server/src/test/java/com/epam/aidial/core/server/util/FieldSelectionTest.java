package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.data.ModelData;
import com.epam.aidial.core.server.data.PricingData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

        ObjectNode row = object("{\"a\": [{\"b\": 1, \"c\": 2}, \"s\", 3]}");
        assertEquals(read("{\"a\": [{\"b\": 1}, \"s\", 3]}"), parse("a(b)").project(row));
    }

    @Test
    void alwaysKeptNamesAreChosenByTheCaller() {
        ObjectNode row = object("{\"id\": \"x\", \"name\": \"n\", \"kind\": \"k\", \"size\": 1}");

        assertEquals(List.of("name", "kind"), names(FieldSelection.parse("name", Set.of("kind")).project(row)));
        assertEquals(List.of("name"), names(FieldSelection.parse("name", Set.of()).project(row)));
    }

    @Test
    void alwaysKeptNamesApplyOnlyAtTheTopLevel() {
        ObjectNode row = object("{\"id\": \"x\", \"a\": {\"id\": 1, \"b\": 2}}");

        assertEquals(read("{\"id\": \"x\", \"a\": {\"b\": 2}}"), parse("a(b)").project(row));
    }

    @Test
    void selectionOnValueWithoutMembersLeavesItUnchanged() {
        JsonNode first = project("display_version(major),features(reasoning_efforts(x))").get(0);

        assertEquals("1.0", first.get("display_version").asText());
        assertEquals(read("{\"reasoning_efforts\": [\"low\", \"high\"]}"), first.get("features"));

        ObjectNode row = object("{\"a\": null, \"b\": 5, \"c\": [\"x\", \"y\"]}");
        assertEquals(row, parse("a(x),b(x),c(x)").project(row));
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
    void wildcardAndNamedSelectionsBothApplyBelowTheirLevel() {
        JsonNode configs = project("interface_configs(*(features(reasoning_efforts)),openaiChatCompletions(defaults))")
                .get(0).get("interface_configs");

        assertEquals(read("""
                {
                  "openaiChatCompletions": {"features": {"reasoning_efforts": ["low"]}, "defaults": {"temperature": 1}},
                  "anthropicMessages": {"features": {"reasoning_efforts": ["max"]}}
                }
                """), configs);
    }

    @Test
    void repeatedNamesAreMerged() {
        assertEquals(List.of("rate", "tokenize"), names(project("features(rate),features(tokenize)").get(0).get("features")));
        assertEquals(4, project("features,features(rate)").get(0).get("features").size());
        assertEquals(List.of("rate", "tokenize"), names(project("features(rate),*(tokenize)").get(0).get("features")));
    }

    @Test
    void namedWholeSelectionWinsOverWildcardSelection() {
        JsonNode first = project("features,*(tokenize)").get(0);

        assertEquals(read(ROWS).get(0).get("features"), first.get("features"));
        assertEquals("GPT 4", first.get("display_name").asText());
        assertEquals(read("{}"), first.get("routes"));
    }

    @Test
    void wholeMatchBelowLevelWinsOverNarrowerMatch() {
        JsonNode configs = project("interface_configs(openaiChatCompletions(features),*(features(rate)))")
                .get(0).get("interface_configs");

        assertEquals(read("""
                {
                  "openaiChatCompletions": {"features": {"reasoning_efforts": ["low"], "rate": true}},
                  "anthropicMessages": {"features": {"rate": false}}
                }
                """), configs);
    }

    @Test
    void beanMembersAreSelectedLikeMapMembers() {
        assertEquals(List.of("rate", "tokenize"), names(FieldSelection.parse("rate,tokenize", Set.of()).project(tree(new FeaturesData()))));
    }

    @Test
    void localeOfLocalizedValueCanBeSelected() {
        ModelData model = new ModelData();
        model.setId("x");
        model.setDisplayName(LocalizedValue.of(new LinkedHashMap<>(Map.of("en", "a", "de", "b"))));

        assertEquals(read("{\"id\": \"x\", \"object\": \"model\", \"display_name\": {\"en\": \"a\"}}"),
                parse("display_name(en)").project(tree(model)));
    }

    @Test
    void decisionTreePricingRateMembersCanBeSelected() {
        PricingRate rate = decisionTree();
        PricingData pricing = new PricingData();
        pricing.setPrompt(rate);
        ModelData model = new ModelData();
        model.setId("m");
        model.setPricing(pricing);

        JsonNode expectedRate = ProxyUtil.MAPPER.valueToTree(rate);
        for (String fields : List.of("pricing", "pricing(prompt)")) {
            assertEquals(expectedRate, parse(fields).project(tree(model)).get("pricing").get("prompt"), fields);
        }
        assertEquals(tree(model).get("pricing"), parse("pricing").project(tree(model)).get("pricing"));
        assertEquals(read("{\"test\": {\"field\": \"prompt_tokens\"}, \"ifTrue\": \"1\"}"),
                parse("pricing(prompt(test(field),ifTrue))").project(tree(model)).get("pricing").get("prompt"));
    }

    @Test
    void selectingEveryMemberKeepsTheBytesOfTheDirectSerialization() throws Exception {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("integer", 1);
        defaults.put("long", 12345678901L);
        defaults.put("double", 0.1);
        defaults.put("flag", true);
        defaults.put("nothing", null);
        defaults.put("list", List.of(1, 2.5, "x"));
        PricingData pricing = new PricingData();
        pricing.setUnit("token");
        pricing.setPrompt(PricingRate.flat("0.0000003"));
        pricing.setCompletion(decisionTree());
        ModelData model = new ModelData();
        model.setId("m");
        model.setDisplayName(LocalizedValue.of(new LinkedHashMap<>(Map.of("en", "a", "de", "b"))));
        model.setDescription(LocalizedValue.of("plain"));
        model.setDefaults(defaults);
        model.setCatalogProperties(new LinkedHashMap<>(Map.of("k", "v")));
        model.setPricing(pricing);

        String direct = ProxyUtil.MAPPER.writeValueAsString(model);
        FieldSelection selection = parse(String.join(",", names(read(direct))));

        assertEquals(direct, ProxyUtil.MAPPER.writeValueAsString(selection.project(tree(model))));
    }

    @Test
    void membersOfJsonNodeValueCanBeSelected() {
        ModelData model = new ModelData();
        model.setId("m");
        model.setDefaults(Map.of("node", ProxyUtil.MAPPER.createObjectNode().put("x", 1).put("y", 2)));

        assertEquals(read("{\"x\": 1}"), parse("defaults(node(x))").project(tree(model)).get("defaults").get("node"));
    }

    @Test
    void unknownNamesAreIgnored() {
        JsonNode projected = project("display_name,descriptio,application_properties");

        assertEquals(List.of("id", "object", "display_name"), names(projected.get(0)));
        assertEquals(List.of("id", "object", "display_name", "application_properties"), names(projected.get(1)));
    }

    @Test
    void bareWildcardAtTheTopKeepsEverythingSoItIsNoSelection() {
        assertNull(parse("*"));
        assertNull(parse("*,features(rate)"));
        assertNull(parse("features(rate), * "));
        assertNull(parse("*(*)"));
        assertNull(parse("display_name,*"));
        assertThrows(IllegalArgumentException.class, () -> parse("*,a("));
    }

    @Test
    void wildcardInsideAnObjectKeepsTheWholeObject() {
        JsonNode first = project("features(*,rate),interface_configs(*(*))").get(0);

        assertEquals(read(ROWS).get(0).get("features"), first.get("features"));
        assertEquals(read(ROWS).get(0).get("interface_configs"), first.get("interface_configs"));
    }

    @Test
    void wildcardWithNameNarrowsEveryObjectMemberToIt() {
        JsonNode first = project("*(en)").get(0);

        assertEquals(read("""
                {
                  "id": "gpt-4", "object": "model", "display_name": "GPT 4", "display_version": "1.0",
                  "features": {}, "interface_configs": {}, "routes": {}
                }
                """), first);
    }

    @Test
    void nestedArraysAreSelectedInto() {
        ObjectNode row = object("{\"m\": [[{\"a\": 1, \"b\": 2}], \"s\", [[{\"b\": 3}]]]}");

        assertEquals(read("{\"m\": [[{\"a\": 1}], \"s\", [[{}]]]}"), parse("m(a)").project(row));
    }

    @Test
    void objectWithoutTheNamedMembersIsLeftEmpty() {
        JsonNode first = project("features(rat),interface_configs(*(feature))").get(0);

        assertEquals(read("{}"), first.get("features"));
        assertEquals(read("{\"openaiChatCompletions\": {}, \"anthropicMessages\": {}}"), first.get("interface_configs"));
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

    @Test
    void wildcardsAtSeveralLevelsAreNotMergedIntoTheParsedSelection() {
        String names = IntStream.range(0, 37).mapToObj(i -> "n" + i + "(z)").collect(Collectors.joining(","));
        String fields = "*(*(*(" + names + ")," + names + ")," + names + ")," + names;

        assertTrue(parse(fields).countMembers() <= fields.length());
        assertEquals(2, project(fields).size());
    }

    private static PricingRate decisionTree() {
        Condition condition = new Condition();
        condition.setField("prompt_tokens");
        condition.setOperator(Operator.EQ);
        condition.setValue(100);
        PricingRate rate = new PricingRate();
        rate.setTest(condition);
        rate.setIfTrue(PricingRate.flat("1"));
        rate.setIfFalse(PricingRate.flat("2"));
        return rate;
    }

    private static FieldSelection parse(String fields) {
        return FieldSelection.parse(fields, ID_AND_OBJECT);
    }

    private static ArrayNode project(String fields) {
        FieldSelection selection = parse(fields);
        ArrayNode projected = ProxyUtil.MAPPER.createArrayNode();
        for (JsonNode row : read(ROWS)) {
            projected.add(selection.project((ObjectNode) row));
        }
        return projected;
    }

    private static ObjectNode tree(Object value) {
        return ProxyUtil.MAPPER.valueToTree(value);
    }

    private static ObjectNode object(String json) {
        return (ObjectNode) read(json);
    }

    @SneakyThrows
    private static JsonNode read(String json) {
        return ProxyUtil.MAPPER.readTree(json);
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
