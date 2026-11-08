package com.epam.aidial.core.server.validation;

import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.ValidationMessage;

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
 * @param issues     the problems met
 */
public record MarkerCollection(List<MarkerReference> references, List<CollectionIssue> issues) {

    /**
     * One marked value found in the properties of an application.
     *
     * @param kind    the marker that holds it
     * @param pointer RFC 6901 pointer relative to {@code application_properties}
     * @param value   the original value, neither parsed nor checked
     */
    public record MarkerReference(MarkerKind kind, String pointer, String value) {
    }

    /**
     * Whether the collection ended without an issue, so no reference could have been missed.
     */
    public boolean isComplete() {
        return issues.isEmpty();
    }

    /**
     * A collection that holds only the issue of a schema that cannot be used: it does not exist or cannot be evaluated.
     */
    public static MarkerCollection ofIssue(CollectionIssueKind kind) {
        return new MarkerCollection(List.of(), List.of(new CollectionIssue(kind, "", "The schema cannot be evaluated")));
    }

    /**
     * Validates the properties against the schema with a {@link MarkerRecorder} registered, so the marker keywords record
     * every marked value, and returns what was recorded together with one issue for each violated location and keyword. A
     * schema that the library cannot evaluate gives the {@code SCHEMA_UNAVAILABLE} issue. The issues carry no library
     * message and no value, because a message can hold the instance value or an IRI.
     */
    public static MarkerCollection collect(JsonSchema schema, String propertiesJson) {
        MarkerRecorder recorder = new MarkerRecorder();
        CollectorContext collectorContext = new CollectorContext();
        collectorContext.getCollectorMap().put(MarkerRecorder.KEY, recorder);
        Set<ValidationMessage> messages;
        try {
            messages = schema.validate(propertiesJson, InputFormat.JSON, execution -> execution.setCollectorContext(collectorContext));
        } catch (JsonSchemaException e) {
            return ofIssue(CollectionIssueKind.SCHEMA_UNAVAILABLE);
        }
        return new MarkerCollection(recorder.references(), instanceIssues(messages));
    }

    /**
     * One issue per violated location and keyword, sorted so that the result is deterministic. The text is only the keyword
     * name: the library's message can contain the instance value.
     */
    private static List<CollectionIssue> instanceIssues(Set<ValidationMessage> messages) {
        Map<List<String>, CollectionIssue> issues = new LinkedHashMap<>();
        for (ValidationMessage message : messages) {
            String pointer = MarkerRecorder.toPointer(message.getInstanceLocation());
            String keyword = String.valueOf(message.getType());
            issues.putIfAbsent(List.of(pointer, keyword),
                    new CollectionIssue(CollectionIssueKind.INSTANCE_INVALID, pointer, "Violated keyword: " + keyword));
        }
        List<CollectionIssue> result = new ArrayList<>(issues.values());
        result.sort(Comparator.comparing(CollectionIssue::pointer).thenComparing(CollectionIssue::message));
        return result;
    }
}
