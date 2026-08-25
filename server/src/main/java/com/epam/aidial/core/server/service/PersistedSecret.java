package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ExternalService;

/**
 * Carries the plaintext {@code clientSecret} across an encrypting write. Both external-service write paths
 * encrypt in place, which leaves the very object the caller is about to redact holding ciphertext — and a
 * hint derived from ciphertext is worse than none. Capture inside the compute block, immediately before
 * encryption; restore once the write has committed.
 *
 * <p>What is captured is what was persisted, which for a write that omitted {@code client_secret} is the
 * value preserved from storage rather than anything the request carried.</p>
 */
class PersistedSecret {

    private String secret;

    void capture(ExternalService service) {
        secret = service.getAuthSettings() == null ? null : service.getAuthSettings().getClientSecret();
    }

    void restoreOn(ExternalService service) {
        if (service.getAuthSettings() != null) {
            service.getAuthSettings().setClientSecret(secret);
        }
    }
}
