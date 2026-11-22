package com.epam.aidial.core.credentials.util;

import lombok.experimental.UtilityClass;

/**
 * The one wording of the {@code 400} for a write that would have kept a stored secret that no longer decrypts,
 * shared by every path that refuses one.
 */
@UtilityClass
public class UnreadableSecretMessage {

    /**
     * @param resource what the secret belongs to, such as a toolset url, an external service id or an entity name
     * @param fields   the field names the client has to send again, comma-separated
     */
    public static String of(String resource, String fields) {
        return "Secret of %s can't be decrypted; send a new value for: %s".formatted(resource, fields);
    }
}
