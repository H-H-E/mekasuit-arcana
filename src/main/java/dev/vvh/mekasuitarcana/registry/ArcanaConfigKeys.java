package dev.vvh.mekasuitarcana.registry;

import dev.vvh.mekasuitarcana.MekaSuitArcana;
import net.minecraft.resources.ResourceLocation;

/** Stable names for the player-facing Mekanism Module Tweaker settings. */
public final class ArcanaConfigKeys {

    public static final ResourceLocation MANA_SPEED_PRESET = key("mana_speed_preset");
    public static final ResourceLocation AMPLIFICATION_STEP = key("amplification_step");
    public static final ResourceLocation FOCUS_SCHOOL = key("focus_school");
    public static final ResourceLocation FOCUS_STEP = key("focus_step");
    public static final ResourceLocation COOLDOWN_STEP = key("cooldown_step");
    public static final ResourceLocation CASTING_STEP = key("casting_step");

    private ArcanaConfigKeys() {
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath(MekaSuitArcana.MODID, path);
    }
}
