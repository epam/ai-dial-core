package com.epam.aidial.core.server.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ListCollectorTest {

    @Test
    void combine_dedupsWhilePreservingInsertionOrder() {
        ListCollector<String> collector = new ListCollector<>();
        collector.combine(List.of("a"));
        collector.combine(List.of("b"));
        collector.combine(List.of("a"));

        Assertions.assertEquals(List.of("a", "b"), collector.collect());
    }
}
