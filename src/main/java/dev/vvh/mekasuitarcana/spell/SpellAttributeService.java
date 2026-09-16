package dev.vvh.mekasuitarcana.spell;

import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import dev.vvh.mekasuitarcana.balance.ArcanaRates.ModuleKind;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** Applies powered Arcana contributions as idempotent transient entity modifiers. */
public final class SpellAttributeService {
    private static final String NAMESPACE = "mekasuitarcana";
    private static final double AMP_CAP = 200.0D;
    private static final double FOCUS_CAP = 200.0D;
    private static final double COOLDOWN_CAP = 500.0D;
    private static final double CASTING_CAP = 200.0D;
    private static final double FULL_CASTING_MOVESPEED_VALUE = 1.8D;
    private static final ResourceLocation MOVESPEED_PROBE_ID =
            ResourceLocation.fromNamespaceAndPath(NAMESPACE, "movement_probe");
    private static final Set<ResourceLocation> MODIFIER_IDS = buildModifierIds();
    private static final Map<Holder<Attribute>, List<ResourceLocation>> ATTRIBUTE_MODIFIER_IDS =
            buildAttributeModifierIds();

    private SpellAttributeService() {
    }

    /** Compatibility helper for a single carrier. Prefer the list overload at runtime. */
    public static void sync(LivingEntity entity, ArcanaCarrier carrier, ArcanaRates rates) {
        sync(entity, carrier == null ? List.of() : List.of(carrier), rates);
    }

    /**
     * Synchronizes independent carriers. Each carrier is capped before contributions are summed;
     * combined carriers are never passed through an aggregate cap, and each focus school remains
     * attached to its own carrier.
     */
    public static void sync(LivingEntity entity, List<ArcanaCarrier> carriers, ArcanaRates rates) {
        if (entity == null || rates == null) {
            return;
        }
        withdraw(entity);
        if (carriers == null || carriers.isEmpty()) {
            return;
        }

        int maxMana = 0;
        boolean hasCastingCarrier = false;
        for (int index = 0; index < carriers.size(); index++) {
            ArcanaCarrier carrier = carriers.get(index);
            if (carrier == null) {
                continue;
            }
            maxMana = Math.max(maxMana, rates.maxManaForUnits(carrier.manaUnits()));
            double amp = cappedPercent(rates, ModuleKind.AMPLIFICATION, carrier.amplificationUnits(), carrier.amplificationStep(),
                    rates.amplificationPercentPerUnit(), AMP_CAP);
            double focus = cappedPercent(rates, ModuleKind.FOCUS, carrier.focusUnits(), carrier.focusStep(),
                    rates.focusPercentPerUnit(), FOCUS_CAP);
            double cooldown = cappedPercent(rates, ModuleKind.COOLDOWN_ACCELERATION, carrier.cooldownUnits(), carrier.cooldownStep(),
                    rates.cooldownPercentPerUnit(), COOLDOWN_CAP);
            double casting = cappedPercent(rates, ModuleKind.CASTING_STABILIZATION, carrier.castingUnits(), carrier.castingStep(),
                    rates.castingPercentPerUnit(), CASTING_CAP);
            add(entity, AttributeRegistry.SPELL_POWER, modifierId(index, "spell_power"),
                    ArcanaRates.ratingDelta(amp));
            add(entity, carrier.focusSchool().spellPowerAttribute(), modifierId(index, "focus_" + carrier.focusSchool().getSerializedName()),
                    ArcanaRates.ratingDelta(focus));
            add(entity, AttributeRegistry.COOLDOWN_REDUCTION, modifierId(index, "cooldown"),
                    ArcanaRates.ratingDelta(cooldown));
            add(entity, AttributeRegistry.CAST_TIME_REDUCTION, modifierId(index, "casting"),
                    ArcanaRates.ratingDelta(casting));
            hasCastingCarrier |= rates.removesCastingMovementPenalty(carrier.castingUnits());
        }

        if (maxMana > 0) {
            AttributeInstance instance = entity.getAttribute(AttributeRegistry.MAX_MANA);
            if (instance != null) {
                double delta = maxMana - instance.getBaseValue();
                add(entity, AttributeRegistry.MAX_MANA, modifierId(0, "max_mana"), delta);
            }
        }

        if (hasCastingCarrier) {
            AttributeInstance instance = entity.getAttribute(AttributeRegistry.CASTING_MOVESPEED);
            if (instance != null) {
                double delta = requiredAddValue(instance, FULL_CASTING_MOVESPEED_VALUE);
                if (delta > 0.0D) {
                    add(entity, AttributeRegistry.CASTING_MOVESPEED, modifierId(0, "movespeed"), delta);
                }
            }
        }
    }

    /** Removes every modifier owned by this service, including focus-school variants. */
    public static void withdraw(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        for (Map.Entry<Holder<Attribute>, List<ResourceLocation>> entry : ATTRIBUTE_MODIFIER_IDS.entrySet()) {
            Holder<Attribute> attribute = entry.getKey();
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (ResourceLocation id : entry.getValue()) {
                instance.removeModifier(id);
            }
        }
    }

    /** Stable IDs exposed for integration cleanup and diagnostics. */
    public static Set<ResourceLocation> modifierIds() {
        return MODIFIER_IDS;
    }

    private static Set<ResourceLocation> buildModifierIds() {
        Set<ResourceLocation> ids = new HashSet<>();
        for (int carrier = 0; carrier < 3; carrier++) {
            ids.add(modifierId(carrier, "spell_power"));
            ids.add(modifierId(carrier, "cooldown"));
            ids.add(modifierId(carrier, "casting"));
            ids.add(modifierId(carrier, "max_mana"));
            ids.add(modifierId(carrier, "movespeed"));
            for (SpellSchool school : SpellSchool.values()) {
                ids.add(modifierId(carrier, "focus_" + school.getSerializedName()));
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private static Map<Holder<Attribute>, List<ResourceLocation>> buildAttributeModifierIds() {
        Map<Holder<Attribute>, List<ResourceLocation>> ids = new LinkedHashMap<>();
        ids.put(AttributeRegistry.MAX_MANA, idsFor("max_mana"));
        ids.put(AttributeRegistry.SPELL_POWER, idsFor("spell_power"));
        ids.put(AttributeRegistry.COOLDOWN_REDUCTION, idsFor("cooldown"));
        ids.put(AttributeRegistry.CAST_TIME_REDUCTION, idsFor("casting"));
        ids.put(AttributeRegistry.CASTING_MOVESPEED, idsFor("movespeed"));
        for (SpellSchool school : SpellSchool.values()) {
            ids.put(school.spellPowerAttribute(), idsFor("focus_" + school.getSerializedName()));
        }
        return Collections.unmodifiableMap(ids);
    }

    private static List<ResourceLocation> idsFor(String attribute) {
        List<ResourceLocation> ids = new ArrayList<>(3);
        for (int carrier = 0; carrier < 3; carrier++) {
            ids.add(modifierId(carrier, attribute));
        }
        return List.copyOf(ids);
    }

    private static double cappedPercent(ArcanaRates rates, ModuleKind kind, int units, double step,
            double percentPerUnit, double cap) {
        int clampedUnits = rates.clampUnits(kind, units);
        double value = Math.max(0.0D, clampedUnits) * factor(step) * Math.max(0.0D, percentPerUnit);
        return Math.min(cap, value);
    }

    private static double factor(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : 1.0D;
    }

    /**
     * Finds the ADD_VALUE amount needed for the requested final value, accounting for any existing
     * multiplicative modifiers on this attribute. The probe is transient and removed immediately.
     */
    private static double requiredAddValue(AttributeInstance instance, double target) {
        double current = instance.getValue();
        if (!Double.isFinite(current) || current >= target) {
            return 0.0D;
        }
        instance.addOrUpdateTransientModifier(new AttributeModifier(
                MOVESPEED_PROBE_ID, 1.0D, AttributeModifier.Operation.ADD_VALUE));
        double slope = instance.getValue() - current;
        instance.removeModifier(MOVESPEED_PROBE_ID);
        if (!Double.isFinite(slope) || slope <= 0.0D) {
            return target - current;
        }
        return Math.max(0.0D, (target - current) / slope);
    }

    private static void add(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id, double amount) {
        if (amount == 0.0D) {
            return;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static ResourceLocation modifierId(int carrier, String attribute) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, "carrier_" + carrier + "/" + attribute);
    }
}
