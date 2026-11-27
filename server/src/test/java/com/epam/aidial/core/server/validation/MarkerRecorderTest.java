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
    void recordKeepsPointerAndValueInEvaluationOrder() {
        CollectorContext context = new CollectorContext();
        MarkerRecorder recorder = new MarkerRecorder();
        context.getCollectorMap().put(MarkerRecorder.KEY, recorder);

        MarkerRecorder.record(context, path("list", 1), "files/b/x");
        MarkerRecorder.record(context, path("obj", "inner"), "toolsets/platform/t");

        assertEquals(List.of(new MarkerReference("/list/1", "files/b/x"),
                new MarkerReference("/obj/inner", "toolsets/platform/t")), recorder.listReferences());
    }

    @Test
    void recordCollapsesTheSamePointerAndValueButNotTheSameValueAtTwoPointers() {
        CollectorContext context = new CollectorContext();
        MarkerRecorder recorder = new MarkerRecorder();
        context.getCollectorMap().put(MarkerRecorder.KEY, recorder);

        MarkerRecorder.record(context, path("a"), "v");
        MarkerRecorder.record(context, path("a"), "v");
        MarkerRecorder.record(context, path("b"), "v");

        assertEquals(2, recorder.listReferences().size());
    }

    @Test
    void toPointerEscapesTildeAndSlashAndGivesTheEmptyPointerForTheRoot() {
        assertEquals("", MarkerRecorder.toPointer(path()));
        assertEquals("/a~1b/c~0d/e f/0", MarkerRecorder.toPointer(path("a/b", "c~d", "e f", 0)));
    }
}
