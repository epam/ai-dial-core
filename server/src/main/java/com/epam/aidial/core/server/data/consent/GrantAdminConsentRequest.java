package com.epam.aidial.core.server.data.consent;

import lombok.Data;

import java.util.List;

@Data
public class GrantAdminConsentRequest {
    /** The resources the administrator reviewed, as declared; the grant succeeds only if they equal the current declaration. */
    private List<Consent.ResourceEntry> resources;
}
