package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InlineFileLocatorTest {

    private static final InterfaceType CHAT = InterfaceType.OPENAI_CHAT_COMPLETIONS;

    @Test
    public void testJacksonTokenOffsetsAreRelativeToTheParsedRange() throws IOException {
        // pins the assumption InlineFileLocator relies on: "b" sits at index 7 of the array and 5 of the range
        byte[] bytes = "xx{\"a\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        try (JsonParser parser = new JsonFactory().createParser(bytes, 2, bytes.length - 2)) {
            parser.nextToken();
            parser.nextToken();
            parser.nextToken();

            assertEquals(5, parser.currentTokenLocation().getByteOffset());
        }
    }

    @Test
    public void testImageDataUrl() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"text","text":"hi"},\
                {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0K"}}]}]}""";

        InlineFile file = single(CHAT, body);

        assertEquals("/messages/0/content/1/image_url/url", file.path());
        assertTrue(file.dataUrl());
        assertEquals("image/png", file.mime());
        assertEquals("iVBORw0K", payload(body, file));
        assertEquals('"', body.getBytes(StandardCharsets.UTF_8)[file.valueStart()]);
        assertNull(file.chunk());
    }

    @Test
    public void testHttpImageUrlIsNotFile() {
        assertTrue(locate(CHAT, """
                {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}]}""").isEmpty());
    }

    @Test
    public void testMimeSiblingAfterTheFileField() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"UklGRg==","format":"wav"}}]}]}""";

        InlineFile file = single(CHAT, body);

        assertEquals("audio/wav", file.mime());
        assertFalse(file.dataUrl());
        assertEquals("UklGRg==", payload(body, file));
    }

    @Test
    public void testDialAttachmentsNeedBinaryType() {
        String body = """
                {"messages":[{"role":"user","content":"x","custom_content":{"attachments":[\
                {"type":"text/markdown","data":"# plain text"},{"data":"iVBORw0K","type":"image/png"},{"data":"QUJD"}]}}]}""";

        InlineFile file = single(CHAT, body);

        assertEquals("/messages/0/custom_content/attachments/1/data", file.path());
        assertEquals("image/png", file.mime());
    }

    @Test
    public void testAnthropicSourcesMustBeBase64() {
        String body = """
                {"messages":[{"role":"user","content":[\
                {"type":"document","source":{"type":"text","media_type":"text/plain","data":"secret prompt"}},\
                {"type":"image","source":{"data":"iVBORw0K","media_type":"image/png","type":"base64"}},\
                {"type":"tool_result","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"/9j/"}}]}]}]}""";

        List<InlineFile> files = locate(InterfaceType.ANTHROPIC_MESSAGES, body);

        assertEquals(List.of("/messages/0/content/1/source/data", "/messages/0/content/2/content/0/source/data"),
                files.stream().map(InlineFile::path).toList());
        assertEquals("image/png", files.get(0).mime());
        assertEquals("/9j/", payload(body, files.get(1)));
    }

    @Test
    public void testFileLikeTextOutsideFileLocationsIsNotFile() {
        assertTrue(locate(CHAT, """
                {"tools":[{"type":"function","function":{"name":"f","parameters":{"data":"QUJD"}}}],\
                "messages":[{"role":"user","content":"data:image/png;base64,iVBORw0K"},\
                {"role":"user","content":"QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo="}],"extra":{"data":"QUJD"}}""").isEmpty());
    }

    @Test
    public void testLocationsOfAnotherInterfaceAreNotFiles() {
        String body = """
                {"input":[{"type":"image_generation_call","result":"iVBORw0K"}]}""";

        assertTrue(locate(CHAT, body).isEmpty());
        assertTrue(locate(InterfaceType.OPENAI_EMBEDDINGS, body).isEmpty());
        assertEquals("/input/0/result", single(InterfaceType.OPENAI_RESPONSES, body).path());
    }

    @Test
    public void testByteOffsetsAfterEscapesAndMultiByteText() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"text","text":"Привіт \\"quoted\\" \\\\ done"},\
                {"type":"image_url","image_url":{"url":"data:image\\/png;base64,iV\\/BOR"}}]}]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        InlineFile file = single(CHAT, body);

        assertEquals('"', bytes[file.valueStart()]);
        assertEquals('"', bytes[file.payloadEnd()]);
        assertEquals("iV\\/BOR", payload(body, file));
        assertEquals("image/png", file.mime());
    }

    @Test
    public void testFileCrossingTheCut() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"format":"wav","data":"UklGRgAAAAAAAAAA"}}]}]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int cut = body.indexOf("UklGRg") + 3;

        List<InlineFile> files = InlineFileLocator.locate(bytes, cut, InlineFileLocations.forInterface(CHAT));

        assertEquals(1, files.size());
        assertTrue(files.get(0).crossesCut());
        assertEquals(body.indexOf("UklGRg"), files.get(0).payloadStart());
        assertEquals("audio/wav", files.get(0).mime());
    }

    @Test
    public void testConditionSiblingAfterTheCutIsNeverSeen() {
        String body = """
                {"messages":[{"role":"user","content":"x","custom_content":{"attachments":[{"data":"iVBORw0KGgoAAAA","type":"image/png"}]}}]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        assertTrue(InlineFileLocator.locate(bytes, body.indexOf("GgoA"), InlineFileLocations.forInterface(CHAT)).isEmpty());
    }

    @Test
    public void testNothingAfterTheLimitIsRead() {
        String prefix = """
                {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"format":"wav","data":"UklGRg=="}}""";
        byte[] bytes = Arrays.copyOf(prefix.getBytes(StandardCharsets.UTF_8), prefix.length() + 64);
        // invalid JSON and invalid UTF-8 after the limit
        Arrays.fill(bytes, prefix.length(), bytes.length, (byte) 0xFF);

        List<InlineFile> files = InlineFileLocator.locate(bytes, prefix.length(), InlineFileLocations.forInterface(CHAT));

        assertEquals(1, files.size());
        assertFalse(files.get(0).crossesCut());
    }

    @Test
    public void testMalformedBodyKeepsTheFilesBeforeTheError() {
        assertEquals(1, locate(CHAT, """
                {"messages":[{"content":[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}], oops""").size());
        assertTrue(locate(CHAT, "hello").isEmpty());
        assertTrue(locate(CHAT, "").isEmpty());
    }

    @Test
    public void testJsonBodyWithByteOrderMark() {
        String body = "﻿" + """
                {"messages":[{"content":[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}]}""";

        assertEquals("QUJD", payload(body, single(CHAT, body)));
    }

    static List<InlineFile> locate(InterfaceType type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return InlineFileLocator.locate(bytes, bytes.length, InlineFileLocations.forInterface(type));
    }

    static InlineFile single(InterfaceType type, String body) {
        List<InlineFile> files = locate(type, body);
        assertEquals(1, files.size(), "files: " + files);
        return files.get(0);
    }

    static String payload(String body, InlineFile file) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, file.payloadStart(), file.payloadEnd() - file.payloadStart(), StandardCharsets.UTF_8);
    }
}
