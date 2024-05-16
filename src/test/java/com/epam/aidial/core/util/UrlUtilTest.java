package com.epam.aidial.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UrlUtilTest {

    @Test
    public void testPathEncoding() {
        assertEquals("folder%201", UrlUtil.encodePath("folder 1"));
        assertEquals("folder%20%23", UrlUtil.encodePath("folder #"));
        assertEquals("%D1%84%D0%B0%D0%B9%D0%BB.txt", UrlUtil.encodePath("файл.txt"));
        assertEquals("fo$l+=d,e%23r%201", UrlUtil.encodePath("fo$l+=d,e#r 1"));
        assertEquals("%5BPlayback%5D", UrlUtil.encodePath("[Playback]"));
        assertEquals("%E2%98%BB%E2%98%BB%E2%98%B9%CF%A1%E2%8D%A3%EF%BD%BC", UrlUtil.encodePath("☻☻☹ϡ⍣ｼ"));
        assertEquals("gpt-35-turbo__(%60~!@%23$%5E*-_+%5B%5D'%7C%3C%3E.%3F%22)", UrlUtil.encodePath("gpt-35-turbo__(`~!@#$^*-_+[]'|<>.?\")"));
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
}
