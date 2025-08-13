package com.epam.aidial.core.server.data.toolset.credentials;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenRequest {

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("client_secret")
    private String clientSecret;

    @JsonProperty("code")
    private String code;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("grant_type")
    private String grantType;

    @JsonProperty("redirect_uri")
    private String redirectUri;

    public String buildFormData() {
        StringBuilder formData = new StringBuilder();

        appendIfNotNull(formData, "client_id", clientId);
        appendIfNotNull(formData, "client_secret", clientSecret);
        appendIfNotNull(formData, "code", code);
        appendIfNotNull(formData, "scope", scope);
        appendIfNotNull(formData, "grant_type", grantType);
        appendIfNotNull(formData, "redirect_uri", redirectUri);

        return formData.toString();
    }

    private void appendIfNotNull(StringBuilder formData, String key, String value) {
        if (value != null) {
            if (!formData.isEmpty()) {
                formData.append("&");
            }
            formData.append(key)
                .append("=")
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
