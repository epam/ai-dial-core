package com.epam.aidial.core.server.service;

import javax.annotation.Nullable;

/**
 * One resource in the answer: a dependency in a {@link DependencyRootEntry#dependencies() list}, or the identity of a root.
 *
 * @param type   the name of the type, for example {@code application} or {@code application-type-schema}
 * @param url    the URL of the resource; {@code null} when the caller has none to use, as for an entity of the file configuration
 * @param name   the last segment of the path
 * @param origin {@code public}, {@code platform}, {@code file} or {@code private}
 */
public record DependencyResource(String type, @Nullable String url, String name, String origin) {
}
