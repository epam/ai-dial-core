package com.epam.aidial.core.server.data.consent;

import lombok.Data;

@Data
public class ConsentResponse {
    private Consent consent;
    private boolean accepted;
}
