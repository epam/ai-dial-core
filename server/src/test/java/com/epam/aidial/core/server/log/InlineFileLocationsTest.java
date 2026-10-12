package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.log.InlineFileLocations.DataUrl;
import com.epam.aidial.core.server.log.InlineFileLocations.LocationSet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.epam.aidial.core.server.log.InlineFileLocations.isBinaryType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InlineFileLocationsTest {

    @Test
    public void testChatCompletionsLocationsMatchWholePathsWithWildcards() {
        LocationSet chat = InlineFileLocations.forInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertNotNull(chat.find(path("/messages/3/content/0/image_url/url")));
        assertNotNull(chat.find(path("/choices/0/delta/custom_content/stages/2/attachments/1/data")));
        assertNull(chat.find(path("/messages/3/content/0/image_url")));
        assertNull(chat.find(path("/messages/3/content/0/image_url/url/x")));
        assertNull(chat.find(path("/messages/3/content")));
    }

    @Test
    public void testLocationSetsArePerInterface() {
        assertNull(InlineFileLocations.forInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS).find(path("/input/0/result")));
        assertNotNull(InlineFileLocations.forInterface(InterfaceType.OPENAI_RESPONSES).find(path("/input/0/result")));
        assertNotNull(InlineFileLocations.forInterface(InterfaceType.ANTHROPIC_MESSAGES).find(path("/messages/0/content/1/content/0/source/data")));
        assertNotNull(InlineFileLocations.forInterface(InterfaceType.ANTHROPIC_MESSAGES).find(path("/messages/0/content/1/content/content/source/data")));
        assertTrue(InlineFileLocations.forInterface(InterfaceType.OPENAI_EMBEDDINGS).isEmpty());
        assertTrue(InlineFileLocations.forInterface(null).isEmpty());
    }

    @Test
    public void testNamesHoldTheLastSegmentOfEveryLocation() {
        LocationSet responses = InlineFileLocations.forInterface(InterfaceType.OPENAI_RESPONSES);

        assertTrue(responses.names().containsAll(List.of("image_url", "file_data", "result", "partial_image_b64")));
        assertFalse(responses.names().contains("text"));
    }

    @Test
    public void testBinaryTypes() {
        assertTrue(isBinaryType("image/png"));
        assertTrue(isBinaryType("IMAGE/JPEG; q=1"));
        assertTrue(isBinaryType("audio/wav"));
        assertTrue(isBinaryType("application/pdf"));
        assertTrue(isBinaryType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertFalse(isBinaryType("image/svg+xml"));
        assertFalse(isBinaryType("text/markdown"));
        assertFalse(isBinaryType("application/json"));
        assertFalse(isBinaryType("application/vnd.dial.chart+json"));
        assertFalse(isBinaryType(""));
        assertFalse(isBinaryType(null));
    }

    @Test
    public void testDataUrlPrefixAndMime() {
        assertEquals(new DataUrl(1 + "data:image/png;base64,".length(), "image/png"), dataUrl("data:image/png;base64,AAAA"));
        assertEquals("image/png", dataUrl("data:image/png;name=a.png;base64,AAAA").mime());
        assertEquals("image/png", dataUrl("DATA:image/png;BASE64,AAAA").mime());
        assertNull(dataUrl("data:;base64,AAAA").mime());
    }

    @Test
    public void testEscapedDataUrlPrefixIsUnescapedForTheChecksOnly() {
        // raw bytes: data:image\/png;base64,AAAA - the payload starts after the raw comma
        DataUrl url = dataUrl("data:image\\/png;base64,AAAA");

        assertEquals(new DataUrl(1 + "data:image\\/png;base64,".length(), "image/png"), url);
    }

    @Test
    public void testNonBase64DataUrlsAreRejected() {
        assertNull(dataUrl("data:text/plain,hello;base64,AAAA"));
        assertNull(dataUrl("data:image/png,AAAA"));
        assertNull(dataUrl("https://example.com/a.png"));
    }

    @Test
    public void testDataUrlPrefixMustEndBeforeTheGivenEnd() {
        byte[] bytes = "\"data:image/png;base64,AAAA\"".getBytes(StandardCharsets.UTF_8);

        assertNull(InlineFileLocations.parseDataUrl(bytes, 1, 10));
    }

    private static DataUrl dataUrl(String value) {
        byte[] bytes = ("\"" + value + "\"").getBytes(StandardCharsets.UTF_8);
        return InlineFileLocations.parseDataUrl(bytes, 1, bytes.length - 1);
    }

    private static List<String> path(String pointer) {
        return List.of(pointer.substring(1).split("/"));
    }
}
