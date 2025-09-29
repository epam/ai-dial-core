package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceUtilTest {

    @Test
    void getRootLocation_withShortPath() {
        String location = "Users/user/";

        String result = ResourceUtil.getRootLocation(location);

        assertEquals(location, result);
    }

    @Test
    void getRootLocation_withOneElement() {
        String location = "public/";

        String result = ResourceUtil.getRootLocation(location);

        assertEquals(location, result);
    }

    @Test
    void getRootLocation_withDeeplyNestedPath() {
        String location = "Users/user/publication-id/sub-folder/";
        String expectedRootBucket = "Users/user/";

        String result = ResourceUtil.getRootLocation(location);

        assertEquals(expectedRootBucket, result);
    }

}