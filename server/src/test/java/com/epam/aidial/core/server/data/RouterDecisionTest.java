package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterDecisionTest {

    @Test
    void newVerdictAppendsAndMovesToItsFirstCandidate() {
        RouterDecision decision = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1"), 10);
        decision.setResolving(false);
        decision.fail(502, "upstream", false);

        decision.redecided(List.of("m2", "m3"), 5);

        assertEquals("m2", decision.current());
        assertTrue(decision.isResolving(), "the new verdict's first candidate is resolved anew");
        assertTrue(decision.hasNext());
        assertEquals(List.of("m1", "m2", "m3"), decision.getCandidates());
        assertEquals(15, decision.getDecideMs());
        assertEquals(2, decision.getDecisions());
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "upstream")), decision.getAttempts());
    }

    @Test
    void failRecordsWithoutMoving() {
        RouterDecision decision = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1", "m2"), 1);

        decision.fail(502, "body", true);

        assertEquals("m1", decision.current());
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "body", true, false)), decision.getAttempts());
    }

    @Test
    void limitAndExhaustStopTheLoop() {
        RouterDecision decision = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1"), 1);
        assertTrue(decision.canRedecide(2));
        decision.redecided(List.of("m1"), 1);
        assertFalse(decision.canRedecide(2), "two decisions of two");

        RouterDecision other = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1"), 1);
        other.exhaust();
        assertFalse(other.canRedecide(3));
        RouterDecision closed = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1"), 1);
        closed.close();
        assertFalse(closed.canRedecide(3));
    }

    @Test
    void plainFallbackRowKeepsItsShape() throws Exception {
        RouterDecision decision = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1", "m2"), 12);
        decision.skip(502, "upstream");

        assertEquals("{\"router\":\"r1\",\"api\":\"openaiChatCompletions\",\"candidates\":[\"m1\",\"m2\"],\"deployment\":\"m2\","
                + "\"decide_ms\":12,\"attempts\":[{\"deployment\":\"m1\",\"status\":502,\"reason\":\"upstream\"}]}",
                ProxyUtil.MAPPER.writeValueAsString(decision.toRow()));
    }

    @Test
    void redecidedRowCarriesDecisionsAndReceived() throws Exception {
        RouterDecision decision = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1"), 3);
        decision.fail(502, "body", true);
        decision.redecided(List.of("m2"), 4);

        String json = ProxyUtil.MAPPER.writeValueAsString(decision.toRow());

        assertEquals("{\"router\":\"r1\",\"api\":\"openaiChatCompletions\",\"candidates\":[\"m1\",\"m2\"],\"deployment\":\"m2\","
                + "\"decide_ms\":7,\"attempts\":[{\"deployment\":\"m1\",\"status\":502,\"reason\":\"body\",\"received\":true}],"
                + "\"decisions\":2}", json);
    }

    @Test
    void routerRowTakenOnce() {
        RouterDecision decision = new RouterDecision("r1", InterfaceType.OPENAI_CHAT_COMPLETIONS, List.of("m1"), 1);
        assertTrue(decision.takeRow());
        assertFalse(decision.takeRow());
    }
}
