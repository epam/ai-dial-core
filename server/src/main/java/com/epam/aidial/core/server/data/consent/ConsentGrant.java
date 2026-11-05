package com.epam.aidial.core.server.data.consent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/** The stored admin-consent record; {@code grantedBy}/{@code grantedAt} are server-stamped and never part of the content binding. */
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsentGrant {

    private Consent consent;

    private String grantedBy;

    private Long grantedAt;
}
