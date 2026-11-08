package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportArchive.StreamSource;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Builds the nodes of a closure for tests, as the plan does: the body is redacted when the node is made, and the node keeps the redacted
 * bytes and their digest. An instance makes its nodes with one redactor.
 */
public final class ExportTestNodes {

    /** Writes the stored bytes unchanged. */
    public static final BodyRedactor KEEPS_THE_BODY = (identity, body) -> body.getBytes(StandardCharsets.UTF_8);

    /** A source for a test that only builds the registry and never writes the bytes of a File or a Skill. */
    public static final StreamSource NO_CONTENT = new StreamSource() { };

    private final BodyRedactor redactor;

    public ExportTestNodes(BodyRedactor redactor) {
        this.redactor = redactor;
    }

    /** A node that was read while no redaction policy was configured: it has an identity and no bytes, so no archive can be prepared. */
    public static ClosureNode nodeWithoutBytes(ResourceDescriptor descriptor, boolean isRoot, String body, @Nullable ExportDiscovery discovery) {
        return node(null, descriptor, isRoot, body, discovery);
    }

    public static ClosureNode node(ResourceDescriptor descriptor, boolean isRoot, String body, @Nullable ExportDiscovery discovery) {
        return node(KEEPS_THE_BODY, descriptor, isRoot, body, discovery);
    }

    public static ClosureNode node(@Nullable BodyRedactor redactor, ResourceDescriptor descriptor, boolean isRoot, String body,
                                   @Nullable ExportDiscovery discovery) {
        return new ClosureNode(descriptor, isRoot, ExportNodeContent.of(redactor, descriptor, body, null, null), discovery);
    }

    /** A node made with the redactor of this instance. */
    public ClosureNode add(ResourceDescriptor descriptor, boolean isRoot, String body, @Nullable ExportDiscovery discovery) {
        return node(redactor, descriptor, isRoot, body, discovery);
    }
}
