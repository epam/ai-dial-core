package com.epam.aidial.core.data;

import lombok.Data;

import java.util.List;

@Data
public class SubscribeResourcesRequest {
    List<ResourceLink> resources;
}