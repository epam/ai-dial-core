package com.epam.aidial.core.server.data.cache;

import com.epam.aidial.core.storage.http.HttpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CachePolicyTest {

    @Test
    public void testFromString() {
        assertEquals(CachePolicy.AVAILABILITY_PRIORITY, CachePolicy.fromString(null));
        assertEquals(CachePolicy.AVAILABILITY_PRIORITY, CachePolicy.fromString("availability-priority"));
        assertEquals(CachePolicy.CACHE_PRIORITY, CachePolicy.fromString("cache-priority"));
        assertThrows(HttpException.class, () -> CachePolicy.fromString("unknown"));
    }
}
