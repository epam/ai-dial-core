package com.epam.aidial.core.credentials.keymanagement;

public class SimpleKeyManagementService implements KeyManagementService {

    @Override
    public byte[] encrypt(byte[] plain) {
        return plain;
    }

    @Override
    public byte[] decrypt(byte[] encrypted) {
        return encrypted;
    }

}
