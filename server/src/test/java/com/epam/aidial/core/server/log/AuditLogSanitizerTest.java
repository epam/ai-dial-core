package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.ConsentRequiredException;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditLogSanitizerTest {

    @Test
    void tokenLosesEverythingThatCouldForgeField() {
        assertEquals("a_b_c_d_e_f_g_h", AuditLogSanitizer.sanitizeToken("a b=c\"d\ne\u0085f g h"));
        assertNull(AuditLogSanitizer.sanitizeToken(null));
    }

    @Test
    void reasonKeepsSpacesButCannotEscapeItsQuotes() {
        assertEquals("not found_ x__y_z", AuditLogSanitizer.sanitizeReason("not found\n x=\"y\rz"));
        assertEquals(" reason=\"a b_c\"", AuditLogSanitizer.reasonOf(new IllegalStateException("a b\"c")));
        assertEquals("", AuditLogSanitizer.reasonOf(null));
    }

    @Test
    void outcomeNamesTheFailure() {
        assertEquals("SUCCESS", AuditLogSanitizer.outcomeOf(null));
        assertEquals("CONSENT_REQUIRED", AuditLogSanitizer.outcomeOf(new ConsentRequiredException("x")));
        assertEquals("DENIED", AuditLogSanitizer.outcomeOf(new PermissionDeniedException("x")));
        assertEquals("NOT_FOUND", AuditLogSanitizer.outcomeOf(new ResourceNotFoundException("x")));
        assertEquals("ERROR", AuditLogSanitizer.outcomeOf(new IllegalStateException("x")));
    }

    @Test
    void actorEvidenceRecordsTheKeyProjectAndTheWorkloadAzp() {
        assertEquals("project:p_1 azp:app", AuditLogSanitizer.actorEvidence(context(key("p 1"), claims("app"))));
        assertEquals("project:p", AuditLogSanitizer.actorEvidence(context(key("p"), null)));
        assertEquals("azp:a_b", AuditLogSanitizer.actorEvidence(context(null, claims("a=b"))));
        assertEquals("unknown", AuditLogSanitizer.actorEvidence(context(null, claims(null))));
    }

    private static Key key(String project) {
        Key key = new Key();
        key.setProject(project);
        return key;
    }

    private static ExtractedClaims claims(String azp) {
        ObjectNode userClaims = ProxyUtil.MAPPER.createObjectNode();
        if (azp != null) {
            userClaims.put("azp", azp);
        }
        return new ExtractedClaims("user", List.of(), null, userClaims, null, null, null);
    }

    private static ProxyContext context(Key key, ExtractedClaims claims) {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getKey()).thenReturn(key);
        when(context.getExtractedClaims()).thenReturn(claims);
        return context;
    }
}
