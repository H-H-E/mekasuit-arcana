package dev.vvh.mekasuitarcana.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArcanaRatesTest {

    private static final ArcanaRates RATES = ArcanaRates.defaults();

    @Test
    @DisplayName("MAX_MANA follows the locked curve, and no units means no override")
    void maxManaCurve() {
        assertEquals(0, RATES.maxManaForUnits(0), "an unmodded suit must not touch MAX_MANA");
        assertEquals(1_000, RATES.maxManaForUnits(1));
        assertEquals(4_000, RATES.maxManaForUnits(2));
        assertEquals(7_000, RATES.maxManaForUnits(3));
        assertEquals(10_000, RATES.maxManaForUnits(4));
    }

    @Test
    @DisplayName("unit counts clamp to the cap instead of extrapolating the curve")
    void unitCountsClamp() {
        assertEquals(10_000, RATES.maxManaForUnits(99));
        assertEquals(200.0D, RATES.amplificationPercent(99), "4 units x 50%");
        assertEquals(500.0D, RATES.cooldownReductionPercent(99), "5 units x 100%");
        assertEquals(0.0D, RATES.amplificationPercent(-3));
    }

    @Test
    @DisplayName("movement penalty removal is binary, not scaled by units")
    void movementPenaltyIsBinary() {
        assertFalse(RATES.removesCastingMovementPenalty(0));
        assertTrue(RATES.removesCastingMovementPenalty(1));
        assertTrue(RATES.removesCastingMovementPenalty(4));
    }

    @Test
    @DisplayName("mana restoration respects the FE budget and the player's headroom")
    void manaRestorationIsBoundedByBothSides() {
        assertEquals(0, RATES.manaRestorableForFe(0.0D, 500), "no energy, no mana");
        assertEquals(0, RATES.manaRestorableForFe(10_000.0D, 0), "no headroom, no mana");
        assertEquals(50, RATES.manaRestorableForFe(500.0D, 1_000), "500 FE / 10 FE per mana");
        assertEquals(100, RATES.manaRestorableForFe(10_000.0D, 100), "clamped by headroom");
        assertEquals(0, RATES.manaRestorableForFe(-5.0D, 100), "negative budget is not a refund");
    }

    @Test
    @DisplayName("a zero or negative FE-per-mana setting cannot mint mana")
    void zeroRateCannotMintMana() {
        ArcanaRates broken = new ArcanaRates(
                List.of(0, 1_000, 4_000, 7_000, 10_000), 0.0D, List.of(100, 400, 1_000, 2_500),
                50.0D, 500, 50.0D, 500, 100.0D, 20, 40.0D, 50.0D, 20, 40.0D,
                List.of(4, 4, 4, 5, 4));
        assertEquals(0, broken.manaRestorableForFe(1_000.0D, 100));
    }

    @Test
    @DisplayName("cooldown cost is additive: every active cooldown costs the fixed term")
    void cooldownCostIsAdditive() {
        assertEquals(0.0D, RATES.cooldownFePerTick(0, 0.5D), "no cooldowns, no cost");
        assertEquals(20.0D, RATES.cooldownFePerTick(1, 0.0D));
        assertEquals(60.0D, RATES.cooldownFePerTick(3, 0.0D));
        assertEquals(80.0D, RATES.cooldownFePerTick(3, 0.5D), "60 fixed + 20 saved-time");
    }

    @Test
    @DisplayName("fractions are clamped so over-100% reduction cannot invert a cost")
    void fractionsAreClamped() {
        assertEquals(RATES.cooldownFePerTick(1, 1.0D), RATES.cooldownFePerTick(1, 5.0D));
        assertEquals(RATES.cooldownFePerTick(1, 0.0D), RATES.cooldownFePerTick(1, -5.0D));
        assertEquals(0.0D, ArcanaRates.clampFraction(Double.NaN));
    }

    @Test
    @DisplayName("casting cost tracks time saved on top of the fixed drain")
    void castingCostTracksSavedTime() {
        assertEquals(20.0D, RATES.castingFePerTick(0.0D));
        assertEquals(60.0D, RATES.castingFePerTick(1.0D), "20 fixed + 40 saved");
    }

    @Test
    @DisplayName("durations never collapse below one tick")
    void durationsFloorAtOneTick() {
        assertEquals(1, ArcanaRates.clampTicks(0));
        assertEquals(1, ArcanaRates.clampTicks(-10));
        assertEquals(7, ArcanaRates.clampTicks(7));
    }

    @Test
    @DisplayName("speed presets map to increasing FE ceilings")
    void speedPresetsAreOrdered() {
        int low = RATES.manaConversionFePerTick(ArcanaRates.SpeedPreset.LOW);
        int normal = RATES.manaConversionFePerTick(ArcanaRates.SpeedPreset.NORMAL);
        int high = RATES.manaConversionFePerTick(ArcanaRates.SpeedPreset.HIGH);
        int maximum = RATES.manaConversionFePerTick(ArcanaRates.SpeedPreset.MAXIMUM);
        assertTrue(low < normal && normal < high && high < maximum);
    }

    @Test
    @DisplayName("malformed tables fail loudly instead of silently mis-indexing")
    void malformedTablesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ArcanaRates(
                List.of(0, 1_000), 10.0D, List.of(100, 400, 1_000, 2_500),
                50.0D, 500, 50.0D, 500, 100.0D, 20, 40.0D, 50.0D, 20, 40.0D,
                List.of(4, 4, 4, 5, 4)));
        assertThrows(IllegalArgumentException.class, () -> new ArcanaRates(
                List.of(0, 1_000, 4_000, 7_000, 10_000), 10.0D, List.of(100),
                50.0D, 500, 50.0D, 500, 100.0D, 20, 40.0D, 50.0D, 20, 40.0D,
                List.of(4, 4, 4, 5, 4)));
    }
    @Test
    @DisplayName("the percent knobs convert to Iron's 1.0-based rating deltas exactly once")
    void percentConvertsToRatingDelta() {
        assertEquals(0.0D, ArcanaRates.ratingDelta(0.0D));
        assertEquals(2.0D, ArcanaRates.ratingDelta(200.0D), "+200% is a delta of 2.0, not a value of 2.0");
        assertEquals(5.0D, ArcanaRates.ratingDelta(500.0D));
        assertEquals(0.0D, ArcanaRates.ratingDelta(Double.NaN), "a broken knob must not become free power");
    }

    @Test
    @DisplayName("Iron's soft cap is reproduced, not approximated")
    void softCapMatchesIronsCurve() {
        assertEquals(1.0D, ArcanaRates.ironSoftCap(1.0D), "an unmodified attribute is the identity");
        assertEquals(1.5D, ArcanaRates.ironSoftCap(1.5D), "the curve is linear up to the knee");
        assertEquals(1.875D, ArcanaRates.ironSoftCap(3.0D), "2.0 - 0.25 / (3.0 - 1.0)");
        assertEquals(1.95D, ArcanaRates.ironSoftCap(6.0D), "2.0 - 0.25 / (6.0 - 1.0)");
        assertTrue(ArcanaRates.ironSoftCap(50.0D) < 2.0D, "the asymptote is never crossed");
    }

    @Test
    @DisplayName("a full stack delivers the advertised maximum, through Iron's own curve")
    void fullStacksDeliverTheDesignMaximum() {
        assertEquals(1.0D, RATES.cooldownReductionMultiplier(0), "no units, no change");
        assertEquals(1.0D, RATES.castTimeReductionMultiplier(0));
        assertEquals(0.05D, RATES.cooldownReductionMultiplier(5), 1.0E-9D,
                "+500% reaches the soft cap 0.05 multiplier, so cooldowns are 20x shorter");
        assertEquals(0.125D, RATES.castTimeReductionMultiplier(4), 1.0E-9D,
                "+200% reaches a 0.125 multiplier, so casts are 8x faster");
        assertEquals(0.05D, RATES.cooldownReductionMultiplier(99), 1.0E-9D, "clamped at the cap");
    }

    @Test
    @DisplayName("FE time-saved terms are priced against the honoured reduction, not the raw rating")
    void savedTimeIsPricedAgainstTheRealReduction() {
        assertEquals(0.95D, ArcanaRates.durationSavedFraction(RATES.cooldownReductionMultiplier(5)), 1.0E-9D);
        assertEquals(0.0D, ArcanaRates.durationSavedFraction(1.0D), "no reduction, nothing to pay for");
        assertEquals(0.0D, ArcanaRates.durationSavedFraction(1.5D), "an over-unity multiplier saves nothing");
    }

    @Test
    @DisplayName("casting movement contributes the 80% delta that cancels Iron's penalty")
    void castingMovementIsBinaryAtFullValue() {
        assertEquals(0.0D, RATES.castingMovespeedPercent(0));
        assertEquals(80.0D, RATES.castingMovespeedPercent(1), "+80% makes 0.2 + 0.8 = 1.0");
        assertEquals(80.0D, RATES.castingMovespeedPercent(4), "extra units add nothing");
    }
}
