package dev.vvh.mekasuitarcana;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import dev.vvh.mekasuitarcana.config.ArcanaConfig;
import dev.vvh.mekasuitarcana.registry.ArcanaModules;
import dev.vvh.mekasuitarcana.runtime.ArcanaRuntime;
import net.neoforged.neoforge.common.NeoForge;

/**
 * MekaSuit Arcana - FE-powered Mekanism MekaSuit modules for Iron's Spellbooks.
 *
 * <p>Design boundary: the MekaSuit stays ordinary armor. It stores no spells, has no inscription
 * support, and adds no casting interface. The addon only detects installed modules, applies Iron's
 * spell attributes while they are powered, converts suit FE into the player's own mana pool, and
 * charges FE for enhanced spell performance. Iron's Spellbooks remains authoritative over the
 * spellbook, the spell wheel, mana, cooldowns, and spell casting.</p>
 */
@Mod(MekaSuitArcana.MODID)
public final class MekaSuitArcana {

    public static final String MODID = "mekasuitarcana";

    public MekaSuitArcana(IEventBus modBus, ModContainer container) {
        ArcanaConfig.register(container);
        ArcanaModules.register(modBus);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(ArcanaModules::registerInterModComms));
        ArcanaRuntime.register(NeoForge.EVENT_BUS);
    }
}
