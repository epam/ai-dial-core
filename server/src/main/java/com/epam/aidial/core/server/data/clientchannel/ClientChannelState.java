package com.epam.aidial.core.server.data.clientchannel;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class ClientChannelState {
    private Map<String, PendingMessage> pendingMessages = new HashMap<>();
}
