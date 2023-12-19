package com.epam.aidial.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UrlUtilTest {

    @Test
    public void testPathEncoding() {
        assertEquals("folder%201", UrlUtil.encodePath("folder 1"));
        assertEquals("folder%20%23", UrlUtil.encodePath("folder #"));
        assertEquals("fo$l+=d,e%23r%201", UrlUtil.encodePath("fo$l+=d,e#r 1"));
    }

    @Test
    public void testPathDecoding() {
        assertEquals("folder 1", UrlUtil.decodePath("folder%201"));
        assertEquals("folder #", UrlUtil.decodePath("folder%20%23"));
        assertEquals("fo$l+=d,e#r 1", UrlUtil.decodePath("fo$l+=d,e%23r%201"));
        assertEquals("fo$l+=d,e#r 1", UrlUtil.decodePath("fo%24l%2B%3Dd%2Ce%23r%201"));
    }
}
