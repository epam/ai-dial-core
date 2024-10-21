package com.epam.aidial.core.server.data;

import lombok.Data;

import java.util.List;

@Data
public class SubscribeResourcesRequest {
    List<ResourceLink> resources;
}