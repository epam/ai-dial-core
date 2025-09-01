package com.epam.aidial.core.credentials.service.encryption.keymanagement;

public interface KeyManagementService {

    byte[] encode(byte[] plain);

    byte[] decode(byte[] encrypted);

}
