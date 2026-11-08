package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
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

    /**
     * The body as submitted; {@link #parse} checks every field. It has no field but {@code roots}, so any other field is refused.
     *
     * @param roots the selected Applications; at least one
     */
    public record ExportRequestBody(@Nullable List<ExportRoot> roots) {
    }

    /**
     * @param type {@code application}, the only accepted value
     * @param url  the resource URL of the Application in the {@code public} or {@code platform} bucket
     */
    public record ExportRoot(@Nullable String type, @Nullable String url) {
    }

    private ExportRequestParser() {
    }

    /**
     * @param maxRoots the most Applications that the request may name; more is refused before any of them is looked at
     */
    public static ExportRequest parse(String body, int maxRoots) {
        ExportRequestBody raw;
        try {
            raw = ProxyUtil.MAPPER.readValue(body, ExportRequestBody.class);
        } catch (JsonProcessingException e) {
            throw badRequest("The request body is not valid for this endpoint");
        }
        if (raw == null || raw.roots() == null || raw.roots().isEmpty()) {
            throw badRequest("roots must hold at least one Application");
        }
        if (raw.roots().size() > maxRoots) {
            throw badRequest("roots accepts at most " + maxRoots + " Applications");
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
            throw badRequest(message);
        }
        ResourceDescriptor descriptor;
        try {
            descriptor = ResourceDescriptorFactory.fromAnyUrl(root.url(), null);
        } catch (RuntimeException e) {
            throw badRequest(message);
        }
        boolean isEligibleLocation = ResourceDescriptor.PUBLIC_LOCATION.equals(descriptor.getBucketLocation())
                || ResourceDescriptor.PLATFORM_LOCATION.equals(descriptor.getBucketLocation());
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        if (!root.type().equals(ExportResourceIdentity.typeName(type)) || !ExportTypePolicy.isRoot(type) || descriptor.isFolder()
                || !isEligibleLocation || !ExportTypePolicy.isBound(type, descriptor.getBucketName())) {
            throw badRequest(message);
        }
        return descriptor;
    }

    private static HttpException badRequest(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST, message);
    }
}
