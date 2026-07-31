package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.data.Conversation;
import com.epam.aidial.core.server.data.Prompt;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProxyUtilTest {
    @Test
    public void testPromptSchemaValidation() {
        String validPromptJson = """
                {
                "id": "Id1",
                "folderId": "folder1",
                "name": "My awesome prompt",
                "content": "this is a content",
                "description": "description"
                }
                """;
        assertDoesNotThrow(() -> ProxyUtil.convertToObject(validPromptJson, Prompt.class));

        String missingRequiredField = """
                {
                "id": "Id1",
                "folderId": "folder1",
                "name": "My awesome prompt",
                "description": "description"
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject(missingRequiredField, Prompt.class));
        assertEquals("Missing required property 'content'", error.getMessage());

        error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject("12345", ProxyUtil.class));
        assertEquals("Provided payload do not match required schema", error.getMessage());
    }

    @Test
    public void testConversationSchemaValidation() {
        String validConversationJson = """
                {
                "id": "conversation_id",
                "name": "display_name",
                "model": {
                  "id": "model_id"
                  },
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "folder1",
                "messages": [
                  {
                  "role": "user",
                  "content": "content",
                  "custom_content": {"attachment_url": "some_url"},
                  "model": {"id": "model_id"},
                  "settings":
                    {
                    "prompt": "sysPrompt",
                    "temperature": 5,
                    "assistantModelId": "assistantId"
                    }
                  }
                ],
                "replay": {
                  "isReplay": true,
                  "replayUserMessagesStack": [],
                  "activeReplayIndex": 0
                  },
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;
        assertDoesNotThrow(() -> ProxyUtil.convertToObject(validConversationJson, Conversation.class));

        String missingRequiredField = """
                {
                "id": "conversation_id",
                "name": "display_name",
                "model": {
                  },
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "folder1",
                "messages": [
                  {
                  "role": "user",
                  "content": "content",
                  "custom_content": {"attachment_url": "some_url"},
                  "model": {"id": "model_id"},
                  "settings":
                    {
                    "prompt": "sysPrompt",
                    "temperature": 5,
                    "assistantModelId": "assistantId"
                    }
                  }
                ],
                "replay": {
                  "isReplay": true,
                  "replayUserMessagesStack": [],
                  "activeReplayIndex": 0
                  },
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject(missingRequiredField, Conversation.class));
        assertEquals("Missing required property 'model.id'", error.getMessage());

        error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject("12345", Conversation.class));
        assertEquals("Provided payload do not match required schema", error.getMessage());
    }

    @Test
    public void testCustomViewStateValidation() {
        String validConversationJson = """
                {
                "id": "conversation_id",
                "name": "display_name",
                "model": {
                  "id": "model_id"
                  },
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "folder1",
                "messages": [
                  {
                  "role": "user",
                  "content": "content",
                  "custom_content": {"attachment_url": "some_url"},
                  "model": {"id": "model_id"},
                  "settings":
                    {
                    "prompt": "sysPrompt",
                    "temperature": 5,
                    "assistantModelId": "assistantId"
                    }
                  }
                ],
                "replay": {
                  "isReplay": true,
                  "replayUserMessagesStack": [],
                  "activeReplayIndex": 0
                  },
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153,
                "customViewState": {
                    "a": ["A"],
                    "b": {
                        "c": ["C"],
                        "d": 5.12
                    }
                }
                }
                """;

        assertDoesNotThrow(() -> ProxyUtil.convertToObject(validConversationJson, Conversation.class));
    }

    @Test
    public void testGetClientIpAddress() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195, 2001:db8:85a3:8d3:1319:8a2e:370:7348");
        assertEquals("203.0.113.195", ProxyUtil.getClientIpAddress(request, 2));

        Mockito.reset(request);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195");
        assertEquals("203.0.113.195", ProxyUtil.getClientIpAddress(request, 1));

        Mockito.reset(request);
        HttpConnection connection = mock(HttpConnection.class);
        when(request.connection()).thenReturn(connection);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195");
        assertNull(ProxyUtil.getClientIpAddress(request, 0));

        Mockito.reset(request, connection);
        when(request.connection()).thenReturn(connection);
        Assertions.assertNull(ProxyUtil.getClientIpAddress(request, 0));

        Mockito.reset(request, connection);
        when(request.connection()).thenReturn(connection);
        SocketAddress socketAddress = mock(SocketAddress.class);
        when(connection.remoteAddress(true)).thenReturn(socketAddress);
        when(socketAddress.isInetSocket()).thenReturn(false);
        Assertions.assertNull(ProxyUtil.getClientIpAddress(request, 0));

        Mockito.reset(request, connection, socketAddress);
        connection = mock(HttpConnection.class);
        when(request.connection()).thenReturn(connection);
        socketAddress = mock(SocketAddress.class);
        when(connection.remoteAddress(true)).thenReturn(socketAddress);
        when(socketAddress.isInetSocket()).thenReturn(true);
        when(socketAddress.host()).thenReturn("203.0.113.195");
        assertEquals("203.0.113.195", ProxyUtil.getClientIpAddress(request, 0));

        Mockito.reset(request, connection, socketAddress);
        when(request.getHeader("X-Forwarded-For")).thenReturn("100.0.113.200, 2001:db8:85a3:8d3:1319:8a2e:370:7348");
        connection = mock(HttpConnection.class);
        when(request.connection()).thenReturn(connection);
        socketAddress = mock(SocketAddress.class);
        when(connection.remoteAddress(true)).thenReturn(socketAddress);
        when(socketAddress.isInetSocket()).thenReturn(true);
        when(socketAddress.host()).thenReturn("203.0.113.195");
        assertEquals("203.0.113.195", ProxyUtil.getClientIpAddress(request, 3));
    }

    @Test
    public void testSetOverrideNameHeader_NullDeployment_DoesNotThrowAndDoesNotSetHeader() {
        MultiMap headers = new HeadersMultiMap();

        assertDoesNotThrow(() -> ProxyUtil.setOverrideNameHeader(headers, null));

        assertNull(headers.get(Proxy.HEADER_OVERRIDE_NAME));
    }

    @Test
    public void testSetOverrideNameHeader_OverrideNameSet_SetsHeader() {
        MultiMap headers = new HeadersMultiMap();
        Model model = new Model();
        model.setName("name");
        model.setOverrideName("overrideName");

        ProxyUtil.setOverrideNameHeader(headers, model);

        assertEquals("overrideName", headers.get(Proxy.HEADER_OVERRIDE_NAME));
    }

    @Test
    public void testSetOverrideNameHeader_OverrideNameNull_DoesNotSetHeader() {
        MultiMap headers = new HeadersMultiMap();
        Model model = new Model();
        model.setName("name");

        ProxyUtil.setOverrideNameHeader(headers, model);

        assertNull(headers.get(Proxy.HEADER_OVERRIDE_NAME));
    }
}
