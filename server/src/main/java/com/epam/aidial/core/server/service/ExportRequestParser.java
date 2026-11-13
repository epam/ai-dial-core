package com.epam.aidial.core.server.service;

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
 * Reads and validates the body of an Application export request. Every problem is a 400 whose message names the
 * field or the index of the root and never the submitted value, because a value can be a credential.
 */
public final class ExportRequestParser {

    /**
     * @param roots Application descriptors in {@code public/} or {@code platform/}, in the order submitted
     */
    public record ExportRequest(List<ResourceDescriptor> roots) {
    }

    /** The body as submitted; {@link #parse} checks every field. Any field but {@code roots} is refused. */
    public record ExportRequestBody(@Nullable List<ExportRoot> roots) {
    }

    /**
     * @param type {@code application}, the only accepted value
     */
    public record ExportRoot(@Nullable String type, @Nullable String url) {
    }

    private ExportRequestParser() {
    }

    public static ExportRequest parse(String body) {
        ExportRequestBody raw;
        try {
            raw = ProxyUtil.MAPPER.readValue(body, ExportRequestBody.class);
        } catch (JsonProcessingException e) {
            throw newBadRequestException("The request body is not valid for this endpoint");
        }
        if (raw == null || raw.roots() == null || raw.roots().isEmpty()) {
            throw newBadRequestException("roots must hold at least one Application");
        }

        List<ResourceDescriptor> roots = new ArrayList<>();
        for (int i = 0; i < raw.roots().size(); i++) {
            roots.add(toRoot(i, raw.roots().get(i)));
        }
        return new ExportRequest(List.copyOf(roots));
    }

    private static ResourceDescriptor toRoot(int index, @Nullable ExportRoot root) {
        String message = "roots[" + index + "] must be an Application in the public or platform bucket";
        if (root == null || root.type() == null || root.url() == null) {
            throw newBadRequestException(message);
        }
        ResourceDescriptor descriptor;
        try {
            descriptor = ResourceDescriptorFactory.fromAnyUrl(root.url(), null);
        } catch (RuntimeException e) {
            throw newBadRequestException(message);
        }
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        if (!root.type().equals(ExportResourceNames.resolveTypeName(type)) || !ExportTypePolicy.isRoot(type) || descriptor.isFolder()
                || !ExportTypePolicy.isSupportedDependency(type, descriptor.getBucketLocation())) {
            throw newBadRequestException(message);
        }
        return descriptor;
    }

    private static HttpException newBadRequestException(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST, message);
    }
}
