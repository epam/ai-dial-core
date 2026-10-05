package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock
    private ResourceService resourceService;

    @Mock
    private EncryptionService encryptionService;

    @Test
    void getDefaultTtlInHours_whenTtlInSecondsIsNotConfigured_shouldReturnSeventyTwoHours() {
        InvitationService invitationService = new InvitationService(resourceService, encryptionService, new JsonObject());

        assertEquals(72, invitationService.getDefaultTtlInHours());
    }

    @Test
    void getDefaultTtlInHours_whenTtlInSecondsIsExactMultipleOfHour_shouldReturnExactHours() {
        JsonObject settings = new JsonObject().put("ttlInSeconds", 14 * 24 * 3600);

        InvitationService invitationService = new InvitationService(resourceService, encryptionService, settings);

        assertEquals(14 * 24, invitationService.getDefaultTtlInHours());
    }

    @Test
    void getDefaultTtlInHours_whenTtlInSecondsIsNotExactMultipleOfHour_shouldRoundUp() {
        JsonObject settings = new JsonObject().put("ttlInSeconds", 3601);

        InvitationService invitationService = new InvitationService(resourceService, encryptionService, settings);

        assertEquals(2, invitationService.getDefaultTtlInHours());
    }

    @Test
    void constructor_whenTtlInSecondsIsZero_shouldThrow() {
        JsonObject settings = new JsonObject().put("ttlInSeconds", 0);

        assertThrows(IllegalArgumentException.class,
                () -> new InvitationService(resourceService, encryptionService, settings));
    }

    @Test
    void constructor_whenTtlInSecondsIsNegative_shouldThrow() {
        JsonObject settings = new JsonObject().put("ttlInSeconds", -1);

        assertThrows(IllegalArgumentException.class,
                () -> new InvitationService(resourceService, encryptionService, settings));
    }
}
