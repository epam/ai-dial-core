package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stored body is bound to its entity, and a failure names the resource and never a value of the body. */
public class DependencyEntityBinderTest {

    private static ResourceDescriptor platform(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", name);
    }

    @Test
    void testBodyIsBoundToTheEntityOfItsType() {
        Deployment application = DependencyEntityBinder.bind(platform(ResourceTypes.APPLICATION, "a"),
                "{\"endpoint\":\"http://a.invalid\",\"dependencies\":[\"applications/platform/b\"]}");
        Deployment model = DependencyEntityBinder.bind(platform(ResourceTypes.MODEL, "m"),
                "{\"type\":\"chat\",\"displayName\":\"M\",\"endpoint\":\"http://m.invalid/v1\"}");

        assertEquals(List.of("applications/platform/b"), assertInstanceOf(Application.class, application).getDependencies());
        assertInstanceOf(Model.class, model);
    }

    @Test
    void testBodyThatCannotBeBoundFailsWithTheResourceAndNoValue() {
        ResourceDescriptor descriptor = platform(ResourceTypes.APPLICATION, "a");
        for (String body : List.of("{ sk-abc123 not json", "[\"sk-abc123\"]", "\"sk-abc123\"", "{\"dependencies\":\"sk-abc123\"}")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> DependencyEntityBinder.bind(descriptor, body), body);

            assertTrue(failure.getMessage().endsWith("(resource=application:platform:a)"), failure.getMessage());
            assertFalse(failure.getMessage().contains("sk-abc123"), "no value of the body in the message");
            assertEquals(null, failure.getCause(), "no cause, whose message can quote the body");
        }
    }

    @Test
    void testTypeWithoutAnEntityIsRefusedWithTheResource() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DependencyEntityBinder.bind(platform(ResourceTypes.FILE, "f"), "{}"));

        assertTrue(failure.getMessage().contains("(resource=file:platform:f)"), failure.getMessage());
    }
}
