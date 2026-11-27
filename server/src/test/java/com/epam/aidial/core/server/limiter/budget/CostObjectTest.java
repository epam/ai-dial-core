package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.SpendingLimits;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostObjectTest {

    private static final Supplier<SpendingLimits> UNREAD = () -> {
        throw new AssertionError("the default cost object must not be read");
    };

    @Test
    void testClaimWinsOverKey() {
        assertEquals(new CostObject("projA", CostObject.Source.CLAIM), CostObject.resolve("projA", key("keyProject"), UNREAD));
    }

    @Test
    void testKeyProjectWithoutClaim() {
        assertEquals(new CostObject("keyProject", CostObject.Source.KEY), CostObject.resolve(null, key("keyProject"), UNREAD));
    }

    @Test
    void testDefaultWithoutClaimAndKey() {
        SpendingLimits settings = new SpendingLimits();
        settings.setDefaultCostObject("PERSONAL");

        assertEquals(new CostObject("PERSONAL", CostObject.Source.DEFAULT), CostObject.resolve(null, null, () -> settings));
    }

    @Test
    void testDefaultForKeyWithoutProject() {
        assertEquals(new CostObject("ORG", CostObject.Source.DEFAULT), CostObject.resolve(null, key(null), SpendingLimits::new));
    }

    @Test
    void testHeaderWinsOverClaimAndKey() {
        assertEquals(new CostObject("IMF", CostObject.Source.HEADER), CostObject.resolve("IMF", "projA", key("keyProject"), UNREAD));
    }

    @Test
    void testNoHeaderFallsBackToClaim() {
        assertEquals(new CostObject("projA", CostObject.Source.CLAIM), CostObject.resolve(null, "projA", null, UNREAD));
    }

    @Test
    void testSourceValues() {
        assertEquals("claim", CostObject.Source.CLAIM.toValue());
        assertEquals("key", CostObject.Source.KEY.toValue());
        assertEquals("header", CostObject.Source.HEADER.toValue());
        assertEquals("default", CostObject.Source.DEFAULT.toValue());
    }

    private static Key key(String project) {
        Key key = new Key();
        key.setProject(project);
        return key;
    }
}
