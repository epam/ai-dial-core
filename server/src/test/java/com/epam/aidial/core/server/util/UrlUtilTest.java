package com.epam.aidial.core.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UrlUtilTest {

    @Test
    public void testPathSegmentEncoding() {
        assertEquals("folder%201", UrlUtil.encodePathSegment("folder 1"));
        assertEquals("folder%20%23", UrlUtil.encodePathSegment("folder #"));
        assertEquals("%D1%84%D0%B0%D0%B9%D0%BB.txt", UrlUtil.encodePathSegment("файл.txt"));
        assertEquals("fo$l+=d,e%23r%201", UrlUtil.encodePathSegment("fo$l+=d,e#r 1"));
        assertEquals("%5BPlayback%5D", UrlUtil.encodePathSegment("[Playback]"));
        assertEquals("%E2%98%BB%E2%98%BB%E2%98%B9%CF%A1%E2%8D%A3%EF%BD%BC", UrlUtil.encodePathSegment("☻☻☹ϡ⍣ｼ"));
        assertEquals("gpt-35-turbo__(%60~!@%23$%5E*-_+%5B%5D'%7C%3C%3E.%3F%22)", UrlUtil.encodePathSegment("gpt-35-turbo__(`~!@#$^*-_+[]'|<>.?\")"));
    }

    @Test
    public void testPathEncoding() {
        assertEquals("", UrlUtil.encodePath(""));
        assertEquals("/", UrlUtil.encodePath("/"));
        assertEquals("//", UrlUtil.encodePath("//"));
        assertEquals("a/", UrlUtil.encodePath("a/"));
        assertEquals("/b", UrlUtil.encodePath("/b"));
        assertEquals("a/b", UrlUtil.encodePath("a/b"));
        assertEquals("a//b", UrlUtil.encodePath("a//b"));
        assertEquals("a//b/", UrlUtil.encodePath("a//b/"));
        assertEquals("/a//b/", UrlUtil.encodePath("/a//b/"));
        assertEquals("folder%201/folder%202/file%203.txt", UrlUtil.encodePath("folder 1/folder 2/file 3.txt"));
    }

    @Test
    public void testPathDecoding() {
        assertEquals("folder 1", UrlUtil.decodePath("folder%201"));
        assertEquals("folder #", UrlUtil.decodePath("folder%20%23"));
        assertEquals("fo$l+=d,e#r 1", UrlUtil.decodePath("fo$l+=d,e%23r%201"));
        assertEquals("fo$l+=d,e#r 1", UrlUtil.decodePath("fo%24l%2B%3Dd%2Ce%23r%201"));
        assertEquals("файл.txt", UrlUtil.decodePath("%D1%84%D0%B0%D0%B9%D0%BB.txt"));
        assertEquals("echo:1", UrlUtil.decodePath("echo:1"));
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.decodePath("/folder1/file#5.txt"));
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.decodePath("/folder1/q?file=5.txt"));
    }

    @Test
    public void testIsAbsoluteUrl() {
        assertTrue(UrlUtil.isAbsoluteUrl("test://example.com"));
        assertTrue(UrlUtil.isAbsoluteUrl("TEST://EXAMPLE.COM"));
        assertTrue(UrlUtil.isAbsoluteUrl("test1+test2://example.com/item"));
        assertTrue(UrlUtil.isAbsoluteUrl("test1.test2://example.com/item"));
        assertTrue(UrlUtil.isAbsoluteUrl("test1-test2://example.com/item"));
        assertFalse(UrlUtil.isAbsoluteUrl("//example.com/item"));
        assertFalse(UrlUtil.isAbsoluteUrl("/example/file.txt"));
        assertFalse(UrlUtil.isAbsoluteUrl("file"));
    }

    @Test
    public void testIsDataUrl() {
        assertTrue(UrlUtil.isDataUrl("data:whatever"));
        assertTrue(UrlUtil.isDataUrl("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAMAAAADCAIAAADZSiLoAAAAF0lEQVR4nGNkYPjPwMDAwMDAxAADCBYAG10BBdmz9y8AAAAASUVORK5CYII="));
        assertFalse(UrlUtil.isDataUrl("data"));
        assertFalse(UrlUtil.isDataUrl("DATA:whatever"));
        assertFalse(UrlUtil.isDataUrl(" data:whatever"));
        assertFalse(UrlUtil.isDataUrl("data;whatever"));
        assertFalse(UrlUtil.isDataUrl("https://example.com"));
    }
}
