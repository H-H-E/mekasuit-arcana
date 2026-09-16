package dev.vvh.mekasuitarcana.runtime;

import dev.vvh.mekasuitarcana.energy.ArcanaEnergy;
import dev.vvh.mekasuitarcana.mana.ManaBridge;
import dev.vvh.mekasuitarcana.mana.ManaBridge.ManaState;
import dev.vvh.mekasuitarcana.registry.ArcanaModules;
import dev.vvh.mekasuitarcana.spell.ArcanaCarrier;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import mekanism.common.attachments.containers.energy.AttachedEnergy;
import mekanism.common.content.gear.ModuleContainer;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.api.gear.IModuleHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Development-only probes for the real production runtime path.
 *
 * <p>The automatic path uses the real Mekanism module-container API on a NeoForge
 * {@link FakePlayer}. It is a server-thread integration probe, not human-client
 * evidence. The manual methods remain available for a connected-player run.</p>
 */
public final class RuntimeVerificationHarness {
    private static final int EXPECTED_BODY_AMPLIFICATION_CAP = 4;
    private static final int EXPECTED_TOOL_AMPLIFICATION_CAP = 4;

    private RuntimeVerificationHarness() {}

    public static Snapshot snapshot(ServerPlayer player) {
        ArcanaRuntime.refresh(player);
        return readSnapshot(player);
    }

    public static Snapshot tick(ServerPlayer player, int ticks) {
        int bounded = Math.max(0, Math.min(200, ticks));
        for (int i = 0; i < bounded; i++) {
            ArcanaRuntime.tick(player);
        }
        return readSnapshot(player);
    }

    /**
     * Exercise the same per-carrier cast payment method used by the event listener.
     * A native spell cast remains the preferred proof; this probe is useful for
     * isolating payment and cap behavior when a fixture cannot learn a spell.
     */
    public static PaymentResult invokeCastPayment(ServerPlayer player, String schoolId, int manaCost) {
        Snapshot before = readSnapshot(player);
        var school = SchoolRegistry.getSchool(ResourceLocation.parse(schoolId));
        if (school == null) {
            school = SchoolRegistry.FIRE.get();
        }
        ArcanaRuntime.onCast(new SpellOnCastEvent(
                player, "irons_spellbooks:magic_arrow", 1, Math.max(0, manaCost), school, CastSource.SPELLBOOK));
        Snapshot after = readSnapshot(player);
        return new PaymentResult(before, after, before.totalEnergy() - after.totalEnergy(), school.getId().toString());
    }

    /**
     * Use Iron's public spell API. The method intentionally reports an in-progress
     * cast instead of pretending that attemptInitiateCast completed it. Call
     * finishNativeCast after the real cast duration has elapsed.
     */
    public static NativeSpellResult attemptNativeCast(ServerPlayer player, String spellId, int level) {
        AbstractSpell spell = resolveSpell(spellId);
        if (spell == null) {
            return NativeSpellResult.unavailable(spellId, "spell is not registered");
        }
        int spellLevel = Math.max(spell.getMinLevel(), Math.min(level, spell.getMaxLevel()));
        boolean initiated = spell.attemptInitiateCast(
                player.getMainHandItem(), spellLevel, player.level(), player,
                CastSource.SPELLBOOK, true, "mainhand");
        MagicData magic = MagicData.getPlayerMagicData(player);
        return new NativeSpellResult(
                spell.getSpellId(), initiated, false, magic.isCasting(),
                magic.getCastDurationRemaining(), "attemptInitiateCast -> "
                        + (magic.isCasting() ? "cast pending" : "already complete; no second castSpell call"));
    }

    public static NativeSpellResult finishNativeCast(ServerPlayer player, String spellId, int level) {
        AbstractSpell spell = resolveSpell(spellId);
        if (spell == null) {
            return NativeSpellResult.unavailable(spellId, "spell is not registered");
        }
        int spellLevel = Math.max(spell.getMinLevel(), Math.min(level, spell.getMaxLevel()));
        MagicData magic = MagicData.getPlayerMagicData(player);
        if (!magic.isCasting()) {
            return new NativeSpellResult(spell.getSpellId(), false, false, false, 0,
                    "no native cast was pending");
        }
        spell.castSpell(player.level(), spellLevel, player, CastSource.SPELLBOOK, true);
        return new NativeSpellResult(spell.getSpellId(), true, true, magic.isCasting(),
                magic.getCastDurationRemaining(), "castSpell");
    }

    public static CapCheck checkAmplificationCaps(Snapshot bodyOnly, Snapshot toolOnly, Snapshot both) {
        boolean body = bodyOnly.amplificationUnits() == EXPECTED_BODY_AMPLIFICATION_CAP;
        boolean tool = toolOnly.amplificationUnits() == EXPECTED_TOOL_AMPLIFICATION_CAP;
        boolean combined = both.amplificationUnits()
                == EXPECTED_BODY_AMPLIFICATION_CAP + EXPECTED_TOOL_AMPLIFICATION_CAP;
        return new CapCheck(body && tool && combined, bodyOnly.amplificationUnits(),
                toolOnly.amplificationUnits(), both.amplificationUnits(),
                "caps are checked from ArcanaCarrier's resolved, per-carrier aggregate");
    }

    public static Check poweredDelta(Snapshot before, Snapshot after, double expectedMaxMana) {
        boolean maxMana = after.maxMana() + 0.001D >= expectedMaxMana;
        boolean energy = after.totalEnergy() < before.totalEnergy();
        boolean mana = after.manaCurrent() > before.manaCurrent() && after.manaCurrent() <= after.manaMax() + 0.001D;
        return new Check(maxMana && energy && mana,
                "maxMana=" + before.maxMana() + "->" + after.maxMana()
                        + ", mana=" + before.manaCurrent() + "->" + after.manaCurrent()
                        + ", FE=" + before.totalEnergy() + "->" + after.totalEnergy());
    }

    public static Check noDrySuitBuff(Snapshot unpowered, Snapshot absent) {
        boolean maxMana = nearlyEqual(unpowered.maxMana(), absent.maxMana());
        boolean spellPower = nearlyEqual(unpowered.spellPower(), absent.spellPower());
        boolean cooldown = nearlyEqual(unpowered.cooldownReduction(), absent.cooldownReduction());
        boolean castTime = nearlyEqual(unpowered.castTimeReduction(), absent.castTimeReduction());
        return new Check(maxMana && spellPower && cooldown && castTime,
                "unpowered=" + unpowered.attributes() + ", absent=" + absent.attributes());
    }

    public static Check serverCaps() {
        var rates = dev.vvh.mekasuitarcana.config.ArcanaConfig.rates();
        var kinds = dev.vvh.mekasuitarcana.balance.ArcanaRates.ModuleKind.values();
        int[] expected = {4, 4, 4, 5, 4};
        boolean passed = true;
        StringBuilder detail = new StringBuilder("caps=");
        for (int i = 0; i < expected.length; i++) {
            var kind = kinds[i];
            int configured = rates.maxUnits(kind);
            int effective = dev.vvh.mekasuitarcana.config.ArcanaConfig.isModuleEnabled(kind) ? configured : 0;
            passed &= effective >= 0 && effective <= expected[i]
                    && (dev.vvh.mekasuitarcana.config.ArcanaConfig.isModuleEnabled(kind) || effective == 0);
            detail.append(kind).append(':').append(effective).append('/').append(expected[i]).append(' ');
        }
        boolean manaCurve = !dev.vvh.mekasuitarcana.config.ArcanaConfig.isModuleEnabled(kinds[0])
                ? rates.maxManaForUnits(1) == 0 && rates.maxManaForUnits(4) == 0
                : rates.maxManaForUnits(1) == 1_000 && rates.maxManaForUnits(4) == 10_000;
        return new Check(passed && manaCurve, detail.append("manaCurve=")
                .append(rates.maxManaForUnits(1)).append('/').append(rates.maxManaForUnits(4)).toString());
    }

    /** Confirms Mekanism received the carrier whitelist through the native IMC path. */
    public static Check moduleSupport() {
        var body = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:mekasuit_bodyarmor"));
        var tool = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:meka_tool"));
        var helmet = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:mekasuit_helmet"));
        boolean bodyAmp = IModuleHelper.INSTANCE.getSupported(body).contains(ArcanaModules.AMPLIFICATION.get());
        boolean toolAmp = IModuleHelper.INSTANCE.getSupported(tool).contains(ArcanaModules.AMPLIFICATION.get());
        boolean badSlot = !IModuleHelper.INSTANCE.getSupported(helmet).contains(ArcanaModules.AMPLIFICATION.get());
        return new Check(bodyAmp && toolAmp && badSlot,
                "bodyAmp=" + bodyAmp + ", toolAmp=" + toolAmp + ", helmetRejected=" + badSlot);
    }

    /** Run a complete disposable-server probe with a deterministic fake player. */
    public static AutoReport runAutomatic(MinecraftServer server) {
        FakePlayer player = FakePlayerFactory.get(server.overworld(),
                new GameProfile(UUID.fromString("c0d3e7a1-9b4c-4a1c-8f9e-0d2e6b7a4c11"), "ArcanaRuntimeProbe"));
        if (player.getHealth() <= 0.0F) {
            player.setHealth(player.getMaxHealth());
        }
        MagicData magic = MagicData.getPlayerMagicData(player);
        magic.setServerPlayer(player);
        magic.setSyncedData(new io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData(player));
        CarrierSetup body = createInstalledCarrier(player, "mekanism:mekasuit_bodyarmor", 1, 5);
        CarrierSetup tool = createInstalledCarrier(player, "mekanism:meka_tool", 0, 5);
        player.setItemSlot(EquipmentSlot.CHEST, body.stack());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool.stack());

        Snapshot before = snapshot(player);
        Snapshot after = tick(player, 20);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Snapshot bodyOnly = snapshot(player);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool.stack());
        Snapshot both = snapshot(player);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        Snapshot toolOnly = snapshot(player);
        player.setItemSlot(EquipmentSlot.CHEST, body.stack());
        PaymentResult payment = invokeCastPayment(player, "irons_spellbooks:fire", 20);
        Check powered = poweredDelta(before, after, 1_000D);
        CapCheck caps = checkAmplificationCaps(bodyOnly, toolOnly, both);
        Check configCaps = serverCaps();
        Check support = moduleSupport();

        player.setItemSlot(EquipmentSlot.CHEST, zeroEnergy(player.getItemBySlot(EquipmentSlot.CHEST)));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, zeroEnergy(player.getMainHandItem()));
        Snapshot unpowered = snapshot(player);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Snapshot absent = snapshot(player);
        Check noDryBuff = noDrySuitBuff(unpowered, absent);

        Check install = new Check(body.amplificationInstalled() == 4 && body.manaInstalled() == 1
                        && tool.amplificationInstalled() == 4,
                "body amp/mana=" + body.amplificationInstalled() + "/" + body.manaInstalled()
                        + ", tool amp=" + tool.amplificationInstalled());
        return new AutoReport(true, install, support, powered, payment.energySpent() > 0,
                caps, configCaps, noDryBuff, before, after, payment, unpowered, absent,
                "synthetic fake-player integration; no connected-client or GUI proof; native spell attempt untested");
    }

    private static CarrierSetup createInstalledCarrier(ServerPlayer player, String itemId, int manaUnits, int amplificationUnits) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)));
        ModuleContainer container = (ModuleContainer) IModuleHelper.INSTANCE.getModuleContainer(stack);
        int installedMana = 0;
        int installedAmplification = 0;
        if (manaUnits > 0) {
            installedMana = container.addModule(player.registryAccess(), stack, ArcanaModules.MANA_CONVERSION, manaUnits);
            container = (ModuleContainer) IModuleHelper.INSTANCE.getModuleContainer(stack);
        }
        if (amplificationUnits > 0) {
            installedAmplification = container.addModule(player.registryAccess(), stack, ArcanaModules.AMPLIFICATION, amplificationUnits);
            container = (ModuleContainer) IModuleHelper.INSTANCE.getModuleContainer(stack);
        }
        stack.set(MekanismDataComponents.MODULE_CONTAINER.get(), container);
        AttachedEnergy current = stack.get(MekanismDataComponents.ATTACHED_ENERGY.get());
        int count = current == null || current.containers().isEmpty() ? 1 : current.containers().size();
        stack.set(MekanismDataComponents.ATTACHED_ENERGY.get(),
                new AttachedEnergy(java.util.stream.IntStream.range(0, count).mapToObj(ignored -> 4_000_000L).toList()));
        return new CarrierSetup(stack, installedMana, installedAmplification);
    }

    private static ItemStack zeroEnergy(ItemStack original) {
        AttachedEnergy current = original.get(MekanismDataComponents.ATTACHED_ENERGY.get());
        if (current == null) return original;
        ItemStack copy = original.copyWithCount(1);
        copy.set(MekanismDataComponents.ATTACHED_ENERGY.get(),
                new AttachedEnergy(current.containers().stream().map(ignored -> 0L).toList()));
        return copy;
    }

    private static Snapshot readSnapshot(ServerPlayer player) {
        Optional<ArcanaCarrier> carrier = ArcanaCarrier.read(player);
        var mana = ManaBridge.read(player);
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack mainHand = player.getMainHandItem();
        return new Snapshot(
                ArcanaEnergy.stored(chest), ArcanaEnergy.stored(mainHand),
                mana.map(ManaState::current).orElse(Double.NaN),
                mana.map(ManaState::max).orElse(Double.NaN),
                player.getAttributeValue(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.MAX_MANA),
                player.getAttributeValue(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.SPELL_POWER),
                player.getAttributeValue(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.COOLDOWN_REDUCTION),
                player.getAttributeValue(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.CAST_TIME_REDUCTION),
                carrier.map(ArcanaCarrier::manaUnits).orElse(0),
                carrier.map(ArcanaCarrier::amplificationUnits).orElse(0),
                carrier.map(ArcanaCarrier::focusUnits).orElse(0),
                carrier.map(ArcanaCarrier::cooldownUnits).orElse(0),
                carrier.map(ArcanaCarrier::castingUnits).orElse(0));
    }

    private static AbstractSpell resolveSpell(String spellId) {
        if (spellId == null || spellId.isBlank()) return null;
        try {
            return SpellRegistry.getSpell(ResourceLocation.parse(spellId));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean nearlyEqual(double left, double right) {
        return Double.isNaN(left) ? Double.isNaN(right) : Math.abs(left - right) < 0.0001D;
    }

    public record Snapshot(long chestEnergy, long mainHandEnergy, double manaCurrent, double manaMax,
            double maxMana, double spellPower, double cooldownReduction, double castTimeReduction,
            int manaUnits, int amplificationUnits, int focusUnits, int cooldownUnits, int castingUnits) {
        public long totalEnergy() { return chestEnergy + mainHandEnergy; }
        public String attributes() {
            return "maxMana=" + maxMana + ", spellPower=" + spellPower
                    + ", cooldown=" + cooldownReduction + ", castTime=" + castTimeReduction;
        }
        @Override public String toString() {
            return "Snapshot{" + attributes() + ", mana=" + manaCurrent + "/" + manaMax
                    + ", FE=" + totalEnergy() + " (chest=" + chestEnergy + ", hand=" + mainHandEnergy
                    + "), units=" + manaUnits + "/" + amplificationUnits + "/" + focusUnits
                    + "/" + cooldownUnits + "/" + castingUnits + "}";
        }
    }

    public record PaymentResult(Snapshot before, Snapshot after, long energySpent, String school) {}
    public record Check(boolean passed, String detail) {}
    public record CapCheck(boolean passed, int bodyUnits, int toolUnits, int combinedUnits, String detail) {}
    public record NativeSpellResult(String spellId, boolean initiated, boolean completed,
            boolean stillCasting, int remainingTicks, String detail) {
        static NativeSpellResult unavailable(String spellId, String detail) {
            return new NativeSpellResult(spellId, false, false, false, 0, detail);
        }
    }
    public record AutoReport(boolean syntheticFakePlayer, Check installationPath, Check moduleSupport,
            Check poweredManaAndEnergy, boolean castPaymentCharged, CapCheck amplificationCaps, Check serverCaps,
            Check noDrySuitBuff, Snapshot before, Snapshot after, PaymentResult payment,
            Snapshot unpowered, Snapshot absent, String proofBoundary) {}

    private record CarrierSetup(ItemStack stack, int manaInstalled, int amplificationInstalled) {}
}
