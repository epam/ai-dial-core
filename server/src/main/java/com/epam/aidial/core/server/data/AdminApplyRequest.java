package com.epam.aidial.core.server.data;

import java.util.List;

public record AdminApplyRequest(Boolean precheck, List<AdminManifest> manifests) {}