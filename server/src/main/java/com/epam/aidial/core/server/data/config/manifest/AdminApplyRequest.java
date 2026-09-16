package com.epam.aidial.core.server.data.config.manifest;

import java.util.List;

public record AdminApplyRequest(Boolean precheck, List<AdminManifest> manifests) {}