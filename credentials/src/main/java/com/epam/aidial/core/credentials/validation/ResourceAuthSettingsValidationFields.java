package com.epam.aidial.core.credentials.validation;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Set;

@RequiredArgsConstructor
@Data
@Builder
public class ResourceAuthSettingsValidationFields {

    private final Set<ResourceAuthSettingsField> requiredFields;
    private final Set<ResourceAuthSettingsField> forbiddenFields;
}
