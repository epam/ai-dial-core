package com.epam.aidial.core.server.data.consent;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * The stored admin-consent record: the approved {@link Consent} snapshot plus its provenance —
 * who granted it and when, server-stamped at grant time. The envelope lives on the admin record
 * only; the user record stays bare (its who/when are structural: the record's bucket is the
 * consenting user, the blob metadata is the when). Because the provenance fields sit outside the
 * echoed {@link Consent} document, nothing on the client round-trip path is stampable, and the
 * content-binding compare (on {@link #consent}) is untouched by them.
 */
@Data
@Accessors(chain = true)
public class ConsentGrant {

    private Consent consent;

    private String grantedBy;

    private Long grantedAt;
}
