package com.epam.aidial.core.server.data.config.apply;

import java.util.List;

public record AdminValidateResponse(int valid, int failed, List<ValidationResult> results) {}