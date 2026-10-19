package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceOperationControllerTest {

    @Test
    void subscribableTypesArePublishedOnResourceTopic() {
        assertTrue(ResourceTypes.EVENT_TYPES.containsAll(ResourceOperationController.SUBSCRIPTION_ALLOWED_TYPES));
    }
}
