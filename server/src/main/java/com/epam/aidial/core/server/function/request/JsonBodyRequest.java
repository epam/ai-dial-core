package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ChatUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A request backed by a JSON object body. It forwards the {@link ReceivedBody received} bytes, with only the model
 * value replaced, until the tree changes in another way; only then is the tree serialized. So the tree is changed only
 * through the methods of this class, and a subclass that changes the {@link #tree()} calls {@link #treeChanged()}.
 */
abstract class JsonBodyRequest implements RequestObject {
    private static final String MODEL_NODE = "model";

    private final ObjectNode tree;
    // null once the tree holds a change the received bytes lack
    @Nullable
    private ReceivedBody received;
    @Nullable
    private String modelOverride;

    protected JsonBodyRequest(ReceivedBody body) {
        this.tree = body.tree();
        this.received = body.bytes() == null ? null : body;
    }

    /**
     * The body; it is changed only through this class, see above.
     */
    protected ObjectNode tree() {
        return tree;
    }

    protected void treeChanged() {
        received = null;
    }

    @Override
    public String getModel() {
        return tree.path(MODEL_NODE).asText();
    }

    @Override
    public final void setModel(String model) {
        tree.put(MODEL_NODE, model);
        modelOverride = model;
        if (received != null && !received.hasModel()) {
            treeChanged();
        }
    }

    @Override
    public boolean isStreaming() {
        return tree.path("stream").asBoolean(false);
    }

    @Override
    public Set<String> collectAppAttachments(List<String> paths) {
        return ChatUtil.collectAttachments(tree, paths);
    }

    @Override
    public final boolean clearInterceptorSettings() {
        boolean changed = ChatUtil.removeInterceptorConfiguration(tree);
        if (changed) {
            treeChanged();
        }
        return changed;
    }

    @Override
    public final boolean applyDefaults(Map<String, Object> defaults) {
        boolean changed = ChatUtil.applyDefaults(tree, defaults);
        if (changed) {
            treeChanged();
        }
        return changed;
    }

    @Override
    public final Buffer serialize() throws JsonProcessingException {
        return received == null ? Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)) : received.withModel(modelOverride);
    }
}
