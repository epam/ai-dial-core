package com.epam.aidial.core.server.data.consent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** Admin-consent status of an application; the grant fields are present whenever a record exists, stale or not. */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminConsentStatus {

    /** The approval is live: a record exists and matches the current declaration. */
    private boolean consented;

    /** A record exists but no longer matches the current declaration; absent when there is no record. */
    private Boolean stale;

    /** Who granted: the admin's user id, or the API key's project. */
    private String grantedBy;

    /** When it was granted, epoch milliseconds. */
    private Long grantedAt;

    /** The approved snapshot, as declared at grant time. */
    private List<Consent.ResourceEntry> grantedResources;
}
