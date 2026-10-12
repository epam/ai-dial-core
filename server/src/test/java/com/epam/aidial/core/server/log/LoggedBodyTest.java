package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.log.LoggedBody.LoggedFile;
import com.epam.aidial.core.server.log.LoggedBody.Status;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoggedBodyTest {

    private static final InterfaceType CHAT = InterfaceType.OPENAI_CHAT_COMPLETIONS;
    private static final String BODY = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"first"},\
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}},\
            {"type":"input_audio","input_audio":{"data":"BBBBBBBB","format":"wav"}},{"type":"text","text":"last"}]}]}""";

    @Test
    public void testDefaultsDoNotScan() {
        Buffer body = Buffer.buffer(BODY);

        LoggedBody logged = LoggedBody.prepare(body, CHAT, settings(new JsonObject()));

        assertSame(body, logged.bytes());
        assertFalse(logged.truncated());
        assertTrue(logged.files().isEmpty());
    }

    @Test
    public void testRemovedPayloadsKeepTheDataUrlPrefix() {
        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFiles", false)));

        String expected = BODY.replace("base64,AAAA", "base64,").replace("\"BBBBBBBB\"", "\"\"");
        assertEquals(expected, logged.bytes().toString(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> ProxyUtil.MAPPER.readTree(logged.bytes().toString(StandardCharsets.UTF_8)));
        assertEquals(List.of(Status.EXCLUDED, Status.EXCLUDED), logged.files().stream().map(LoggedFile::status).toList());
        assertEquals(4, logged.files().get(0).size());
        assertEquals(8, logged.files().get(1).size());
        assertNull(logged.files().get(0).offset());
        assertNull(logged.files().get(0).length());
    }

    @Test
    public void testIndexedFilesPointIntoTheLoggedBody() {
        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)));

        assertEquals(BODY, logged.bytes().toString(StandardCharsets.UTF_8));
        assertEquals(List.of("AAAA", "BBBBBBBB"), logged.files().stream().map(file -> slice(logged, file)).toList());
        assertTrue(logged.files().stream().allMatch(file -> file.status() == Status.INCLUDED));
        assertEquals(8, logged.files().get(1).size());
    }

    @Test
    public void testFileCrossingTheCutIsPartial() {
        int cut = BODY.indexOf("BBBBBBBB") + 3;

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)), cut);

        assertTrue(logged.truncated());
        assertEquals(BODY.substring(0, cut), logged.bytes().toString(StandardCharsets.UTF_8));
        LoggedFile audio = logged.files().get(1);
        assertEquals(Status.PARTIAL, audio.status());
        assertEquals("BBB", slice(logged, audio));
        assertNull(audio.size());
    }

    @Test
    public void testFileCrossingTheCutIsCutAway() {
        int cut = BODY.indexOf("BBBBBBBB") + 3;

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectTruncatedInlineFiles", false)), cut);

        assertTrue(logged.truncated());
        assertEquals(BODY.substring(0, BODY.indexOf("\"BBBBBBBB")), logged.bytes().toString(StandardCharsets.UTF_8));
        assertEquals(List.of(Status.INCLUDED, Status.EXCLUDED), logged.files().stream().map(LoggedFile::status).toList());
        assertNull(logged.files().get(1).size());
    }

    @Test
    public void testCollectTruncatedInlineFilesAloneDoesNotScanBodyWithinTheLimit() {
        Buffer body = Buffer.buffer(BODY);

        LoggedBody logged = LoggedBody.prepare(body, CHAT, settings(new JsonObject().put("collectTruncatedInlineFiles", false)));

        assertSame(body, logged.bytes());
        assertTrue(logged.files().isEmpty());
    }

    @Test
    public void testRemovalNeverLetsMoreOfTheBodyIn() {
        // like a 40 MB request: removing files does not move the cut, and nothing after it is read
        int cut = BODY.length();
        byte[] bytes = Arrays.copyOf(BODY.getBytes(StandardCharsets.UTF_8), cut + 1000);
        Arrays.fill(bytes, cut, bytes.length, (byte) 0xFF);

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(bytes), CHAT, settings(new JsonObject().put("collectInlineFiles", false)), cut);

        assertTrue(logged.truncated());
        assertEquals(BODY.replace("base64,AAAA", "base64,").replace("\"BBBBBBBB\"", "\"\""), logged.bytes().toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testInvalidUtf8DropsOffsets() {
        // the malformed byte follows the file: Jackson rejects malformed UTF-8 even in strings it skips, so the
        // parse stops there, and a file after it would not be found at all and would be logged as it is
        String prefix = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"BBBB\",\"format\":\"wav\"}},"
                + "{\"type\":\"text\",\"text\":\"";
        String suffix = "\"}]}]}";
        Buffer body = Buffer.buffer(prefix).appendByte((byte) 0xC3).appendString(suffix);

        LoggedBody logged = LoggedBody.prepare(body, CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)));

        LoggedFile audio = logged.files().get(0);
        assertEquals(Status.INCLUDED, audio.status());
        assertNull(audio.offset());
        assertNull(audio.length());
        assertEquals(4, audio.size());
    }

    @Test
    public void testBodyExactlyAtTheLimitIsNotCut() {
        int limit = BODY.length();

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)), limit);

        assertFalse(logged.truncated());
        assertTrue(logged.files().stream().allMatch(file -> file.status() == Status.INCLUDED));
    }

    @Test
    public void testNullBodyAndUnknownInterface() {
        AnalyticsSettings all = settings(new JsonObject().put("collectInlineFiles", false).put("collectInlineFileIndex", true));

        assertNull(LoggedBody.prepare(null, CHAT, all));
        assertTrue(LoggedBody.prepare(Buffer.buffer(BODY), null, all).files().isEmpty());
    }

    private static String slice(LoggedBody logged, LoggedFile file) {
        return logged.bytes().getString(file.offset(), file.offset() + file.length(), "UTF-8");
    }

    private static AnalyticsSettings settings(JsonObject analytics) {
        return AnalyticsSettings.from(analytics);
    }
}
