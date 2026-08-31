package com.epam.aidial.core.server.data.config.migration;

import java.util.List;

public record ConfigFileMigrateRequest(List<String> types, Boolean dryRun) {}
