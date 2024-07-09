package com.epam.aidial.core.util;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EtagHeaderTest {
    @Test
    void testEtag() {
        EtagHeader etag = EtagHeader.fromHeader("123");
        etag.validate("123");
    }

    @Test
    void testEtagWithQuotes() {
        EtagHeader etag = EtagHeader.fromHeader("\"123\"");
        etag.validate("123");
    }

    @Test
    void testEtagList() {
        EtagHeader etag = EtagHeader.fromHeader("\"123\",\"234\"");
        etag.validate("123");
        etag.validate("234");
    }

    @Test
    void testEtagAny() {
        EtagHeader etag = EtagHeader.fromHeader("*");
        etag.validate("any");
    }

    @Test
    void testMissingEtag() {
        EtagHeader etag = EtagHeader.fromHeader(null);
        etag.validate("any");
    }

    @Test
    void testEtagMismatch() {
        EtagHeader etag = EtagHeader.fromHeader("123");
        assertThrows(HttpException.class, () -> etag.validate("234"));
    }
}