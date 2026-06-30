package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.experimental.Accessors;

/** Management-API view of an external-service definition; never carries credential material. */
@Data
@Accessors(chain = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ExternalServiceData {

    private String id;
    private String displayName;
    private String description;
    private ResourceAuthSettings authSettings;
}
