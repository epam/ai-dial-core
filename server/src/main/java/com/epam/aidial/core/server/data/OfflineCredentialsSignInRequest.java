package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class OfflineCredentialsSignInRequest {

    @NotBlank(message = "code should be specified")
    @ToString.Exclude
    private String code;

    @NotBlank(message = "redirect_uri should be specified")
    @JsonAlias({"redirectUri", "redirect_uri"})
    private String redirectUri;
}
