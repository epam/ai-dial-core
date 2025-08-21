package com.epam.aidial.core.server.data.toolset.credentials;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefreshTokenRequest {

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("client_secret")
    private String clientSecret;

    @JsonProperty("grant_type")
    private String grantType;

    @JsonProperty("refresh_token")
    private String refreshToken;


    public String buildFormData() {
        StringBuilder formData = new StringBuilder();
        appendIfNotNull(formData, "client_id", clientId);
        appendIfNotNull(formData, "client_secret", clientSecret);
        appendIfNotNull(formData, "grant_type", grantType);
        appendIfNotNull(formData, "refresh_token", refreshToken);
        return formData.toString();
    }

    private void appendIfNotNull(StringBuilder formData, String key, String value) {
        if (value != null) {
            if (!formData.isEmpty()) {
                formData.append("&");
            }
            formData.append(key)
                    .append("=")
                    .append(
                        URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
