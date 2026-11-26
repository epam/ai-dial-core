package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Set;

/**
 * One recipient of a resource: {@code user} is their display name, absent when they have none, {@code bucket} their encrypted bucket,
 * and {@code permissions} what the shares of the resource and of the folders above it grant them together.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceShare(String user, String bucket, Set<ResourceAccessType> permissions) {
}
