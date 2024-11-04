package com.epam.aidial.core.storage.util;


import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtagHeaderTest {
    @Test
    void testEtag() {
        EtagHeader etag = EtagHeader.fromHeader("123", null, "POST");
        etag.validate("123");
    }

    @Test
    void testEtagWithQuotes() {
        EtagHeader etag = EtagHeader.fromHeader("\"123\"", null, "POST");
        etag.validate("123");
    }

    @Test
    void testEtagList() {
        EtagHeader etag = EtagHeader.fromHeader("\"123\",\"234\"", null, "POST");
        etag.validate("123");
        etag.validate("234");
    }

    @Test
    void testEtagAny() {
        EtagHeader etag = EtagHeader.fromHeader(EtagHeader.ANY_TAG, null, "POST");
        etag.validate("any");
    }

    @Test
    void testMissingEtag() {
        EtagHeader etag = EtagHeader.fromHeader(null, null, "POST");
        etag.validate("any");
    }

    @Test
    void testEtagMismatch() {
        EtagHeader etag = EtagHeader.fromHeader("123", null, "POST");
        assertThrows(HttpException.class, () -> etag.validate("234"));
    }

    @Test
    void testNoOverwrite() {
        EtagHeader etag = EtagHeader.fromHeader(null, EtagHeader.ANY_TAG, "POST");
        assertThrows(HttpException.class, () -> etag.validate("123"));
    }

    @Test
    void testIfMatchAndNoneIfMatch() {
        EtagHeader etag = EtagHeader.fromHeader("299,123", "235,326", "POST");
        etag.validate("123");
    }

    @Test
    void testIfMatchAnyAndNoneIfMatch() {
        EtagHeader etag = EtagHeader.fromHeader("*", "235,326", "POST");
        etag.validate("123");
    }

    @Test
    void testNoneIfMatchFail_ModifiedMethod() {
        EtagHeader etag = EtagHeader.fromHeader(null, "235,326", "POST");
        HttpException error = assertThrows(HttpException.class, () -> etag.validate("326"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, error.getStatus());
    }

    @Test
    void testNoneIfMatchFail_ReadMethod() {
        EtagHeader etag = EtagHeader.fromHeader(null, "235,326", "GET");
        HttpException error = assertThrows(HttpException.class, () -> etag.validate("326"));
        assertEquals(HttpStatus.NOT_MODIFIED, error.getStatus());
    }

    @Test
    void testNoneIfMatchAnyFail_ModifiedMethod() {
        EtagHeader etag = EtagHeader.fromHeader(null, "*", "POST");
        assertThrows(HttpException.class, () -> etag.validate("326"));
    }

    @Test
    void testNoneIfMatchAnyFail_ReadMethod() {
        EtagHeader etag = EtagHeader.fromHeader(null, "*", "READ");
        assertThrows(HttpException.class, () -> etag.validate("326"));
    }

    @Test
    void testNoneIfMatchPass() {
        EtagHeader etag = EtagHeader.fromHeader(null, "235,326", "POST");
        etag.validate("123");
    }

}