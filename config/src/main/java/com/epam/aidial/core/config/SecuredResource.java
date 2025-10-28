package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class SecuredResource extends Deployment {

    @JsonAlias({"authSettings", "auth_settings"})
    protected ResourceAuthSettings authSettings = new ResourceAuthSettings();
}
