package com.epam.aidial.core.server.data.toolset.credentials;

import lombok.Builder;
import lombok.Data;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Data
@Builder
public class TokenRequest {

    private String clientId;
    private String clientSecret;
    private String code;
    private String scope;
    private String grantType;
    private String codeVerifier;
    private String redirectUri;

    public String buildFormData() {
        StringBuilder formData = new StringBuilder();
        appendIfNotNull(formData, "client_id", clientId);
        appendIfNotNull(formData, "client_secret", clientSecret);
        appendIfNotNull(formData, "code", code);
        appendIfNotNull(formData, "scope", scope);
        appendIfNotNull(formData, "grant_type", grantType);
        appendIfNotNull(formData, "redirect_uri", redirectUri);
        appendIfNotNull(formData, "code_verifier", codeVerifier);
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
