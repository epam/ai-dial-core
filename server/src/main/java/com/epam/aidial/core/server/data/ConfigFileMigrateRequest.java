package com.epam.aidial.core.server.data;

import java.util.List;

public record ConfigFileMigrateRequest(List<String> types, Boolean dryRun) {}
