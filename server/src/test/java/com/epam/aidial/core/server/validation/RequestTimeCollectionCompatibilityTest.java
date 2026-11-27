package com.epam.aidial.core.server.validation;

import com.epam.aidial.core.metaschemas.CatalogMetaSchemaHolder;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.getMetaschemaBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A request-time collection registers no structured recorder, and registering one changes nothing that the existing
 * collectors hold. The request-time getters create a plain {@link CollectorContext}, validate and
 * read {@link ListCollector.ResourceCollectorType#ALL_RESOURCES}; these tests do the same with the production keywords.
 */
class RequestTimeCollectionCompatibilityTest {

    private static final String APPLICATION_SCHEMA = """
            {"type":"object","properties":{
              "serverResource":{"type":"string","dial:resource":true,
                "dial:meta":{"dial:propertyKind":"server","dial:propertyOrder":1}},
              "clientFile":{"type":"string","dial:file":true,
                "dial:meta":{"dial:propertyKind":"client","dial:propertyOrder":2}}}}""";

    private static final String APPLICATION_PROPERTIES = """
            {"serverResource":"toolsets/platform/s","clientFile":"files/public/c"}""";

    private static final String CATALOG_SCHEMA = """
            {"type":"object","properties":{"badge":{"type":"string","dial:file":true}}}""";

    private static CollectorContext validateApplication(CollectorContext context) {
        JsonMetaSchema meta = getMetaschemaBuilder()
                .keyword(new DialMetaKeyword())
                .keyword(new DialFileKeyword())
                .keyword(new DialResourceKeyKeyword())
                .build();
        JsonSchema schema = JsonSchemaFactory.builder().metaSchema(meta).defaultMetaSchemaIri(meta.getIri()).build()
                .getSchema(APPLICATION_SCHEMA);
        schema.validate(APPLICATION_PROPERTIES, InputFormat.JSON, execution -> execution.setCollectorContext(context));
        return context;
    }

    private static CollectorContext validateCatalog(CollectorContext context) {
        JsonMetaSchema meta = CatalogMetaSchemaHolder.getMetaschemaBuilder().keyword(new CatalogFileKeyword()).build();
        JsonSchema schema = JsonSchemaFactory.builder().metaSchema(meta).defaultMetaSchemaIri(meta.getIri()).build()
                .getSchema(CATALOG_SCHEMA);
        schema.validate("{\"badge\":\"files/public/b.png\"}", InputFormat.JSON, execution -> execution.setCollectorContext(context));
        return context;
    }

    @SuppressWarnings("unchecked")
    private static List<String> values(CollectorContext context, ListCollector.ResourceCollectorType type) {
        Object collector = context.getCollectorMap().get(type.getValue());
        assertNotNull(collector);
        return ((ListCollector<String>) collector).collect();
    }

    static Stream<Arguments> keywordsAndWhatTheyCollect() {
        return Stream.of(
                Arguments.of("application keywords", (UnaryOperator<CollectorContext>) RequestTimeCollectionCompatibilityTest::validateApplication,
                        Map.of(ListCollector.ResourceCollectorType.ALL_RESOURCES, List.of("toolsets/platform/s", "files/public/c"),
                                ListCollector.ResourceCollectorType.ONLY_SERVER_RESOURCES, List.of("toolsets/platform/s"))),
                Arguments.of("catalog keyword", (UnaryOperator<CollectorContext>) RequestTimeCollectionCompatibilityTest::validateCatalog,
                        Map.of(ListCollector.ResourceCollectorType.ALL_RESOURCES, List.of("files/public/b.png"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keywordsAndWhatTheyCollect")
    void keywordsRegisterNoStructuredRecorderWithoutOptIn(String keywords, UnaryOperator<CollectorContext> validate,
                                                          Map<ListCollector.ResourceCollectorType, List<String>> expectedValues) {
        CollectorContext context = validate.apply(new CollectorContext());

        assertFalse(context.getCollectorMap().containsKey(MarkerRecorder.KEY));
        expectedValues.forEach((type, expected) -> assertEquals(expected, values(context, type), type.name()));
    }

    @Test
    void registeringRecorderChangesNothingThatTheExistingCollectorsHold() {
        CollectorContext plain = validateApplication(new CollectorContext());
        CollectorContext withRecorder = new CollectorContext();
        MarkerRecorder recorder = new MarkerRecorder();
        withRecorder.getCollectorMap().put(MarkerRecorder.KEY, recorder);

        validateApplication(withRecorder);

        for (ListCollector.ResourceCollectorType type : ListCollector.ResourceCollectorType.values()) {
            assertEquals(values(plain, type), values(withRecorder, type), type.name());
        }
        Map<String, Object> extra = new java.util.HashMap<>(withRecorder.getCollectorMap());
        extra.keySet().removeAll(plain.getCollectorMap().keySet());
        assertEquals(Set.of(MarkerRecorder.KEY), extra.keySet(), "the recorder is the only addition");
        assertEquals(2, recorder.listReferences().size());
    }
}
