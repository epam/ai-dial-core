package com.epam.aidial.core.server.data.consent;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewConsentResponse(Consent consent, boolean accepted) {
}
