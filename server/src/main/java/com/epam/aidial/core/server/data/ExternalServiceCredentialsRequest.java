package com.epam.aidial.core.server.data;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExternalServiceCredentialsRequest {

    @NotBlank(message = "url should be specified")
    private String url;
}
