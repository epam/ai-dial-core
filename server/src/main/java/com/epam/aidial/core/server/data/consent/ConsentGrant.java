package com.epam.aidial.core.server.data.consent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * The stored admin-consent record: the approved {@link Consent} snapshot plus its provenance —
 * who granted it and when, server-stamped at grant time. The envelope lives on the admin record
 * only; the user record stays bare (its who/when are structural: the record's bucket is the
 * consenting user, the blob metadata is the when). Because the provenance fields sit outside the
 * echoed {@link Consent} document, nothing on the client round-trip path is stampable, and the
 * content-binding compare (on {@link #consent}) is untouched by them.
 *
 * <p>{@code ignoreUnknown}: records written by the pre-envelope commits of this branch store a
 * bare {@code Consent} body at the same key — lenient reading turns those into {@code consent ==
 * null}, which flows into the already-built fail-closed path (stale, nothing resolves); without
 * it, the strict mapper throws and even withdraw cannot delete the record.
 */
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsentGrant {

    private Consent consent;

    private String grantedBy;

    private Long grantedAt;
}
