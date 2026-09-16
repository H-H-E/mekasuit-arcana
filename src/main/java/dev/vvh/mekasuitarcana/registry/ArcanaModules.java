package dev.vvh.mekasuitarcana.registry;

import dev.vvh.mekasuitarcana.MekaSuitArcana;
import dev.vvh.mekasuitarcana.module.AmplificationUnit;
import dev.vvh.mekasuitarcana.module.CastingStabilizationUnit;
import dev.vvh.mekasuitarcana.module.CooldownAccelerationUnit;
import dev.vvh.mekasuitarcana.module.FocusUnit;
import dev.vvh.mekasuitarcana.module.ManaConversionUnit;
import dev.vvh.mekasuitarcana.spell.SpellSchool;
import java.util.function.Supplier;
import mekanism.api.MekanismAPI;
import mekanism.api.MekanismIMC;
import mekanism.api.gear.ModuleData;
import mekanism.api.gear.ModuleData.ModuleDataBuilder;
import mekanism.api.gear.IModuleHelper;
import mekanism.api.gear.config.ModuleEnumConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Module data, module items, and Mekanism's carrier IMC for the five in-scope units. */
public final class ArcanaModules {

    public static final DeferredRegister<ModuleData<?>> MODULE_DATA =
            DeferredRegister.create(MekanismAPI.MODULE_REGISTRY_NAME, MekaSuitArcana.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MekaSuitArcana.MODID);

    public static final DeferredHolder<Item, Item> MANA_CONVERSION_ITEM = ITEMS.register(
            "mana_conversion_unit", () -> createModuleItem(() -> widen(manaConversionHolder())));
    public static final DeferredHolder<Item, Item> AMPLIFICATION_ITEM = ITEMS.register(
            "amplification_unit", () -> createModuleItem(() -> widen(amplificationHolder())));
    public static final DeferredHolder<Item, Item> FOCUS_ITEM = ITEMS.register(
            "focus_unit", () -> createModuleItem(() -> widen(focusHolder())));
    public static final DeferredHolder<Item, Item> COOLDOWN_ACCELERATION_ITEM = ITEMS.register(
            "cooldown_acceleration_unit", () -> createModuleItem(() -> widen(cooldownAccelerationHolder())));
    public static final DeferredHolder<Item, Item> CASTING_STABILIZATION_ITEM = ITEMS.register(
            "casting_stabilization_unit", () -> createModuleItem(() -> widen(castingStabilizationHolder())));

    public static final DeferredHolder<ModuleData<?>, ModuleData<ManaConversionUnit>> MANA_CONVERSION =
            MODULE_DATA.register("mana_conversion", () -> new ModuleData<>(
                    ModuleDataBuilder.<ManaConversionUnit>custom(module -> new ManaConversionUnit(), MANA_CONVERSION_ITEM)
                            .maxStackSize(4)
                            .addConfig(ModuleEnumConfig.create(ArcanaConfigKeys.MANA_SPEED_PRESET, ManaSpeedPreset.MAXIMUM),
                                    ModuleEnumConfig.codec(ManaSpeedPreset.CODEC),
                                    ModuleEnumConfig.streamCodec(ManaSpeedPreset.STREAM_CODEC))));

    public static final DeferredHolder<ModuleData<?>, ModuleData<AmplificationUnit>> AMPLIFICATION =
            MODULE_DATA.register("amplification", () -> new ModuleData<>(
                    ModuleDataBuilder.<AmplificationUnit>custom(module -> new AmplificationUnit(), AMPLIFICATION_ITEM)
                            .maxStackSize(4)
                            .addConfig(ModuleEnumConfig.create(ArcanaConfigKeys.AMPLIFICATION_STEP, ArcanaStep.FULL),
                                    ModuleEnumConfig.codec(ArcanaStep.CODEC),
                                    ModuleEnumConfig.streamCodec(ArcanaStep.STREAM_CODEC))));

    public static final DeferredHolder<ModuleData<?>, ModuleData<FocusUnit>> FOCUS =
            MODULE_DATA.register("focus", () -> new ModuleData<>(
                    ModuleDataBuilder.<FocusUnit>custom(module -> new FocusUnit(), FOCUS_ITEM)
                            .maxStackSize(4)
                            .addConfig(ModuleEnumConfig.create(ArcanaConfigKeys.FOCUS_SCHOOL, SpellSchool.defaultSchool()),
                                    ModuleEnumConfig.codec(SpellSchool.CODEC),
                                    ModuleEnumConfig.streamCodec(SpellSchool.STREAM_CODEC))
                            .addConfig(ModuleEnumConfig.create(ArcanaConfigKeys.FOCUS_STEP, ArcanaStep.FULL),
                                    ModuleEnumConfig.codec(ArcanaStep.CODEC),
                                    ModuleEnumConfig.streamCodec(ArcanaStep.STREAM_CODEC))));

    public static final DeferredHolder<ModuleData<?>, ModuleData<CooldownAccelerationUnit>> COOLDOWN_ACCELERATION =
            MODULE_DATA.register("cooldown_acceleration", () -> new ModuleData<>(
                    ModuleDataBuilder.<CooldownAccelerationUnit>custom(module -> new CooldownAccelerationUnit(), COOLDOWN_ACCELERATION_ITEM)
                            .maxStackSize(5)
                            .addConfig(ModuleEnumConfig.create(ArcanaConfigKeys.COOLDOWN_STEP, ArcanaStep.FULL),
                                    ModuleEnumConfig.codec(ArcanaStep.CODEC),
                                    ModuleEnumConfig.streamCodec(ArcanaStep.STREAM_CODEC))));

    public static final DeferredHolder<ModuleData<?>, ModuleData<CastingStabilizationUnit>> CASTING_STABILIZATION =
            MODULE_DATA.register("casting_stabilization", () -> new ModuleData<>(
                    ModuleDataBuilder.<CastingStabilizationUnit>custom(module -> new CastingStabilizationUnit(), CASTING_STABILIZATION_ITEM)
                            .maxStackSize(4)
                            .addConfig(ModuleEnumConfig.create(ArcanaConfigKeys.CASTING_STEP, ArcanaStep.FULL),
                                    ModuleEnumConfig.codec(ArcanaStep.CODEC),
                                    ModuleEnumConfig.streamCodec(ArcanaStep.STREAM_CODEC))));

    private ArcanaModules() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        MODULE_DATA.register(modBus);
        modBus.addListener(ArcanaModules::addCreativeTabContents);
    }

    /** Adds all craftable units to vanilla's tools/utilities tab without creating another tab. */
    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (!CreativeModeTabs.TOOLS_AND_UTILITIES.equals(event.getTabKey())) {
            return;
        }
        event.accept(new ItemStack(MANA_CONVERSION_ITEM.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(AMPLIFICATION_ITEM.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(FOCUS_ITEM.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(COOLDOWN_ACCELERATION_ITEM.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(CASTING_STABILIZATION_ITEM.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }

    /** Called from common setup through {@code enqueueWork}, before Mekanism processes IMC. */
    public static void registerInterModComms() {
        MekanismIMC.addMekaSuitBodyarmorModules(widen(MANA_CONVERSION));
        MekanismIMC.addMekaSuitBodyarmorModules(widen(AMPLIFICATION));
        MekanismIMC.addMekaToolModules(widen(AMPLIFICATION));
        MekanismIMC.addMekaSuitBodyarmorModules(widen(FOCUS));
        MekanismIMC.addMekaToolModules(widen(FOCUS));
        MekanismIMC.addMekaSuitBodyarmorModules(widen(COOLDOWN_ACCELERATION));
        MekanismIMC.addMekaSuitBodyarmorModules(widen(CASTING_STABILIZATION));
    }

    private static Item createModuleItem(Supplier<Holder<ModuleData<?>>> module) {
        return IModuleHelper.INSTANCE.createModuleItem(module, new Item.Properties());
    }

    private static Holder<? extends ModuleData<?>> manaConversionHolder() {
        return MANA_CONVERSION;
    }

    private static Holder<? extends ModuleData<?>> amplificationHolder() {
        return AMPLIFICATION;
    }

    private static Holder<? extends ModuleData<?>> focusHolder() {
        return FOCUS;
    }

    private static Holder<? extends ModuleData<?>> cooldownAccelerationHolder() {
        return COOLDOWN_ACCELERATION;
    }

    private static Holder<? extends ModuleData<?>> castingStabilizationHolder() {
        return CASTING_STABILIZATION;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Holder<ModuleData<?>> widen(Holder<? extends ModuleData<?>> holder) {
        return (Holder) holder;
    }
}
