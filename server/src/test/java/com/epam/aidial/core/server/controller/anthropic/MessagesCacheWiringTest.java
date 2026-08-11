package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.BuildUpstreamCacheFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /anthropic/v1/messages} must build an upstream cache breakpoint context so multi-turn
 * conversations get pinned to the same upstream; {@code count_tokens} does not generate, so it must not.
 */
@ExtendWith(MockitoExtension.class)
class MessagesCacheWiringTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Test
    void testMessagesController_includesBuildUpstreamCacheFn() {
        MessagesController controller = new MessagesController(proxy, context);

        assertTrue(containsBuildUpstreamCacheFn(controller.enhancementFunctions));
    }

    @Test
    void testMessagesCountTokensController_excludesBuildUpstreamCacheFn() {
        MessagesCountTokensController controller = new MessagesCountTokensController(proxy, context);

        assertFalse(containsBuildUpstreamCacheFn(controller.enhancementFunctions));
    }

    private static boolean containsBuildUpstreamCacheFn(List<BaseRequestFunction<RequestObject>> functions) {
        return functions.stream().anyMatch(BuildUpstreamCacheFn.class::isInstance);
    }
}
