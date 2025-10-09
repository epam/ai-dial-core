package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;

public interface AuthSettingsValidator {

    void validate(ResourceAuthSettings resourceAuthSettings,
                  ResourceAuthSettingsChangeMode resourceAuthSettingsChangeMode);
}
