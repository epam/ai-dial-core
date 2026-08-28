package com.epam.aidial.core.server.data.config.migration;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigFileMigrateResult(String id, ConfigFileMigrateStatus status, String reason) {}
