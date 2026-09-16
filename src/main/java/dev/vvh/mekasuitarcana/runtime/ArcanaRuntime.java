package dev.vvh.mekasuitarcana.runtime;

import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import dev.vvh.mekasuitarcana.balance.ArcanaTiming;
import dev.vvh.mekasuitarcana.config.ArcanaConfig;
import dev.vvh.mekasuitarcana.energy.ArcanaEnergy;
import dev.vvh.mekasuitarcana.mana.ManaBridge;
import dev.vvh.mekasuitarcana.spell.ArcanaCarrier;
import dev.vvh.mekasuitarcana.spell.SpellAttributeService;
import io.redspace.ironsspellbooks.api.events.SpellCooldownAddedEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.capabilities.magic.CooldownInstance;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.network.casting.UpdateCastingStatePacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server authority for addon effects. Iron's still owns spells, mana and both timers.
 * Acceleration buys extra progress on those existing timers each tick. This makes
 * unequipping or running out of FE stop future acceleration without canceling a cast.
 */
public final class ArcanaRuntime {
    private static final Map<UUID, State> STATES = new HashMap<>();

    private ArcanaRuntime() {}

    public static void register(IEventBus bus) {
        bus.addListener(ArcanaRuntime::onTick);
        bus.addListener(EventPriority.LOWEST, ArcanaRuntime::onPreCast);
        bus.addListener(EventPriority.LOWEST, ArcanaRuntime::onCast);
        bus.addListener(EventPriority.LOWEST, ArcanaRuntime::onCooldown);
        bus.addListener(ArcanaRuntime::onLogout);
        bus.addListener(ArcanaRuntime::onClone);
        bus.addListener(ArcanaRuntime::onDimension);
        bus.addListener(ArcanaRuntime::onDeath);
        bus.addListener(ArcanaRuntime::onServerStopped);
    }

    private static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) tick(player);
    }

    /** Public so the separate development harness can exercise the production path. */
    public static void tick(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            clear(player);
            return;
        }
        ArcanaRates rates = ArcanaConfig.rates();
        var equipped = ArcanaCarrier.readEquipped(player);
        if (equipped.isEmpty()) {
            clear(player);
            return;
        }
        SpellAttributeService.withdraw(player);
        double baseCooldown = player.getAttributeValue(AttributeRegistry.COOLDOWN_REDUCTION);
        double baseCasting = player.getAttributeValue(AttributeRegistry.CAST_TIME_REDUCTION);
        List<ArcanaCarrier> candidates = new ArrayList<>();
        for (var carrier : equipped) candidates.add(powered(carrier.carrier(), carrier.stack(), rates));
        SpellAttributeService.sync(player, candidates, rates);
        // Read the actual marginal values, including vanilla multiplicative modifiers from
        // other equipment and native attribute bounds, rather than guessing from unit counts.
        double cooldownDelta = Math.max(0,
                player.getAttributeValue(AttributeRegistry.COOLDOWN_REDUCTION) - baseCooldown);
        double castingDelta = Math.max(0,
                player.getAttributeValue(AttributeRegistry.CAST_TIME_REDUCTION) - baseCasting);
        MagicData magic = MagicData.getPlayerMagicData(player);
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        state.cooldownCarry.keySet().retainAll(magic.getPlayerCooldowns().getSpellCooldowns().values());
        if (!magic.isCasting()) {
            state.castCarry = 0;
            state.preparedSpell = "";
        }
        List<ArcanaCarrier> effects = new ArrayList<>();
        for (var equippedCarrier : equipped) {
            ItemStack stack = equippedCarrier.stack();
            ArcanaCarrier carrier = powered(equippedCarrier.carrier(), stack, rates);
            boolean cooldownPaid = true;
            boolean castingPaid = true;
            if (carrier.cooldownUnits() > 0 && carrier.cooldownStep() > 0) {
                cooldownPaid = accelerateCooldowns(player, stack, carrier, rates, state, baseCooldown, cooldownDelta);
            }
            if (carrier.castingUnits() > 0 && magic.isCasting()) {
                castingPaid = accelerateCasting(player, stack, carrier, rates, state, baseCasting, castingDelta);
            }
            effects.add(filtered(carrier, carrier.amplificationUnits(), carrier.focusUnits(),
                    cooldownPaid ? carrier.cooldownUnits() : 0,
                    castingPaid ? carrier.castingUnits() : 0,
                    magic.getCastType() == CastType.CONTINUOUS ? 0 : carrier.castingStep()));
        }
        SpellAttributeService.sync(player, effects, rates);
        // Mana is the lowest priority load: active casting/cooldowns have already paid.
        for (var equippedCarrier : equipped) {
            restoreMana(player, equippedCarrier.stack(), equippedCarrier.carrier(), rates);
        }
        clampMana(player);
    }

    /** Reconcile equipment immediately at spell boundaries, closing the unequip-between-ticks gap. */
    public static void refresh(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            clear(player);
            return;
        }
        ArcanaRates rates = ArcanaConfig.rates();
        List<ArcanaCarrier> effects = new ArrayList<>();
        for (var carrier : ArcanaCarrier.readEquipped(player)) {
            effects.add(powered(carrier.carrier(), carrier.stack(), rates));
        }
        SpellAttributeService.sync(player, effects, rates);
        clampMana(player);
    }

    public static void onPreCast(SpellPreCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        if (player.isSpectator()) {
            clear(player);
            return;
        }
        ArcanaRates rates = ArcanaConfig.rates();
        List<ArcanaCarrier> effects = new ArrayList<>();
        for (var equipped : ArcanaCarrier.readEquipped(player)) {
            ArcanaCarrier c = powered(equipped.carrier(), equipped.stack(), rates);
            // Iron's snapshots duration immediately after this event. Start at the native
            // duration; the tick driver purchases acceleration only while energy is paid.
            effects.add(filtered(c, c.amplificationUnits(), c.focusUnits(), c.cooldownUnits(),
                    c.castingUnits(), 0));
        }
        SpellAttributeService.sync(player, effects, rates);
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        state.preparedSpell = event.getSpellId();
        state.castCarry = 0;
        clampMana(player);
    }

    public static void onCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator()) {
            clear(player);
            return;
        }
        ArcanaRates rates = ArcanaConfig.rates();
        List<ArcanaCarrier> effects = new ArrayList<>();
        for (var equipped : ArcanaCarrier.readEquipped(player)) {
            ItemStack stack = equipped.stack();
            ArcanaCarrier c = powered(equipped.carrier(), stack, rates);
            int amplification = c.amplificationUnits();
            int focus = c.focusUnits();
            if (amplification > 0 && !pay(stack, rates.amplificationCastCost() * c.amplificationStep())) {
                amplification = 0;
            }
            boolean selectedSchool = event.getSchoolType().getId().getPath()
                    .equalsIgnoreCase(c.focusSchool().name());
            if (!selectedSchool) focus = 0;
            if (focus > 0 && selectedSchool && !pay(stack, rates.focusCastCost() * c.focusStep())) {
                focus = 0;
            }
            // Paid bonuses must remain for the synchronous spell execution even if this
            // transaction used the carrier's final FE. The next tick reconciles them.
            ArcanaCarrier remaining = powered(c, stack, rates);
            effects.add(new ArcanaCarrier(remaining.manaUnits(), amplification, focus,
                    remaining.cooldownUnits(), remaining.castingUnits(), c.manaSpeedPreset(), c.focusSchool(),
                    c.amplificationStep(), c.focusStep(), c.cooldownStep(),
                    MagicData.getPlayerMagicData(player).getCastType() == CastType.CONTINUOUS
                            ? 0 : c.castingStep()));
        }
        SpellAttributeService.sync(player, effects, rates);
    }

    public static void onCooldown(SpellCooldownAddedEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()
                || event.getCastSource() == CastSource.SCROLL) return;
        // Undo precisely the upfront reduction from our own attribute. Preserve other
        // equipment and the additive duration adjustments made by earlier event listeners.
        int enhanced = MagicManager.getEffectiveSpellCooldown(event.getSpell(), player, event.getCastSource());
        SpellAttributeService.withdraw(player);
        int baseline = MagicManager.getEffectiveSpellCooldown(event.getSpell(), player, event.getCastSource());
        int difference = Math.max(0, baseline - enhanced);
        if (difference > 0) {
            event.setEffectiveCooldown((int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, (long) event.getEffectiveCooldown() + difference)));
        }
        refresh(player);
    }

    private static boolean accelerateCooldowns(ServerPlayer player, ItemStack stack, ArcanaCarrier c,
            ArcanaRates rates, State state, double baseRating, double bonusDelta) {
        var cooldowns = MagicData.getPlayerMagicData(player).getPlayerCooldowns();
        double extra = extraTicks(baseRating, bonusDelta);
        List<CooldownWork> work = new ArrayList<>();
        double totalCost = 0;
        for (var entry : new ArrayList<>(cooldowns.getSpellCooldowns().entrySet())) {
            CooldownInstance cooldown = entry.getValue();
            int remaining = cooldown.getCooldownRemaining();
            if (remaining <= 0) continue;
            double progress = state.cooldownCarry.getOrDefault(cooldown, 0.0) + extra;
            // Leave the final ordinary tick to Iron's. Zero/negative durations are never minted.
            int ticks = (int) Math.min(Math.max(0, remaining - 1), Math.floor(progress));
            double cost = rates.cooldownFePerTickPerSlot() * c.cooldownStep()
                    + rates.cooldownSavedTimeFePerTick() * ticks;
            totalCost += cost;
            work.add(new CooldownWork(cooldown, ticks, Math.max(0, progress - Math.floor(progress))));
        }
        // One transaction for the entire tick: insufficient FE never favors whichever
        // cooldown happened to be visited first in Iron's map.
        if (!pay(stack, totalCost)) {
            state.cooldownCarry.clear();
            return false;
        }
        boolean changed = false;
        for (CooldownWork item : work) {
            state.cooldownCarry.put(item.cooldown(), item.carry());
            if (item.ticks() > 0) {
                cooldowns.decrementCooldown(item.cooldown(), item.ticks());
                changed = true;
            }
        }
        if (changed) cooldowns.syncToPlayer(player);
        return true;
    }

    private static boolean accelerateCasting(ServerPlayer player, ItemStack stack, ArcanaCarrier c,
            ArcanaRates rates, State state, double baseRating, double bonusDelta) {
        MagicData magic = MagicData.getPlayerMagicData(player);
        boolean timed = magic.getCastType() == CastType.LONG
                && magic.getCastingSpellId().equals(state.preparedSpell);
        double extra = timed ? extraTicks(baseRating, bonusDelta) : 0;
        double progress = state.castCarry + extra;
        int ticks = (int) Math.min(Math.max(0, magic.getCastDurationRemaining() - 1), Math.floor(progress));
        // The binary movement benefit still consumes the fixed casting load at a zero time setting.
        double cost = rates.castingFePerTick() + rates.castingSavedTimeFePerTick() * ticks;
        if (!pay(stack, cost)) {
            state.castCarry = 0;
            return false;
        }
        state.castCarry = Math.max(0, progress - Math.floor(progress));
        for (int i = 0; i < ticks; i++) magic.handleCastDuration();
        if (ticks > 0) {
            PacketDistributor.sendToPlayer(player, new UpdateCastingStatePacket(
                    magic.getCastingSpellId(), magic.getCastingSpellLevel(), magic.getCastDurationRemaining(),
                    magic.getCastSource(), magic.getCastingEquipmentSlot()));
        }
        return true;
    }

    private static void restoreMana(ServerPlayer player, ItemStack stack, ArcanaCarrier carrier, ArcanaRates rates) {
        if (carrier.manaUnits() <= 0 || ArcanaEnergy.stored(stack) <= 0) return;
        var state = ManaBridge.read(player);
        if (state.isEmpty()) return;
        int headroom = (int) Math.max(0, Math.floor(state.get().max() - state.get().current()));
        long budget = Math.min(ArcanaEnergy.stored(stack),
                (long) rates.manaConversionFePerTick(carrier.manaSpeedPreset()) * carrier.manaUnits());
        int proposed = rates.manaRestorableForFe(budget, headroom);
        if (proposed <= 0) return;
        long charged = cost(rates.feForMana(proposed));
        if (!ArcanaEnergy.canPay(stack, charged)) return;
        long consumed = ArcanaEnergy.consume(stack, charged);
        int funded = rates.manaRestorableForFe(consumed, headroom);
        double credited = funded > 0 ? ManaBridge.creditExact(player, funded) : 0;
        long owed = cost(credited * Math.max(0, rates.fePerMana()));
        if (consumed > owed) ArcanaEnergy.refund(stack, consumed - owed);
    }

    private static ArcanaCarrier powered(ArcanaCarrier c, ItemStack stack, ArcanaRates rates) {
        if (ArcanaEnergy.stored(stack) <= 0) return ArcanaCarrier.empty();
        long budget = ArcanaEnergy.stored(stack);
        long amplificationCost = cost(rates.amplificationCastCost() * c.amplificationStep());
        int amplification = c.amplificationStep() > 0 && budget >= amplificationCost ? c.amplificationUnits() : 0;
        if (amplification > 0) budget -= amplificationCost;
        long focusCost = cost(rates.focusCastCost() * c.focusStep());
        int focus = c.focusStep() > 0 && budget >= focusCost ? c.focusUnits() : 0;
        int casting = ArcanaEnergy.canPay(stack, cost(rates.castingFePerTick())) ? c.castingUnits() : 0;
        int cooldown = c.cooldownStep() > 0 && ArcanaEnergy.canPay(stack,
                cost(rates.cooldownFePerTickPerSlot() * c.cooldownStep())) ? c.cooldownUnits() : 0;
        return filtered(c, amplification, focus, cooldown, casting, c.castingStep());
    }

    private static ArcanaCarrier filtered(ArcanaCarrier c, int amplification, int focus, int cooldown,
            int casting, double castingStep) {
        return new ArcanaCarrier(c.manaUnits(), amplification, focus, cooldown, casting,
                c.manaSpeedPreset(), c.focusSchool(), c.amplificationStep(), c.focusStep(),
                c.cooldownStep(), castingStep);
    }

    private static double extraTicks(double baselineRating, double bonus) {
        return Math.min(4096, ArcanaTiming.extraTicks(baselineRating, bonus));
    }

    private static long cost(double amount) {
        if (!Double.isFinite(amount)) return Long.MAX_VALUE;
        return (long) Math.ceil(Math.max(0, amount));
    }

    private static boolean pay(ItemStack stack, double amount) {
        long wanted = cost(amount);
        if (wanted == 0) return true;
        if (!ArcanaEnergy.canPay(stack, wanted)) return false;
        long extracted = ArcanaEnergy.consume(stack, wanted);
        if (extracted == wanted) return true;
        if (extracted > 0) ArcanaEnergy.refund(stack, extracted);
        return false;
    }

    private static void clampMana(ServerPlayer player) {
        MagicData magic = MagicData.getPlayerMagicData(player);
        double maximum = player.getAttributeValue(AttributeRegistry.MAX_MANA);
        if (magic.getMana() > maximum) magic.setMana((float) maximum);
    }

    private static void clear(ServerPlayer player) {
        SpellAttributeService.withdraw(player);
        STATES.remove(player.getUUID());
        clampMana(player);
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player);
    }

    private static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original) clear(original);
        if (event.getEntity() instanceof ServerPlayer player) clear(player);
    }

    private static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player);
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
    }

    private static final class State {
        private final Map<CooldownInstance, Double> cooldownCarry = new IdentityHashMap<>();
        private double castCarry;
        private String preparedSpell = "";
    }

    private record CooldownWork(CooldownInstance cooldown, int ticks, double carry) {}
}
