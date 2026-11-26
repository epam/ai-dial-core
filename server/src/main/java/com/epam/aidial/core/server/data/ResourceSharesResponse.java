package com.epam.aidial.core.server.data;

import java.util.List;

public record ResourceSharesResponse(String url, List<ResourceShare> shares) {
}
