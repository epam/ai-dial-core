package com.epam.aidial.core.server.validation;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.ValidationMessage;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of collecting the markers of one application.
 *
 * @param references the marked values, in traversal order; the same value at two pointers is two references
 */
public record MarkerCollection(List<MarkerReference> references, List<CollectionIssue> issues) {

    /**
     * One marked value found in the properties of an application.
     *
     * @param pointer RFC 6901 pointer relative to {@code application_properties}
     * @param value   the original value, neither parsed nor checked
     */
    public record MarkerReference(String pointer, String value) {
    }

    public static final MarkerCollection EMPTY = new MarkerCollection(List.of(), List.of());

    public static MarkerCollection ofIssue(CollectionIssueKind kind) {
        return new MarkerCollection(List.of(), List.of(new CollectionIssue(kind, "")));
    }

    /**
     * Validates the properties against the schema with a {@link MarkerRecorder} registered and returns what was recorded together with one
     * issue for each violated location and keyword. A schema that the library cannot evaluate gives {@code SCHEMA_UNAVAILABLE}. The issues
     * carry no library message and no value, because a message can hold the instance value or an IRI.
     *
     * @param properties the properties of the application or of the catalog entry, which are serialized as they are
     */
    public static MarkerCollection collect(JsonSchema schema, Object properties) {
        String propertiesJson;
        try {
            propertiesJson = ProxyUtil.MAPPER.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize the properties", e);
        }
        MarkerRecorder recorder = new MarkerRecorder();
        CollectorContext collectorContext = new CollectorContext();
        collectorContext.getCollectorMap().put(MarkerRecorder.KEY, recorder);
        Set<ValidationMessage> messages;
        try {
            messages = schema.validate(propertiesJson, InputFormat.JSON, execution -> execution.setCollectorContext(collectorContext));
        } catch (JsonSchemaException e) {
            return ofIssue(CollectionIssueKind.SCHEMA_UNAVAILABLE);
        }
        return new MarkerCollection(recorder.listReferences(), buildInstanceIssues(messages));
    }

    /**
     * One issue per violated location and keyword, sorted by pointer so that the result is deterministic; it holds no library message,
     * which can contain the instance value.
     */
    private static List<CollectionIssue> buildInstanceIssues(Set<ValidationMessage> messages) {
        Map<List<String>, CollectionIssue> issues = new LinkedHashMap<>();
        for (ValidationMessage message : messages) {
            String pointer = MarkerRecorder.toPointer(message.getInstanceLocation());
            String keyword = String.valueOf(message.getType());
            issues.putIfAbsent(List.of(pointer, keyword),
                    new CollectionIssue(CollectionIssueKind.INSTANCE_INVALID, pointer));
        }
        List<CollectionIssue> result = new ArrayList<>(issues.values());
        result.sort(Comparator.comparing(CollectionIssue::pointer));
        return result;
    }
}
