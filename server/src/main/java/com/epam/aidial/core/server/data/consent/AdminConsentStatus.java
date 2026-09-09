package com.epam.aidial.core.server.data.consent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * The admin-consent status read (GET /v1/consent/{id}/admin-consent). {@code consented} means
 * live right now — a record exists AND its snapshot equals the current declaration — exactly
 * what the request-time gate enforces; a stale record never reports consented. Provenance and
 * the approved snapshot are present whenever a record exists, including the stale case ("last
 * approved by X at T — re-approve"). The current declaration is deliberately not duplicated
 * here — it lives in the app definition and the user-consent document.
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminConsentStatus {

    private boolean consented;

    /** True when a record exists but no longer matches the current declaration. */
    private Boolean stale;

    private String grantedBy;

    private Long grantedAt;

    private List<Consent.ResourceEntry> grantedResources;
}
