package com.epam.aidial.core.server.data.clientchannel;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ClientChannelState {
    private Map<String, PendingMessage> pendingMessages = new LinkedHashMap<>();

    @JsonIgnore
    public void removeExpiredMessages(long timeout, long nowTs) {
        pendingMessages.values().removeIf(message -> message.getStatus() == PendingMessage.Status.RECEIVED
                && message.getReceivedAt() + timeout >= nowTs);
    }
}
