package dev.vvh.mekasuitarcana.config;

import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.ModuleKind;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.SpeedPreset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The loader-free half of the balance layer: plain config values in, an {@link ArcanaRates}
 * snapshot out.
 *
 * <p>This lives in its own file for a concrete reason. {@link ArcanaConfig} owns the NeoForge
 * config spec, so the JVM must link FML types just to load that class. While this mapping was
 * nested inside it, {@code gradlew test} failed with
 * {@code NoClassDefFoundError: net/neoforged/fml/config/IConfigSpec} before a single assertion ran -
 * nesting a type does not insulate it from its enclosing class's linkage. Nothing here imports
 * Minecraft or NeoForge, so plain JUnit can exercise the whole mapping, and {@link ArcanaConfig}
 * delegates to it.</p>
 *
 * <p>Disabling a module is expressed as a zero cap rather than a new field, because
 * {@link ArcanaRates} is frozen and a zero cap already removes the module whole effect: every
 * accessor clamps the unit count through {@link ArcanaRates#clampUnits(ModuleKind, int)}, and
 * {@code maxManaForUnits(0)} answers "no override". So a disabled module contributes nothing and
 * cannot charge FE, with no second flag for callers to consult.</p>
 */
public final class ArcanaTuning {

    private ArcanaTuning() {
    }

    /**
     * The snapshot used before the config file is loaded. It is the conservative
     * {@link ArcanaRates#defaults()}, so an unread config can never hand out a stronger suit than
     * the shipped one.
     */
    private static final ArcanaRates FALLBACK = ArcanaRates.defaults();

    /** Hard design caps; config may lower these, but never widen them. */
    public static int hardCap(ModuleKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case MANA_CONVERSION, AMPLIFICATION, FOCUS, CASTING_STABILIZATION -> 4;
            case COOLDOWN_ACCELERATION -> 5;
        };
    }

    /** Maximum per-unit percentage which can still respect the design cap. */
    public static double hardPercentPerUnit(ModuleKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case AMPLIFICATION, FOCUS, CASTING_STABILIZATION -> 50.0D;
            case COOLDOWN_ACCELERATION -> 100.0D;
            case MANA_CONVERSION -> 0.0D;
        };
    }

    /**
     * Every server-wide knob in plain, immutable form, shaped like the {@link ArcanaRates} it turns
     * into: the same three tables in the same enum order, plus which modules are switched off.
     *
     * @param maxManaByUnits MAX_MANA supplied per installed Mana Conversion Unit; index 0 is unused
     * @param manaFePerTickByPreset per-tick FE ceiling, ordered by {@link SpeedPreset}
     * @param maxUnits per-carrier caps, ordered by {@link ModuleKind}
     * @param disabledModules kinds whose cap must be forced to zero
     */
    public record Values(
            double fePerMana,
            List<Integer> maxManaByUnits,
            List<Integer> manaFePerTickByPreset,
            double amplificationPercentPerUnit,
            int amplificationFePerCast,
            double focusPercentPerUnit,
            int focusFePerCast,
            double cooldownPercentPerUnit,
            int cooldownFePerTickPerSlot,
            double cooldownSavedTimeFePerTick,
            double castingPercentPerUnit,
            int castingFePerTick,
            double castingSavedTimeFePerTick,
            List<Integer> maxUnits,
            Set<ModuleKind> disabledModules) {

        public Values {
            maxManaByUnits = List.copyOf(maxManaByUnits);
            manaFePerTickByPreset = List.copyOf(manaFePerTickByPreset);
            maxUnits = List.copyOf(maxUnits);
            disabledModules = Set.copyOf(disabledModules);
        }

        /**
         * The shipped, conservative numbers. Read from {@link ArcanaRates#defaults()} so the two can
         * never drift apart, with no module disabled.
         */
        public static Values defaults() {
            return from(ArcanaRates.defaults());
        }

        /**
         * Lifts an existing balance snapshot into config terms, with nothing disabled. Unit counts
         * become caps, which is the same question for both types.
         */
        public static Values from(ArcanaRates rates) {
            Objects.requireNonNull(rates, "rates");
            return new Values(
                    rates.fePerMana(),
                    rates.maxManaByUnits(),
                    rates.manaFePerTickByPreset(),
                    rates.amplificationPercentPerUnit(),
                    rates.amplificationFePerCast(),
                    rates.focusPercentPerUnit(),
                    rates.focusFePerCast(),
                    rates.cooldownPercentPerUnit(),
                    rates.cooldownFePerTickPerSlot(),
                    rates.cooldownSavedTimeFePerTick(),
                    rates.castingPercentPerUnit(),
                    rates.castingFePerTick(),
                    rates.castingSavedTimeFePerTick(),
                    rates.maxUnits(),
                    Set.of());
        }

        /** The shipped cap for a kind, or 0 when the shipped table has no entry there. */
        public int cap(ModuleKind kind) {
            Objects.requireNonNull(kind, "kind");
            return valueAtOrZero(maxUnits, kind.ordinal());
        }

        /** The shipped MAX_MANA for a unit count, or 0 when the curve has no entry there. */
        public int maxManaAtUnits(int units) {
            return valueAtOrZero(maxManaByUnits, units);
        }

        /** The shipped per-tick FE ceiling for a preset, or 0 when the table has no entry there. */
        public int manaFePerTick(SpeedPreset preset) {
            Objects.requireNonNull(preset, "preset");
            return valueAtOrZero(manaFePerTickByPreset, preset.ordinal());
        }
    }

    /**
     * The whole mapping, in one place: plain config values in, balance snapshot out. An empty
     * argument means "no config is loaded yet" and yields {@link ArcanaRates#defaults()}.
     *
     * <p>The result always satisfies the {@link ArcanaRates} constructor, whatever the input: table
     * sizes are rebuilt from the enums, negative and absent entries become zero, and the MAX_MANA
     * curve - not the cap - decides how many Mana Conversion Units can be priced. A hand-edited
     * config therefore cannot throw inside a tick.</p>
     */
    public static ArcanaRates balance(Optional<Values> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        if (loaded.isEmpty()) {
            return FALLBACK;
        }
        Values values = loaded.get();

        List<Integer> maxManaByUnits = nonNegative(values.maxManaByUnits());
        if (maxManaByUnits.isEmpty()) {
            // ArcanaRates requires index 0 to exist even when no unit count has a price.
            maxManaByUnits = List.of(0);
        }

        List<Integer> maxUnits = new ArrayList<>(ModuleKind.values().length);
        for (ModuleKind kind : ModuleKind.values()) {
            // The installed-count cap is an execution boundary as well as a GUI hint. A hand-edited
            // config must not widen either carrier beyond the locked design maximum.
            int cap = Math.min(hardCap(kind), values.cap(kind));
            if (kind == ModuleKind.MANA_CONVERSION) {
                // ArcanaRates rejects a curve that does not cover the cap, so a short curve shortens
                // the cap instead of failing construction.
                cap = Math.min(cap, maxManaByUnits.size() - 1);
            }
            maxUnits.add(values.disabledModules().contains(kind) ? 0 : cap);
        }

        List<Integer> manaFePerTickByPreset = new ArrayList<>(SpeedPreset.values().length);
        for (SpeedPreset preset : SpeedPreset.values()) {
            manaFePerTickByPreset.add(values.manaFePerTick(preset));
        }

        double amplificationPercent = boundedPercent(values.amplificationPercentPerUnit(),
                ModuleKind.AMPLIFICATION);
        double focusPercent = boundedPercent(values.focusPercentPerUnit(), ModuleKind.FOCUS);
        double cooldownPercent = boundedPercent(values.cooldownPercentPerUnit(),
                ModuleKind.COOLDOWN_ACCELERATION);
        double castingPercent = boundedPercent(values.castingPercentPerUnit(),
                ModuleKind.CASTING_STABILIZATION);

        return new ArcanaRates(
                maxManaByUnits,
                nonNegative(values.fePerMana()),
                manaFePerTickByPreset,
                amplificationPercent,
                nonNegative(values.amplificationFePerCast()),
                focusPercent,
                nonNegative(values.focusFePerCast()),
                cooldownPercent,
                nonNegative(values.cooldownFePerTickPerSlot()),
                nonNegative(values.cooldownSavedTimeFePerTick()),
                castingPercent,
                nonNegative(values.castingFePerTick()),
                nonNegative(values.castingSavedTimeFePerTick()),
                maxUnits);
    }

    /**
     * The entry at {@code index}, or 0 when the table has no entry there. Zero is the conservative
     * reading of both a missing cap (the kind is off) and a missing speed preset (nothing may be
     * converted at that speed); negatives clamp to zero as well.
     */
    private static int valueAtOrZero(List<Integer> values, int index) {
        if (index < 0 || index >= values.size()) {
            return 0;
        }
        Integer value = values.get(index);
        return value == null ? 0 : Math.max(0, value);
    }

    /** The same table with negatives clamped to zero and nulls dropped to zero, order kept. */
    private static List<Integer> nonNegative(List<Integer> values) {
        List<Integer> result = new ArrayList<>(values.size());
        for (Integer value : values) {
            result.add(nonNegative(value == null ? 0 : value));
        }
        return result;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    /**
     * Negative and NaN rates become zero. {@code Math.max} is deliberately not used for the NaN case:
     * it propagates NaN, which would then quietly poison every cost calculation.
     */
    private static double nonNegative(double value) {
        return Double.isNaN(value) || value < 0.0D ? 0.0D : value;
    }

    private static double boundedPercent(double value, ModuleKind kind) {
        return Math.min(hardPercentPerUnit(kind), nonNegative(value));
    }
}
