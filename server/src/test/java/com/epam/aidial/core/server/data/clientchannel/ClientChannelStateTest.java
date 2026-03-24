package com.epam.aidial.core.server.data.clientchannel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientChannelStateTest {

    @Test
    public void testRemoveExpiredMessages() {
        ClientChannelState state = new ClientChannelState();

        PendingMessage message = new PendingMessage();
        message.setStatus(PendingMessage.Status.RECEIVED);
        message.setReceivedAt(10);
        state.getPendingMessages().put("1", message);

        message = new PendingMessage();
        message.setStatus(PendingMessage.Status.RECEIVED);
        message.setReceivedAt(5);
        state.getPendingMessages().put("2", message);

        message = new PendingMessage();
        message.setStatus(PendingMessage.Status.SENT);
        state.getPendingMessages().put("3", message);

        message = new PendingMessage();
        message.setStatus(PendingMessage.Status.RECEIVED);
        message.setReceivedAt(3);
        state.getPendingMessages().put("4", message);

        state.removeExpiredMessages(5, 10);

        assertEquals(2, state.getPendingMessages().size());
        assertTrue(state.getPendingMessages().containsKey("3"));
        assertTrue(state.getPendingMessages().containsKey("1"));
    }
}
