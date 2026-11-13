package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExportRequestParserTest {

    private static final String MARKER = "SUBMITTED-VALUE-MARKER";
    private static final String PLATFORM_ROOT = "{\"type\":\"application\",\"url\":\"applications/platform/a\"}";

    private static String request(String rootsJson) {
        return "{\"roots\":" + rootsJson + "}";
    }

    private static HttpException assertRejected(String body) {
        HttpException error = assertThrows(HttpException.class, () -> ExportRequestParser.parse(body), body);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus(), body);
        assertFalse(error.getMessage().contains(MARKER), "the message echoes a submitted value: " + error.getMessage());
        return error;
    }

    @Test
    public void testRootOnlyRequestIsAccepted() {
        ExportRequest result = ExportRequestParser.parse(request("[" + PLATFORM_ROOT + "]"));

        assertEquals(1, result.roots().size());
        assertEquals(ResourceTypes.APPLICATION, result.roots().get(0).getType());
        assertEquals("platform/", result.roots().get(0).getBucketLocation());
        assertEquals("a", result.roots().get(0).getName());
    }

    @Test
    public void testPublicRootWithVersionAndFolderIsAccepted() {
        ExportRequest result = ExportRequestParser.parse(request(
                "[{\"type\":\"application\",\"url\":\"applications/public/folder/name__1.0.0\"}]"));

        assertEquals("public/", result.roots().get(0).getBucketLocation());
        assertEquals("name__1.0.0", result.roots().get(0).getName());
    }

    @Test
    public void testRootsKeepTheirOrderAndDuplicates() {
        ExportRequest result = ExportRequestParser.parse(request("["
                + PLATFORM_ROOT + ",{\"type\":\"application\",\"url\":\"applications/platform/b\"}," + PLATFORM_ROOT + "]"));

        assertEquals(List.of("a", "b", "a"), result.roots().stream().map(root -> root.getName()).toList());
    }

    @Test
    public void testMissingOrEmptyRootsAreRejected() {
        assertRejected("{}");
        assertRejected("{\"roots\":null}");
        assertRejected(request("[]"));
        assertRejected("null");
    }

    @Test
    public void testBodyThatIsNotJsonIsRejectedWithoutEchoingIt() {
        assertRejected("");
        assertRejected("   ");
        assertRejected("this is not json " + MARKER);
        assertRejected("{\"roots\":[" + MARKER);
    }

    @Test
    public void testUnknownFieldsAreRejectedAtEveryLevelWithoutEchoingThem() {
        assertRejected("{\"roots\":[" + PLATFORM_ROOT + "],\"" + MARKER + "\":1}");
        assertRejected("{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\",\"" + MARKER + "\":1}]}");
    }

    @Test
    public void testRootThatIsNotAnApplicationIsRejectedWithItsIndex() {
        HttpException error = assertRejected(request("[" + PLATFORM_ROOT + ",{\"type\":\"model\",\"url\":\"models/platform/m\"}]"));

        assertTrue(error.getMessage().contains("roots[1]"), error.getMessage());
    }

    static Stream<String> badRoots() {
        return Stream.of(
                "{\"type\":\"" + MARKER + "\",\"url\":\"applications/platform/a\"}",
                "{\"url\":\"applications/platform/a\"}",
                "{\"type\":\"application\"}",
                "null",
                "{\"type\":\"application\",\"url\":\"toolsets/platform/t\"}",
                "{\"type\":\"application\",\"url\":\"models/platform/m\"}",
                "{\"type\":\"toolset\",\"url\":\"toolsets/platform/t\"}",
                "{\"type\":\"toolset\",\"url\":\"toolsets/public/t\"}",
                "{\"type\":\"toolset\",\"url\":\"applications/platform/a\"}",
                "{\"type\":\"Application\",\"url\":\"applications/platform/a\"}",
                "{\"type\":\"application\",\"url\":\"applications/" + MARKER + "/a\"}",
                "{\"type\":\"application\",\"url\":\"" + MARKER + "\"}",
                "{\"type\":\"application\",\"url\":\"applications/platform\"}",
                "{\"type\":\"application\",\"url\":\"/applications/platform/a\"}",
                "{\"type\":\"application\",\"url\":\"applications/public/folder/\"}",
                "{\"type\":\"application\",\"url\":\"applications/platform/a b\"}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badRoots")
    public void testBadRootIsRejectedAndNothingSubmittedIsEchoed(String root) {
        assertRejected(request("[" + root + "]"));
    }
}
