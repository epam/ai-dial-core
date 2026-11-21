package com.epam.aidial.core.credentials.keymanagement;

import com.epam.aidial.core.credentials.data.configuration.KmsSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyManagementServiceFactoryTest {

    @Test
    void testNoProviderOrUnencryptedGivesPlainService() {
        assertInstanceOf(SimpleKeyManagementService.class, KeyManagementServiceFactory.create(null, null));
        assertInstanceOf(SimpleKeyManagementService.class, KeyManagementServiceFactory.create(KmsSettings.builder().build(), null));
        assertInstanceOf(SimpleKeyManagementService.class,
                KeyManagementServiceFactory.create(KmsSettings.builder().provider("unencrypted").build(), null));
    }

    @Test
    void testUnsupportedProviderFailsInsteadOfFallingBackToPlainService() {
        assertThrows(IllegalArgumentException.class,
                () -> KeyManagementServiceFactory.create(KmsSettings.builder().provider("foo").build(), null));
    }
}
