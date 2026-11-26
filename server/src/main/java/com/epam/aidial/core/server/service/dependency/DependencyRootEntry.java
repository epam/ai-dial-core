package com.epam.aidial.core.server.service.dependency;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * One root of the answer: its identity and the flat list of the resources it reaches.
 *
 * @param root         the identity of the root, written as the fields {@code type}, {@code url}, {@code name} and {@code origin}
 * @param dependencies everything the root reaches, each once, breadth first, never the root itself
 */
public record DependencyRootEntry(@JsonUnwrapped DependencyResource root, List<DependencyResource> dependencies) {
}
