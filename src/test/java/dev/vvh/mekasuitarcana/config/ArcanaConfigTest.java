package dev.vvh.mekasuitarcana.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.ModuleKind;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.SpeedPreset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the loader-free half of {@link ArcanaTuning}: {@code ArcanaTuning.balance(Optional)}
 * and the {@code ArcanaTuning.Values} record it consumes. Nothing here touches the NeoForge
 * config spec - and it could not, because the plain JUnit classpath carries no NeoForge classes - so
 * this runs without a game.
 *
 * <p>The mapping lives in its own loader-free class: loading {@code ArcanaConfig} itself would
 * resolve {@code ModContainer}, which drags in NeoForge's spec types, so the loader-free half has to
 * sit somewhere the loader cannot reach it.</p>
 *
 * <p>The spec itself - key names, ranges, comments, and the {@code Spec.read()} wiring from typed
 * config values into {@code Values} - needs a loaded game and is not covered here.</p>
 */
class ArcanaConfigTest {

    @Test
    @DisplayName("no loaded config falls back to the conservative defaults")
    void noLoadedConfigFallsBackToDefaults() {
        assertEquals(ArcanaRates.defaults(), ArcanaTuning.balance(Optional.empty()));
        assertThrows(NullPointerException.class, () -> ArcanaTuning.balance(null),
                "the fallback is spelled Optional.empty(), not null");
    }

    @Test
    @DisplayName("the shipped config values map back onto the shipped rates exactly")
    void shippedValuesMapToShippedRates() {
        ArcanaTuning.Values shipped = shipped();
        assertTrue(shipped.disabledModules().isEmpty(), "nothing is disabled by default");
        assertEquals(ModuleKind.values().length, shipped.maxUnits().size());
        assertEquals(SpeedPreset.values().length, shipped.manaFePerTickByPreset().size());
        assertEquals(5, shipped.maxManaByUnits().size(), "index 0 plus the four curve points");
        assertEquals(ArcanaRates.defaults(), balanceOf(shipped));
    }

    @Test
    @DisplayName("caps follow the ModuleKind order")
    void capsFollowModuleKindOrder() {
        ArcanaRates rates = balanceOf(tuned(
                List.of(0, 111, 222, 333, 444, 555), null, List.of(5, 2, 3, 4, 1), null));

        assertEquals(4, rates.maxUnits(ModuleKind.MANA_CONVERSION), "hard design cap");
        assertEquals(2, rates.maxUnits(ModuleKind.AMPLIFICATION));
        assertEquals(3, rates.maxUnits(ModuleKind.FOCUS));
        assertEquals(4, rates.maxUnits(ModuleKind.COOLDOWN_ACCELERATION));
        assertEquals(1, rates.maxUnits(ModuleKind.CASTING_STABILIZATION));
        assertEquals(444, rates.maxManaForUnits(5), "execution clamps the count before pricing");
    }

    @Test
    @DisplayName("the speed presets follow the SpeedPreset order")
    void presetsFollowSpeedPresetOrder() {
        ArcanaRates rates = balanceOf(tuned(null, List.of(7, 8, 9, 10), null, null));

        assertEquals(7, rates.manaConversionFePerTick(SpeedPreset.LOW));
        assertEquals(8, rates.manaConversionFePerTick(SpeedPreset.NORMAL));
        assertEquals(9, rates.manaConversionFePerTick(SpeedPreset.HIGH));
        assertEquals(10, rates.manaConversionFePerTick(SpeedPreset.MAXIMUM));
    }

    @Test
    @DisplayName("a speed preset the table does not reach is off, not unbounded")
    void shortPresetTableLeavesLaterPresetsOff() {
        ArcanaRates rates = balanceOf(tuned(null, List.of(250), null, null));

        assertEquals(250, rates.manaConversionFePerTick(SpeedPreset.LOW));
        assertEquals(0, rates.manaConversionFePerTick(SpeedPreset.NORMAL));
        assertEquals(0, rates.manaConversionFePerTick(SpeedPreset.HIGH));
        assertEquals(0, rates.manaConversionFePerTick(SpeedPreset.MAXIMUM));
    }

    @Test
    @DisplayName("a disabled module reports a cap of zero, for every kind")
    void disablingAModuleZeroesItsCap() {
        for (ModuleKind kind : ModuleKind.values()) {
            ArcanaRates rates = balanceOf(tuned(null, null, null, Set.of(kind)));
            assertEquals(0, rates.maxUnits(kind), kind + " must be capped at zero when disabled");
            assertEquals(0, rates.clampUnits(kind, 4));
        }
    }

    @Test
    @DisplayName("a disabled module contributes no attribute and charges no FE")
    void disabledModuleHasNoEffect() {
        ArcanaRates rates = balanceOf(tuned(null, null, null, Set.of(
                ModuleKind.MANA_CONVERSION,
                ModuleKind.AMPLIFICATION,
                ModuleKind.FOCUS,
                ModuleKind.COOLDOWN_ACCELERATION,
                ModuleKind.CASTING_STABILIZATION)));

        assertEquals(0, rates.maxManaForUnits(4), "no MAX_MANA override from a disabled module");
        assertEquals(0.0D, rates.amplificationPercent(4));
        assertEquals(0.0D, rates.focusPercent(4));
        assertEquals(0.0D, rates.cooldownReductionPercent(5));
        assertEquals(0.0D, rates.castTimeReductionPercent(4));
        assertFalse(rates.removesCastingMovementPenalty(1), "even one unit must not remove the penalty");
        // The arithmetic calculator intentionally has no disabled-module state. Runtime must gate
        // conversion on this zero execution cap before it calls the calculator.
        assertEquals(0, rates.maxUnits(ModuleKind.MANA_CONVERSION));
    }

    @Test
    @DisplayName("a short MAX_MANA curve shortens the cap instead of failing construction")
    void shortManaCurveShortensTheCap() {
        ArcanaRates twoPoints = balanceOf(tuned(List.of(0, 1_000), null, null, null));
        assertEquals(1, twoPoints.maxUnits(ModuleKind.MANA_CONVERSION));
        assertEquals(1_000, twoPoints.maxManaForUnits(1));
        assertEquals(1_000, twoPoints.maxManaForUnits(4), "the shortened cap bounds the count");

        ArcanaRates onlyZero = balanceOf(tuned(List.of(0), null, null, null));
        assertEquals(0, onlyZero.maxUnits(ModuleKind.MANA_CONVERSION));
        assertEquals(0, onlyZero.maxManaForUnits(1), "an unpriced unit count supplies no MAX_MANA");

        ArcanaRates empty = balanceOf(tuned(List.of(), null, null, null));
        assertEquals(0, empty.maxUnits(ModuleKind.MANA_CONVERSION));
        assertEquals(0, empty.maxManaForUnits(1));
    }

    @Test
    @DisplayName("kinds a cap table omits are off, and extra entries cannot widen the design")
    void capTableLengthCannotWidenTheDesign() {
        ArcanaRates empty = balanceOf(tuned(null, null, List.of(), null));
        for (ModuleKind kind : ModuleKind.values()) {
            assertEquals(0, empty.maxUnits(kind));
        }

        ArcanaRates partial = balanceOf(tuned(null, null, List.of(2), null));
        assertEquals(2, partial.maxUnits(ModuleKind.MANA_CONVERSION));
        assertEquals(4_000, partial.maxManaForUnits(2));
        assertEquals(0, partial.maxUnits(ModuleKind.AMPLIFICATION));
        assertEquals(0, partial.maxUnits(ModuleKind.CASTING_STABILIZATION));

        ArcanaRates extra = balanceOf(tuned(null, null, List.of(1, 2, 3, 4, 5, 6, 7), null));
        assertEquals(ModuleKind.values().length, extra.maxUnits().size());
        assertEquals(1, extra.maxUnits(ModuleKind.MANA_CONVERSION));
        assertEquals(4, extra.maxUnits(ModuleKind.CASTING_STABILIZATION), "hard design cap");
    }

    @Test
    @DisplayName("config tuning cannot exceed the locked maximum total ratings")
    void hardRatingCapsAreAppliedAtExecutionSnapshot() {
        ArcanaTuning.Values shipped = shipped();
        ArcanaTuning.Values hostile = new ArcanaTuning.Values(
                shipped.fePerMana(), shipped.maxManaByUnits(), shipped.manaFePerTickByPreset(),
                999.0D, shipped.amplificationFePerCast(), 999.0D, shipped.focusFePerCast(),
                999.0D, shipped.cooldownFePerTickPerSlot(), shipped.cooldownSavedTimeFePerTick(),
                999.0D, shipped.castingFePerTick(), shipped.castingSavedTimeFePerTick(),
                List.of(99, 99, 99, 99, 99), Set.of());
        ArcanaRates rates = balanceOf(hostile);
        assertEquals(200.0D, rates.amplificationPercent(4));
        assertEquals(200.0D, rates.focusPercent(4));
        assertEquals(500.0D, rates.cooldownReductionPercent(5));
        assertEquals(200.0D, rates.castTimeReductionPercent(4));
        assertEquals(4, rates.maxUnits(ModuleKind.CASTING_STABILIZATION));
    }

    @Test
    @DisplayName("negative or NaN numbers cannot produce a powerful or invalid snapshot")
    void hostileNumbersAreSanitized() {
        ArcanaRates negative = ArcanaTuning.balance(Optional.of(new ArcanaTuning.Values(
                -10.0D,
                List.of(-1, -1_000, -4_000, -7_000, -10_000),
                List.of(-100, -400, -1_000, -2_500),
                -50.0D, -500,
                -50.0D, -500,
                -100.0D, -20, -40.0D,
                -50.0D, -20, -40.0D,
                List.of(-4, -4, -4, -5, -4),
                Set.of())));

        assertEquals(ModuleKind.values().length, negative.maxUnits().size());
        for (ModuleKind kind : ModuleKind.values()) {
            assertEquals(0, negative.maxUnits(kind));
        }
        assertEquals(0.0D, negative.feForMana(500), "a negative rate is not a refund");
        assertEquals(0, negative.manaRestorableForFe(10_000.0D, 500));
        assertEquals(0.0D, negative.amplificationPercent(4));
        assertEquals(0.0D, negative.focusPercent(4));
        assertEquals(0.0D, negative.cooldownReductionPercent(5));
        assertEquals(0.0D, negative.castTimeReductionPercent(4));
        assertEquals(0.0D, negative.amplificationCastCost());
        assertEquals(0.0D, negative.focusCastCost());
        assertEquals(0.0D, negative.cooldownFePerTick(3, 1.0D));
        assertEquals(0.0D, negative.castingFePerTick(1.0D));

        ArcanaRates nan = ArcanaTuning.balance(Optional.of(new ArcanaTuning.Values(
                Double.NaN,
                List.of(0, 1_000, 4_000, 7_000, 10_000),
                List.of(100, 400, 1_000, 2_500),
                50.0D, 500,
                50.0D, 500,
                100.0D, 20, 40.0D,
                50.0D, 20, 40.0D,
                List.of(4, 4, 4, 5, 4),
                Set.of())));

        assertEquals(0, nan.manaRestorableForFe(1_000.0D, 100));
        assertFalse(Double.isNaN(nan.cooldownFePerTick(1, 0.5D)), "NaN must not leak into a cost");
        assertEquals(400.0D, nan.maxManaForUnits(2) / 10.0D, "the rest of the snapshot is untouched");
    }

    @Test
    @DisplayName("the disabled set and the tables are copied, so later mutation cannot move a snapshot")
    void inputsAreCopied() {
        EnumSet<ModuleKind> liveDisabled = EnumSet.noneOf(ModuleKind.class);
        List<Integer> liveCaps = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        ArcanaTuning.Values values = tuned(null, null, liveCaps, liveDisabled);

        assertEquals(1, values.maxUnits().get(0));
        assertTrue(values.disabledModules().isEmpty());

        liveCaps.set(0, 0);
        liveDisabled.add(ModuleKind.FOCUS);

        assertEquals(1, values.maxUnits().get(0), "the record copied the cap table");
        assertTrue(values.disabledModules().isEmpty(), "the record copied the disabled set");
        assertEquals(1, balanceOf(values).maxUnits(ModuleKind.MANA_CONVERSION));
        assertEquals(3, balanceOf(values).maxUnits(ModuleKind.FOCUS));
    }

    private static ArcanaTuning.Values shipped() {
        return ArcanaTuning.Values.defaults();
    }

    private static ArcanaRates balanceOf(ArcanaTuning.Values values) {
        return ArcanaTuning.balance(Optional.of(values));
    }

    /**
     * The shipped values with only a few fields replaced; {@code null} keeps the shipped value.
     */
    private static ArcanaTuning.Values tuned(
            List<Integer> maxManaByUnits,
            List<Integer> manaFePerTickByPreset,
            List<Integer> maxUnits,
            Set<ModuleKind> disabledModules) {
        ArcanaTuning.Values shipped = shipped();
        return new ArcanaTuning.Values(
                shipped.fePerMana(),
                maxManaByUnits == null ? shipped.maxManaByUnits() : maxManaByUnits,
                manaFePerTickByPreset == null ? shipped.manaFePerTickByPreset() : manaFePerTickByPreset,
                shipped.amplificationPercentPerUnit(),
                shipped.amplificationFePerCast(),
                shipped.focusPercentPerUnit(),
                shipped.focusFePerCast(),
                shipped.cooldownPercentPerUnit(),
                shipped.cooldownFePerTickPerSlot(),
                shipped.cooldownSavedTimeFePerTick(),
                shipped.castingPercentPerUnit(),
                shipped.castingFePerTick(),
                shipped.castingSavedTimeFePerTick(),
                maxUnits == null ? shipped.maxUnits() : maxUnits,
                disabledModules == null ? shipped.disabledModules() : disabledModules);
    }
}
