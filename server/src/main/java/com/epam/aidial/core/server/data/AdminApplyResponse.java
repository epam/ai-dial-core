package com.epam.aidial.core.server.data;

import java.util.List;

public record AdminApplyResponse(int applied, int failed, List<AdminApplyResult> results) {}