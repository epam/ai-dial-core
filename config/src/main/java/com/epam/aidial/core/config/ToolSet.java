package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * The class describes metadata of the MCP server.
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ToolSet extends SecuredResource {

    private Transport transport = Transport.HTTP;

    @JsonAlias({"allowedTools", "allowed_tools"})
    private List<String> allowedTools = List.of();

    private String provider;

    @JsonAlias({"vendorWebsite", "vendor_website"})
    private String vendorWebsite;

    public enum Transport {
        HTTP, SSE;
    }

    /**
     * Strips credential material for a response. Deliberately not restricted to {@code OAUTH}: a blob of any
     * authentication type that carries a {@code clientSecret} — written before the per-type validators existed,
     * or hand-seeded — must not have it echoed back either.
     */
    @JsonIgnore
    public void clearAuthSettings(boolean revealClientSecretHint) {
        if (authSettings != null) {
            authSettings.redactSecrets(revealClientSecretHint);
        }
    }
}
