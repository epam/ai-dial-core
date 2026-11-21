package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ChatUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A request backed by a JSON object body. It sends the {@link ReceivedBody received} bytes, with only the model value
 * replaced, until the tree changes in another way; from then on the tree is serialized. So the tree is changed only
 * through the methods of this class, and a subclass that changes {@link #tree()} calls {@link #treeChanged()}.
 */
abstract class JsonBodyRequest implements RequestObject {
    private static final String MODEL_NODE = "model";

    // the body as it now stands; its bytes are dropped by the first change they lack
    @JsonIgnore
    private ReceivedBody body;

    protected JsonBodyRequest(ReceivedBody body) {
        this.body = body;
    }

    /**
     * The body's tree, to read; changed only as the class comment says.
     */
    protected ObjectNode tree() {
        return body.tree();
    }

    protected void treeChanged() {
        body = body.withoutBytes();
    }

    @Override
    public String getModel() {
        return tree().path(MODEL_NODE).asText();
    }

    @Override
    public final void setModel(String model) {
        tree().put(MODEL_NODE, model);
        body = body.withModel(model);
    }

    @Override
    public boolean isStreaming() {
        return tree().path("stream").asBoolean(false);
    }

    @Override
    public Set<String> collectAppAttachments(List<String> paths) {
        return ChatUtil.collectAttachments(tree(), paths);
    }

    @Override
    public final boolean clearInterceptorSettings() {
        boolean changed = ChatUtil.removeInterceptorConfiguration(tree());
        if (changed) {
            treeChanged();
        }
        return changed;
    }

    @Override
    public final boolean applyDefaults(Map<String, Object> defaults) {
        boolean changed = ChatUtil.applyDefaults(tree(), defaults);
        if (changed) {
            treeChanged();
        }
        return changed;
    }

    @Override
    public final ReceivedBody getBody() {
        return body;
    }

    @Override
    public final Buffer serialize() throws JsonProcessingException {
        return body.serialize();
    }
}
