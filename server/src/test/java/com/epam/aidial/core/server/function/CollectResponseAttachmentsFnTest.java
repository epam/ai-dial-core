package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectResponseAttachmentsFnTest {

    @Test
    void testGrantLeavesTheMapsReadersHoldUnchanged() {
        ApiKeyData key = new ApiKeyData();
        key.getAttachedFiles().put("files/b/old.txt", new AutoSharedData(ResourceAccessType.READ_ONLY));
        Map<String, AutoSharedData> files = key.getAttachedFiles();
        Map<String, AutoSharedData> folders = key.getAttachedFolders();
        Iterator<Map.Entry<String, AutoSharedData>> reading = files.entrySet().iterator();

        CollectResponseAttachmentsFn.addAttachments(key, Map.of(
                "files/b/new.txt", ResourceAccessType.READ_ONLY, "files/b/dir/", ResourceAccessType.READ_ONLY));

        // the reader's iteration goes on over the maps as they were
        assertEquals("files/b/old.txt", reading.next().getKey());
        assertEquals(1, files.size());
        assertTrue(folders.isEmpty());
        assertNotSame(files, key.getAttachedFiles());
        assertEquals(2, key.getAttachedFiles().size());
        assertTrue(key.getAttachedFolders().containsKey("files/b/dir/"));
    }
}
