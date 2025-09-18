package com.epam.aidial.core.credentials.keymanagement;

public interface KeyManagementService {

    byte[] encrypt(byte[] plain);

    byte[] decrypt(byte[] encrypted);

}
