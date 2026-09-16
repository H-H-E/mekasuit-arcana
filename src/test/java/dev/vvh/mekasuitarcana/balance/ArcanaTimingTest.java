package dev.vvh.mekasuitarcana.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArcanaTimingTest {

    @Test
    @DisplayName("duration multipliers preserve the identity and full module cap")
    void durationMultiplierBoundaries() {
        assertEquals(1.0D, ArcanaTiming.durationMultiplier(1.0D), 1.0E-12D);
        assertEquals(7.0D, ArcanaTiming.durationMultiplier(-5.0D), 1.0E-12D,
                "finite native debuffs remain part of the baseline");
        assertEquals(0.05D, ArcanaTiming.durationMultiplier(6.0D), 1.0E-12D);
        assertEquals(0.5D, ArcanaTiming.extraTicks(0.5D, 0.5D), 1.0E-12D,
                "a below-1 baseline must price the marginal step from 1.5x to 1.0x");
        assertTrue(ArcanaTiming.durationMultiplier(Double.POSITIVE_INFINITY)
                >= ArcanaTiming.MIN_DURATION_MULTIPLIER);
    }

    @Test
    @DisplayName("extra ticks are marginal to the existing rating, not all-gear savings")
    void extraTicksUseMarginalRatio() {
        assertEquals(0.0D, ArcanaTiming.extraTicks(1.0D, 0.0D));
        assertEquals(1.0D, ArcanaTiming.extraTicks(3.0D, 2.0D), 1.0E-12D,
                "0.125 baseline / 0.0625 enhanced - 1");
        assertEquals(0.5D, ArcanaTiming.marginalSavedFraction(3.0D, 2.0D), 1.0E-12D,
                "the suit saves half of the other-gear baseline, not 93.75% from identity");
        assertEquals(10.0D, ArcanaTiming.marginalSavedTicks(20.0D, 3.0D, 2.0D), 1.0E-12D);
    }

    @Test
    @DisplayName("fractional progress is capped before saved-time energy is charged")
    void actualProgressCapsCost() {
        assertEquals(0.25D, ArcanaTiming.actualExtraTicks(0.25D, 0.5D), 1.0E-12D);
        assertEquals(12.5D, ArcanaTiming.cooldownCostPerSpell(10.0D, 10.0D, 0.5D, 0.25D),
                1.0E-12D, "fixed 10 + saved rate for the 0.25 ticks actually executed");
        assertEquals(10.0D, ArcanaTiming.cooldownCostPerSpell(10.0D, 10.0D, 0.0D, 50.0D));
    }

    @Test
    @DisplayName("negative, zero, NaN, and infinite inputs never create invalid timing or cost")
    void malformedInputsAreSafe() {
        assertEquals(0.0D, ArcanaTiming.extraTicks(-5.0D, -2.0D));
        assertEquals(0.0D, ArcanaTiming.extraTicks(Double.NaN, Double.NaN));
        assertFalse(Double.isNaN(ArcanaTiming.durationMultiplier(Double.NaN)));
        assertFalse(Double.isInfinite(ArcanaTiming.durationMultiplier(Double.POSITIVE_INFINITY)));
        assertEquals(0.0D, ArcanaTiming.cooldownCostPerSpell(-10.0D, -4.0D, -1.0D));
        assertFalse(Double.isNaN(ArcanaTiming.cooldownCostPerSpell(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)));
        assertTrue(ArcanaTiming.cooldownCostPerSpell(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY) >= 0.0D);
    }

    @Test
    @DisplayName("casting cost prices only the marginal baseline-to-enhanced duration")
    void castingCostUsesMarginalSavings() {
        assertEquals(20.0D, ArcanaTiming.castingCostPerTick(20.0D, 40.0D, 1.0D, 0.0D));
        assertEquals(40.0D, ArcanaTiming.castingCostPerTick(20.0D, 40.0D, 3.0D, 2.0D),
                1.0E-12D, "20 fixed + 40 * 0.5 marginal saved fraction");
    }
}
