package com.epam.aidial.core.server.limiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LimitBreachTest {

    @Test
    void picker_singleOffer() {
        LimitBreach.Picker picker = new LimitBreach.Picker(LimitBreach.Kind.TOKENS);
        picker.offer(LimitBreach.Window.MINUTE, 30, -1);

        assertEquals(new LimitBreach(LimitBreach.Kind.TOKENS, LimitBreach.Window.MINUTE, 30, -1), picker.build());
    }

    @Test
    void picker_keepsTheLongestWait() {
        LimitBreach.Picker picker = new LimitBreach.Picker(LimitBreach.Kind.COST);
        picker.offer(LimitBreach.Window.MINUTE, 30, -1);
        picker.offer(LimitBreach.Window.DAY, 3600, 1_000L);
        picker.offer(LimitBreach.Window.WEEK, 1200, 2_000L);

        assertEquals(new LimitBreach(LimitBreach.Kind.COST, LimitBreach.Window.DAY, 3600, 1_000L), picker.build());
    }

    @Test
    void picker_tieGoesToTheLaterOfferedLongerWindow() {
        LimitBreach.Picker picker = new LimitBreach.Picker(LimitBreach.Kind.TOKENS);
        picker.offer(LimitBreach.Window.DAY, 600, 1_000L);
        picker.offer(LimitBreach.Window.MONTH, 600, 1_000L);

        assertEquals(LimitBreach.Window.MONTH, picker.build().window());
    }

    @Test
    void picker_zeroWaitStillCounts() {
        LimitBreach.Picker picker = new LimitBreach.Picker(LimitBreach.Kind.REQUESTS);
        picker.offer(LimitBreach.Window.HOUR, 0, -1);

        assertEquals(LimitBreach.Window.HOUR, picker.build().window());
        assertEquals(0, picker.build().retryAfterSeconds());
    }

    @Test
    void picker_nothingOfferedCannotBuild() {
        LimitBreach.Picker picker = new LimitBreach.Picker(LimitBreach.Kind.TOKENS);

        assertThrows(IllegalStateException.class, picker::build);
    }

    @Test
    void window_ofCalendarPeriod() {
        assertEquals(LimitBreach.Window.DAY, LimitBreach.Window.of(CalendarPeriod.DAY));
        assertEquals(LimitBreach.Window.WEEK, LimitBreach.Window.of(CalendarPeriod.WEEK));
        assertEquals(LimitBreach.Window.MONTH, LimitBreach.Window.of(CalendarPeriod.MONTH));
    }
}
