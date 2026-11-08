package com.epam.aidial.core.server.validation;

import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.PathType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerRecorderTest {

    private static JsonNodePath path(Object... segments) {
        JsonNodePath result = new JsonNodePath(PathType.DEFAULT);
        for (Object segment : segments) {
            result = segment instanceof Integer index ? result.append(index) : result.append((String) segment);
        }
        return result;
    }

    @Test
    void recordKeepsKindPointerAndValueInEvaluationOrder() {
        CollectorContext context = new CollectorContext();
        MarkerRecorder recorder = new MarkerRecorder();
        context.getCollectorMap().put(MarkerRecorder.KEY, recorder);

        MarkerRecorder.record(context, MarkerKind.FILE, path("list", 1), "files/b/x");
        MarkerRecorder.record(context, MarkerKind.RESOURCE, path("obj", "inner"), "toolsets/platform/t");

        assertEquals(List.of(new MarkerReference(MarkerKind.FILE, "/list/1", "files/b/x"),
                new MarkerReference(MarkerKind.RESOURCE, "/obj/inner", "toolsets/platform/t")), recorder.references());
    }

    @Test
    void recordCollapsesTheSameKindPointerAndValueButNotTheSameValueAtTwoPointers() {
        CollectorContext context = new CollectorContext();
        MarkerRecorder recorder = new MarkerRecorder();
        context.getCollectorMap().put(MarkerRecorder.KEY, recorder);

        MarkerRecorder.record(context, MarkerKind.RESOURCE, path("a"), "v");
        MarkerRecorder.record(context, MarkerKind.RESOURCE, path("a"), "v");
        MarkerRecorder.record(context, MarkerKind.RESOURCE, path("b"), "v");

        assertEquals(2, recorder.references().size());
    }

    @Test
    void toPointerEscapesTildeAndSlashAndGivesTheEmptyPointerForTheRoot() {
        assertEquals("", MarkerRecorder.toPointer(path()));
        assertEquals("/a~1b/c~0d/e f/0", MarkerRecorder.toPointer(path("a/b", "c~d", "e f", 0)));
    }
}
