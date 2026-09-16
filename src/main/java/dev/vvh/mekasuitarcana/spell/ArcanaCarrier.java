package dev.vvh.mekasuitarcana.spell;

import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import dev.vvh.mekasuitarcana.config.ArcanaConfig;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.ModuleKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mekanism.api.gear.IModule;
import mekanism.api.gear.IModuleContainer;
import mekanism.api.gear.IModuleHelper;
import mekanism.api.gear.config.ModuleConfig;
import mekanism.common.item.gear.ItemMekaTool;
import mekanism.common.registries.MekanismItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Resolved module state for one independently powered carrier. */
public record ArcanaCarrier(
        int manaUnits,
        int amplificationUnits,
        int focusUnits,
        int cooldownUnits,
        int castingUnits,
        ArcanaRates.SpeedPreset manaSpeedPreset,
        SpellSchool focusSchool,
        double amplificationStep,
        double focusStep,
        double cooldownStep,
        double castingStep) {

    private static final String NAMESPACE = "mekasuitarcana";
    private static final ResourceLocation MANA_CONVERSION = id("mana_conversion");
    private static final ResourceLocation AMPLIFICATION = id("amplification");
    private static final ResourceLocation FOCUS = id("focus");
    private static final ResourceLocation COOLDOWN = id("cooldown_acceleration");
    private static final ResourceLocation CASTING = id("casting_stabilization");

    private static final ResourceLocation MANA_SPEED = id("mana_speed_preset");
    private static final ResourceLocation AMPLIFICATION_STEP = id("amplification_step");
    private static final ResourceLocation FOCUS_SCHOOL = id("focus_school");
    private static final ResourceLocation FOCUS_STEP = id("focus_step");
    private static final ResourceLocation COOLDOWN_STEP = id("cooldown_step");
    private static final ResourceLocation CASTING_STEP = id("casting_step");

    public ArcanaCarrier {
        manaUnits = Math.max(0, manaUnits);
        amplificationUnits = Math.max(0, amplificationUnits);
        focusUnits = Math.max(0, focusUnits);
        cooldownUnits = Math.max(0, cooldownUnits);
        castingUnits = Math.max(0, castingUnits);
        manaSpeedPreset = manaSpeedPreset == null ? ArcanaRates.SpeedPreset.NORMAL : manaSpeedPreset;
        focusSchool = focusSchool == null ? SpellSchool.defaultSchool() : focusSchool;
        amplificationStep = factor(amplificationStep);
        focusStep = factor(focusStep);
        cooldownStep = factor(cooldownStep);
        castingStep = factor(castingStep);
    }

    /** Empty state used for unsupported, unpowered, or module-free stacks. */
    public static ArcanaCarrier empty() {
        return new ArcanaCarrier(0, 0, 0, 0, 0, ArcanaRates.SpeedPreset.NORMAL,
                SpellSchool.defaultSchool(), 1.0D, 1.0D, 1.0D, 1.0D);
    }

    /**
     * Reads one carrier without combining it with another stack. {@code tool} selects the
     * Meka-Tool whitelist; false selects MekaSuit Bodyarmor only.
     */
    public static ArcanaCarrier readStack(ItemStack stack, boolean tool) {
        if (stack == null || stack.isEmpty() || !isExpectedCarrier(stack, tool)) {
            return empty();
        }
        IModuleContainer container = IModuleHelper.INSTANCE.getModuleContainer(stack);
        if (container == null || container.modules().isEmpty()) {
            return empty();
        }

        int mana = 0;
        int amplification = 0;
        int focus = 0;
        int cooldown = 0;
        int casting = 0;
        ArcanaRates.SpeedPreset speed = ArcanaRates.SpeedPreset.NORMAL;
        SpellSchool school = SpellSchool.defaultSchool();
        double amplificationStep = 1.0D;
        double focusStep = 1.0D;
        double cooldownStep = 1.0D;
        double castingStep = 1.0D;

        for (IModule<?> module : container.modules()) {
            if (!module.isEnabled() || module.getInstalledCount() <= 0) {
                continue;
            }
            ResourceLocation moduleId = module.getDataHolder().unwrapKey()
                    .map(key -> key.location()).orElse(null);
            if (moduleId == null || !NAMESPACE.equals(moduleId.getNamespace())) {
                continue;
            }
            int count = module.getInstalledCount();
            String path = moduleId.getPath();
            if (MANA_CONVERSION.getPath().equals(path) && !tool) {
                mana += count;
                speed = speed(moduleValue(module, MANA_SPEED));
            } else if (AMPLIFICATION.getPath().equals(path)) {
                amplification += count;
                amplificationStep = factor(moduleValue(module, AMPLIFICATION_STEP));
            } else if (FOCUS.getPath().equals(path)) {
                focus += count;
                school = school(moduleValue(module, FOCUS_SCHOOL));
                focusStep = factor(moduleValue(module, FOCUS_STEP));
            } else if (COOLDOWN.getPath().equals(path) && !tool) {
                cooldown += count;
                cooldownStep = factor(moduleValue(module, COOLDOWN_STEP));
            } else if (CASTING.getPath().equals(path) && !tool) {
                casting += count;
                castingStep = factor(moduleValue(module, CASTING_STEP));
            }
        }

        ArcanaRates rates = ArcanaConfig.rates();
        mana = rates.clampUnits(ModuleKind.MANA_CONVERSION, mana);
        amplification = rates.clampUnits(ModuleKind.AMPLIFICATION, amplification);
        focus = rates.clampUnits(ModuleKind.FOCUS, focus);
        cooldown = rates.clampUnits(ModuleKind.COOLDOWN_ACCELERATION, cooldown);
        casting = rates.clampUnits(ModuleKind.CASTING_STABILIZATION, casting);
        return new ArcanaCarrier(mana, amplification, focus, cooldown, casting, speed, school,
                amplificationStep, focusStep, cooldownStep, castingStep);
    }

    /** The carrier stack plus its resolved state, preserving the carrier boundary for accounting. */
    public record EquippedCarrier(ItemStack stack, ArcanaCarrier carrier) {
    }

    /**
     * Reads bodyarmor and mainhand Meka-Tools independently. Iron staff attribute modifiers use
     * the mainhand equipment group; no casting component, right-click, or casting behavior is added.
     */
    public static List<EquippedCarrier> readEquipped(LivingEntity entity) {
        if (entity == null) {
            return List.of();
        }
        List<EquippedCarrier> result = new ArrayList<>(3);
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        addIfPresent(result, chest, false);
        addToolIfHeldInStaffSlot(result, entity.getMainHandItem());
        return List.copyOf(result);
    }

    /** Convenience aggregate. The list overload is authoritative when multiple schools/carriers exist. */
    public static Optional<ArcanaCarrier> read(LivingEntity entity) {
        List<EquippedCarrier> equipped = readEquipped(entity);
        if (equipped.isEmpty()) {
            return Optional.empty();
        }
        ArcanaCarrier first = equipped.get(0).carrier();
        int mana = 0;
        int amplification = 0;
        int focus = 0;
        int cooldown = 0;
        int casting = 0;
        double ampStep = 0.0D;
        double focusStep = 0.0D;
        double cooldownStep = 0.0D;
        double castingStep = 0.0D;
        int focusStepUnits = 0;
        int ampStepUnits = 0;
        int cooldownStepUnits = 0;
        int castingStepUnits = 0;
        for (EquippedCarrier equippedCarrier : equipped) {
            ArcanaCarrier carrier = equippedCarrier.carrier();
            mana += carrier.manaUnits();
            amplification += carrier.amplificationUnits();
            focus += carrier.focusUnits();
            cooldown += carrier.cooldownUnits();
            casting += carrier.castingUnits();
            ampStep += carrier.amplificationUnits() * carrier.amplificationStep();
            focusStep += carrier.focusUnits() * carrier.focusStep();
            cooldownStep += carrier.cooldownUnits() * carrier.cooldownStep();
            castingStep += carrier.castingUnits() * carrier.castingStep();
            ampStepUnits += carrier.amplificationUnits();
            focusStepUnits += carrier.focusUnits();
            cooldownStepUnits += carrier.cooldownUnits();
            castingStepUnits += carrier.castingUnits();
        }
        return Optional.of(new ArcanaCarrier(mana, amplification, focus, cooldown, casting,
                first.manaSpeedPreset(), first.focusSchool(), weighted(ampStep, ampStepUnits),
                weighted(focusStep, focusStepUnits), weighted(cooldownStep, cooldownStepUnits),
                weighted(castingStep, castingStepUnits)));
    }

    private static void addIfPresent(List<EquippedCarrier> result, ItemStack stack, boolean tool) {
        ArcanaCarrier carrier = readStack(stack, tool);
        if (!carrier.isEmpty()) {
            result.add(new EquippedCarrier(stack, carrier));
        }
    }

    private static void addToolIfHeldInStaffSlot(List<EquippedCarrier> result, ItemStack stack) {
        if (stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemMekaTool
                ) {
            addIfPresent(result, stack, true);
        }
    }

    private static boolean isExpectedCarrier(ItemStack stack, boolean tool) {
        return tool ? stack.getItem() instanceof ItemMekaTool : stack.is(MekanismItems.MEKASUIT_BODYARMOR);
    }

    private boolean isEmpty() {
        return manaUnits == 0 && amplificationUnits == 0 && focusUnits == 0
                && cooldownUnits == 0 && castingUnits == 0;
    }

    private static Object moduleValue(IModule<?> module, ResourceLocation key) {
        ModuleConfig<?> config = module.getConfig(key);
        return config == null ? null : config.get();
    }

    private static ArcanaRates.SpeedPreset speed(Object value) {
        if (value instanceof ArcanaRates.SpeedPreset preset) {
            return preset;
        }
        if (value instanceof Enum<?> enumValue) {
            ArcanaRates.SpeedPreset[] values = ArcanaRates.SpeedPreset.values();
            return values[Math.min(enumValue.ordinal(), values.length - 1)];
        }
        return ArcanaRates.SpeedPreset.NORMAL;
    }

    private static SpellSchool school(Object value) {
        if (value instanceof SpellSchool selected) {
            return selected;
        }
        if (value instanceof Enum<?> enumValue) {
            try {
                return SpellSchool.valueOf(enumValue.name());
            } catch (IllegalArgumentException ignored) {
                // A malformed setting falls back to the stable default.
            }
        }
        return SpellSchool.defaultSchool();
    }

    private static double factor(Object value) {
        if (value instanceof Number number) {
            return factor(number.doubleValue());
        }
        if (value instanceof Enum<?> enumValue) {
            int last = Math.max(1, enumValue.getDeclaringClass().getEnumConstants().length - 1);
            return factor((double) enumValue.ordinal() / last);
        }
        return 1.0D;
    }

    private static double factor(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : 1.0D;
    }

    private static double weighted(double sum, int units) {
        return units == 0 ? 1.0D : factor(sum / units);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
}
