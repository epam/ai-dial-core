package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OboCredentialsRequest {

    @NotBlank(message = "url should be specified")
    private String url;

    @NotBlank(message = "owner_user_id should be specified")
    @JsonAlias({"ownerUserId", "owner_user_id"})
    private String ownerUserId;
}
