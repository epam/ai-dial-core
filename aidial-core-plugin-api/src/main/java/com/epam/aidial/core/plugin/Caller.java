package com.epam.aidial.core.plugin;

import java.util.List;

/**
 * Who sent the request a plugin answers.
 *
 * @param userId  the user id of a caller with a JWT, null for a caller with an API key
 * @param roles   the caller's roles
 * @param project the project of the caller's API key, or the project claim of its JWT; null when there is none
 */
public record Caller(String userId, List<String> roles, String project) {
}
