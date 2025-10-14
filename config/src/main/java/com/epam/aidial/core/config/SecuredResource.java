package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class SecuredResource extends Deployment {

    @JsonAlias({"forwardPerRequestKey", "forward_per_request_key"})
    private boolean forwardPerRequestKey;

    @JsonAlias({"authSettings", "auth_settings"})
    protected ResourceAuthSettings authSettings = new ResourceAuthSettings();
}
