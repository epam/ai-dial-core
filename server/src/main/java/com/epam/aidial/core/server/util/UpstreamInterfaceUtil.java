package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Resolves the configuration an upstream serves one interface type with — the values forwarded to the
 * adapter as {@code X-UPSTREAM-ENDPOINT}, {@code X-UPSTREAM-KEY} and {@code X-UPSTREAM-EXTRA-DATA}.
 * An upstream carries two configuration shapes: the typed {@code interfaces} map, whose entries
 * override the upstream's own fields for that interface alone, and the pre-{@code interfaces}
 * {@code endpoint} and {@code responsesEndpoint} fields, which serve whatever interface asks for them.
 */
@UtilityClass
public class UpstreamInterfaceUtil {

    /**
     * The provider url for the type, or null when the upstream has nothing serving it — the header is
     * then omitted and the adapter falls back to its own configuration.
     */
    @Nullable
    public String resolveEndpoint(Upstream upstream, InterfaceType type) {
        UpstreamInterface upstreamInterface = findInterface(upstream, type);
        if (upstreamInterface == null) {
            return resolveLegacyEndpoint(upstream, type);
        }
        String endpoint = upstreamInterface.getEndpoint();
        return endpoint != null ? endpoint : appendApiPath(upstream.getBaseUrl(), type);
    }

    /**
     * The credential for the type: the interface's own key when it declares one, the upstream's otherwise.
     */
    @Nullable
    public String resolveKey(Upstream upstream, InterfaceType type) {
        return resolveOverridable(upstream, type, UpstreamInterface::getKey, upstream.getKey());
    }

    @Nullable
    public String resolveExtraData(Upstream upstream, InterfaceType type) {
        return resolveOverridable(upstream, type, UpstreamInterface::getExtraData, upstream.getExtraData());
    }

    @Nullable
    public String resolveSecretExtraData(Upstream upstream, InterfaceType type) {
        return resolveOverridable(upstream, type, UpstreamInterface::getSecretExtraData, upstream.getSecretExtraData());
    }

    /**
     * The interface's own value for the field, falling back to the upstream's. Each field falls back
     * independently, so an interface overriding only {@code key} still inherits {@code extraData}.
     */
    @Nullable
    private String resolveOverridable(
            Upstream upstream,
            InterfaceType type,
            Function<UpstreamInterface, String> field,
            @Nullable String fallback
    ) {
        UpstreamInterface upstreamInterface = findInterface(upstream, type);
        String override = upstreamInterface == null ? null : field.apply(upstreamInterface);
        return override != null ? override : fallback;
    }

    @Nullable
    private UpstreamInterface findInterface(Upstream upstream, InterfaceType type) {
        Map<String, UpstreamInterface> interfaces = upstream.getInterfaces();
        return interfaces == null ? null : interfaces.get(type.getValue());
    }

    /**
     * The url {@code baseUrl} implies for the type, or null when the upstream declares no base url.
     */
    @Nullable
    private String appendApiPath(@Nullable String baseUrl, InterfaceType type) {
        if (baseUrl == null) {
            return null;
        }
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return root + type.getApiPath();
    }

    /**
     * The pre-{@code interfaces} field serving the type. {@code endpoint} predates the split into typed
     * interfaces, so every non-Responses interface has been reading it — the Anthropic Messages API
     * included, which is what {@code interfaces} exists to disentangle.
     */
    @Nullable
    private String resolveLegacyEndpoint(Upstream upstream, InterfaceType type) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS, OPENAI_EMBEDDINGS, ANTHROPIC_MESSAGES -> upstream.getEndpoint();
            case OPENAI_RESPONSES -> upstream.getResponsesEndpoint();
        };
    }
}
