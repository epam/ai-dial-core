package com.epam.aidial.core.server.data.config.apply;

import java.util.List;

public record AdminApplyRequest(Boolean precheck, List<AdminManifest> manifests) {}