package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OboCredentialsRequest {

    @NotBlank(message = "url should be specified")
    private String url;

    @NotBlank(message = "owner_sub should be specified")
    @JsonAlias({"ownerSub", "owner_sub"})
    private String ownerSub;
}
