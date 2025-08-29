package com.epam.aidial.core.server.service.credentials.encryption.keymanagement;

public interface KeyManagementService {

    byte[] encode(byte[] plain);

    byte[] decode(byte[] encrypted);

}
