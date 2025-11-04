package com.epam.aidial.core.server.data.permission;

import lombok.Data;

import java.util.List;

@Data
public class PerRequestReceiverList {
    private List<PerRequestReceiver> receivers;
}
