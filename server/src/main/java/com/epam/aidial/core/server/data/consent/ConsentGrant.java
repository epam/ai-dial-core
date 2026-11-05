package com.epam.aidial.core.server.data.consent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * The stored admin-consent record: the approved {@link Consent} snapshot plus who granted it and when,
 * server-stamped at grant time. The provenance never takes part in the content binding.
 */
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsentGrant {

    private Consent consent;

    private String grantedBy;

    private Long grantedAt;
}
