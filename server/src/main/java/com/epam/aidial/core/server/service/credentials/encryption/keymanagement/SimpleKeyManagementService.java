package com.epam.aidial.core.server.service.credentials.encryption.keymanagement;

public class SimpleKeyManagementService implements KeyManagementService {

    @Override
    public byte[] encode(byte[] plain) {
        return plain;
    }

    @Override
    public byte[] decode(byte[] encrypted) {
        return encrypted;
    }

}
