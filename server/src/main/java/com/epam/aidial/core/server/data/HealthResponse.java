package com.epam.aidial.core.server.data;

import java.util.List;

public record HealthResponse(String status, List<SkippedEntity> skipped) {}