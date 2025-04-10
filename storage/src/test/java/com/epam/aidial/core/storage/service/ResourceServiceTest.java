package com.epam.aidial.core.storage.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ResourceServiceTest {

    @Test
    public void testEncode() {
        assertNull(ResourceService.encode(null));
        assertEquals("", ResourceService.encode(""));
        assertEquals("{abc}", ResourceService.encode("{abc}"));
        assertEquals("base58_24SWdWXor", ResourceService.encode("{abñ}"));
    }

    @Test
    public void testDecode() {
        assertNull(ResourceService.decode(null));
        assertEquals("", ResourceService.decode(""));
        assertEquals("{abc}", ResourceService.decode("{abc}"));
        assertEquals("{abñ}", ResourceService.decode("base58_24SWdWXor"));
    }
}
