package com.epam.aidial.core.credentials.service.encryption.keymanagement;

public interface KeyManagementService {

    byte[] encrypt(byte[] plain);

    byte[] decrypt(byte[] encrypted);

}
