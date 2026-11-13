package com.epam.aidial.core.storage.util;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisUtilTest {

    @Test
    void testRedisKeyIsTypeNameAndStoragePath() {
        assertEquals("role:platform/roles/r1", RedisUtil.redisKey(ResourceTypes.ROLE, "platform/roles/r1", null));
        assertEquals("role:my-prefix/platform/roles/r1", RedisUtil.redisKey(ResourceTypes.ROLE, "platform/roles/r1", "my-prefix"));
    }

    @Test
    void testRedisKeyDoesNotDependOnTheDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals("invitation:public/invitations/i1", RedisUtil.redisKey(ResourceTypes.INVITATION, "public/invitations/i1", null));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}
