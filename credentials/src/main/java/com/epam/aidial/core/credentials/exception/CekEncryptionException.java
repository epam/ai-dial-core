package com.epam.aidial.core.credentials.exception;

public class CekEncryptionException extends EncryptionException {

    public CekEncryptionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CekEncryptionException(String message) {
        super(message);
    }
}
