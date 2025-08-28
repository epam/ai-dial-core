package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class SecuredResource extends Deployment {

    @JsonAlias({"authSettings", "auth_settings"})
    protected ResourceAuthSettings authSettings = new ResourceAuthSettings();
}
