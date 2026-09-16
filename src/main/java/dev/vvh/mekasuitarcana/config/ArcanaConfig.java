package dev.vvh.mekasuitarcana.config;

import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.ModuleKind;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.SpeedPreset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/** Server-wide config facade. Loader-free mapping lives in {@link ArcanaTuning}. */
public final class ArcanaConfig {
    private ArcanaConfig() {}

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Spec.SPEC);
    }

    /** Live server snapshot; before load, the conservative locked defaults apply. */
    public static ArcanaRates rates() {
        return ArcanaTuning.balance(Spec.loadedValues());
    }

    public static boolean isModuleEnabled(ModuleKind kind) {
        if (!Spec.SPEC.isLoaded()) return true;
        return Spec.loadedValues().map(values -> !values.disabledModules().contains(kind)).orElse(true);
    }

    private static final class Spec {
        private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
        private static final ModConfigSpec SPEC;

        private static final ConfigValue<Double> FE_PER_MANA;
        private static final ConfigValue<Integer> MANA_MAX_UNITS;
        private static final ConfigValue<Integer> MAX_MANA_1;
        private static final ConfigValue<Integer> MAX_MANA_2;
        private static final ConfigValue<Integer> MAX_MANA_3;
        private static final ConfigValue<Integer> MAX_MANA_4;
        private static final ConfigValue<Integer> MANA_LOW;
        private static final ConfigValue<Integer> MANA_NORMAL;
        private static final ConfigValue<Integer> MANA_HIGH;
        private static final ConfigValue<Integer> MANA_MAXIMUM;
        private static final ConfigValue<Integer> AMP_MAX_UNITS;
        private static final ConfigValue<Double> AMP_PERCENT;
        private static final ConfigValue<Integer> AMP_FE;
        private static final ConfigValue<Integer> FOCUS_MAX_UNITS;
        private static final ConfigValue<Double> FOCUS_PERCENT;
        private static final ConfigValue<Integer> FOCUS_FE;
        private static final ConfigValue<Integer> COOLDOWN_MAX_UNITS;
        private static final ConfigValue<Double> COOLDOWN_PERCENT;
        private static final ConfigValue<Integer> COOLDOWN_FE;
        private static final ConfigValue<Double> COOLDOWN_SAVED_FE;
        private static final ConfigValue<Integer> CASTING_MAX_UNITS;
        private static final ConfigValue<Double> CASTING_PERCENT;
        private static final ConfigValue<Integer> CASTING_FE;
        private static final ConfigValue<Double> CASTING_SAVED_FE;
        private static final ConfigValue<Boolean> DISABLED_MANA;
        private static final ConfigValue<Boolean> DISABLED_AMP;
        private static final ConfigValue<Boolean> DISABLED_FOCUS;
        private static final ConfigValue<Boolean> DISABLED_COOLDOWN;
        private static final ConfigValue<Boolean> DISABLED_CASTING;

        static {
            ArcanaTuning.Values defaults = ArcanaTuning.Values.defaults();
            BUILDER.comment("MekaSuit Arcana server balance; player module settings remain in Mekanism.")
                    .push("balance");
            FE_PER_MANA = BUILDER.defineInRange("fe_per_mana", defaults.fePerMana(), 0.0D, 10_000.0D);

            BUILDER.push("mana_conversion");
            MANA_MAX_UNITS = BUILDER.defineInRange("max_units", defaults.cap(ModuleKind.MANA_CONVERSION), 0, 4);
            MAX_MANA_1 = BUILDER.defineInRange("max_mana_at_units_1", defaults.maxManaAtUnits(1), 0, 1_000_000);
            MAX_MANA_2 = BUILDER.defineInRange("max_mana_at_units_2", defaults.maxManaAtUnits(2), 0, 1_000_000);
            MAX_MANA_3 = BUILDER.defineInRange("max_mana_at_units_3", defaults.maxManaAtUnits(3), 0, 1_000_000);
            MAX_MANA_4 = BUILDER.defineInRange("max_mana_at_units_4", defaults.maxManaAtUnits(4), 0, 1_000_000);
            MANA_LOW = BUILDER.defineInRange("fe_per_tick_low", defaults.manaFePerTick(SpeedPreset.LOW), 0, 1_000_000);
            MANA_NORMAL = BUILDER.defineInRange("fe_per_tick_normal", defaults.manaFePerTick(SpeedPreset.NORMAL), 0, 1_000_000);
            MANA_HIGH = BUILDER.defineInRange("fe_per_tick_high", defaults.manaFePerTick(SpeedPreset.HIGH), 0, 1_000_000);
            MANA_MAXIMUM = BUILDER.defineInRange("fe_per_tick_maximum", defaults.manaFePerTick(SpeedPreset.MAXIMUM), 0, 1_000_000);
            BUILDER.pop();

            BUILDER.push("amplification");
            AMP_MAX_UNITS = BUILDER.defineInRange("max_units", defaults.cap(ModuleKind.AMPLIFICATION), 0, 4);
            AMP_PERCENT = BUILDER.defineInRange("percent_per_unit", defaults.amplificationPercentPerUnit(), 0.0D, ArcanaTuning.hardPercentPerUnit(ModuleKind.AMPLIFICATION));
            AMP_FE = BUILDER.defineInRange("fe_per_cast", defaults.amplificationFePerCast(), 0, 1_000_000);
            BUILDER.pop();

            BUILDER.push("focus");
            FOCUS_MAX_UNITS = BUILDER.defineInRange("max_units", defaults.cap(ModuleKind.FOCUS), 0, 4);
            FOCUS_PERCENT = BUILDER.defineInRange("percent_per_unit", defaults.focusPercentPerUnit(), 0.0D, ArcanaTuning.hardPercentPerUnit(ModuleKind.FOCUS));
            FOCUS_FE = BUILDER.defineInRange("fe_per_cast", defaults.focusFePerCast(), 0, 1_000_000);
            BUILDER.pop();

            BUILDER.push("cooldown_acceleration");
            COOLDOWN_MAX_UNITS = BUILDER.defineInRange("max_units", defaults.cap(ModuleKind.COOLDOWN_ACCELERATION), 0, 5);
            COOLDOWN_PERCENT = BUILDER.defineInRange("percent_per_unit", defaults.cooldownPercentPerUnit(), 0.0D, ArcanaTuning.hardPercentPerUnit(ModuleKind.COOLDOWN_ACCELERATION));
            COOLDOWN_FE = BUILDER.defineInRange("fe_per_tick_per_slot", defaults.cooldownFePerTickPerSlot(), 0, 1_000_000);
            COOLDOWN_SAVED_FE = BUILDER.defineInRange("saved_time_fe_per_tick", defaults.cooldownSavedTimeFePerTick(), 0.0D, 10_000.0D);
            BUILDER.pop();

            BUILDER.push("casting_stabilization");
            CASTING_MAX_UNITS = BUILDER.defineInRange("max_units", defaults.cap(ModuleKind.CASTING_STABILIZATION), 0, 4);
            CASTING_PERCENT = BUILDER.defineInRange("percent_per_unit", defaults.castingPercentPerUnit(), 0.0D, ArcanaTuning.hardPercentPerUnit(ModuleKind.CASTING_STABILIZATION));
            CASTING_FE = BUILDER.defineInRange("fe_per_tick", defaults.castingFePerTick(), 0, 1_000_000);
            CASTING_SAVED_FE = BUILDER.defineInRange("saved_time_fe_per_tick", defaults.castingSavedTimeFePerTick(), 0.0D, 10_000.0D);
            BUILDER.pop();
            BUILDER.pop();

            BUILDER.push("disabled_modules");
            DISABLED_MANA = BUILDER.define("mana_conversion", false);
            DISABLED_AMP = BUILDER.define("amplification", false);
            DISABLED_FOCUS = BUILDER.define("focus", false);
            DISABLED_COOLDOWN = BUILDER.define("cooldown_acceleration", false);
            DISABLED_CASTING = BUILDER.define("casting_stabilization", false);
            BUILDER.pop();
            SPEC = BUILDER.build();
        }

        static Optional<ArcanaTuning.Values> loadedValues() {
            if (!SPEC.isLoaded()) return Optional.empty();
            try {
                EnumSet<ModuleKind> disabled = EnumSet.noneOf(ModuleKind.class);
                if (DISABLED_MANA.get()) disabled.add(ModuleKind.MANA_CONVERSION);
                if (DISABLED_AMP.get()) disabled.add(ModuleKind.AMPLIFICATION);
                if (DISABLED_FOCUS.get()) disabled.add(ModuleKind.FOCUS);
                if (DISABLED_COOLDOWN.get()) disabled.add(ModuleKind.COOLDOWN_ACCELERATION);
                if (DISABLED_CASTING.get()) disabled.add(ModuleKind.CASTING_STABILIZATION);

                return Optional.of(new ArcanaTuning.Values(
                        FE_PER_MANA.get(),
                        List.of(0, MAX_MANA_1.get(), MAX_MANA_2.get(), MAX_MANA_3.get(), MAX_MANA_4.get()),
                        List.of(MANA_LOW.get(), MANA_NORMAL.get(), MANA_HIGH.get(), MANA_MAXIMUM.get()),
                        AMP_PERCENT.get(), AMP_FE.get(), FOCUS_PERCENT.get(), FOCUS_FE.get(),
                        COOLDOWN_PERCENT.get(), COOLDOWN_FE.get(), COOLDOWN_SAVED_FE.get(),
                        CASTING_PERCENT.get(), CASTING_FE.get(), CASTING_SAVED_FE.get(),
                        List.of(MANA_MAX_UNITS.get(), AMP_MAX_UNITS.get(), FOCUS_MAX_UNITS.get(),
                                COOLDOWN_MAX_UNITS.get(), CASTING_MAX_UNITS.get()),
                        disabled));
            } catch (IllegalStateException unloadedDuringRead) {
                return Optional.empty();
            }
        }
    }
}
