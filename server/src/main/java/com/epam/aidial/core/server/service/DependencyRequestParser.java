package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Reads and validates the body of a dependency list request. Every problem is a 400 whose message names the
 * field or the index of the root and never the submitted value, because a value can be a credential.
 */
public final class DependencyRequestParser {

    /**
     * @param roots Application descriptors, in the order submitted; the location of a root is for the access policy
     */
    public record DependencyRequest(List<ResourceDescriptor> roots) {
    }

    /** The body as submitted; {@link #parse} checks every field. Any field but {@code roots} is refused. */
    public record DependencyRequestBody(@Nullable List<DependencyRoot> roots) {
    }

    /**
     * @param type {@code application}, the only accepted value
     */
    public record DependencyRoot(@Nullable String type, @Nullable String url) {
    }

    private DependencyRequestParser() {
    }

    /**
     * @param encryptionService decrypts the bucket of a URL in a user bucket; a root in any bucket is parsed, and the access policy decides
     *                          whether the caller may use it
     */
    public static DependencyRequest parse(String body, EncryptionService encryptionService) {
        DependencyRequestBody raw;
        try {
            raw = ProxyUtil.MAPPER.readValue(body, DependencyRequestBody.class);
        } catch (JsonProcessingException e) {
            throw newBadRequestException("The request body is not valid for this endpoint");
        }
        if (raw == null || raw.roots() == null || raw.roots().isEmpty()) {
            throw newBadRequestException("roots must hold at least one Application");
        }

        List<ResourceDescriptor> roots = new ArrayList<>();
        for (int i = 0; i < raw.roots().size(); i++) {
            roots.add(toRoot(i, raw.roots().get(i), encryptionService));
        }
        return new DependencyRequest(List.copyOf(roots));
    }

    private static ResourceDescriptor toRoot(int index, @Nullable DependencyRoot root, EncryptionService encryptionService) {
        String message = "roots[" + index + "] must be an Application";
        if (root == null || root.type() == null || root.url() == null) {
            throw newBadRequestException(message);
        }
        ResourceDescriptor descriptor;
        try {
            descriptor = ResourceDescriptorFactory.fromAnyUrl(root.url(), encryptionService);
        } catch (RuntimeException e) {
            throw newBadRequestException(message);
        }
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        if (!root.type().equals(DependencyResourceNames.resolveTypeName(type)) || !DependencyTypePolicy.isRoot(type) || descriptor.isFolder()) {
            throw newBadRequestException(message);
        }
        return descriptor;
    }

    private static HttpException newBadRequestException(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST, message);
    }
}
