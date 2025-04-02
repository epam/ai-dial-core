package com.epam.aidial.core.server.data.consent;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Consent {

    private Map<String, Deployment> deployments = new HashMap<>();

    @Data
    public static class Deployment {
        private boolean consentRequired;
    }
}
