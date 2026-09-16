package dev.vvh.mekasuitarcana.mana;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Adapter for Iron's existing mana attachment; this mod never creates a second pool. */
public final class ManaBridge {
    private ManaBridge() {}

    /** Current/max mana, or empty if Iron's attachment cannot be resolved. */
    public static Optional<ManaState> read(Player player) {
        if (player == null) return Optional.empty();
        try {
            MagicData magic = MagicData.getPlayerMagicData(player);
            if (magic == null) return Optional.empty();
            double current = Math.max(0.0D, magic.getMana());
            double maximum = Math.max(0.0D, player.getAttributeValue(AttributeRegistry.MAX_MANA));
            return Optional.of(new ManaState(current, maximum));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Credit the existing pool once, honoring ChangeManaEvent and returning the actual double
     * delta. The caller can price FE from this exact delta when an event applies a fractional
     * adjustment; this method never retries setMana after the event has run.
     */
    public static double creditExact(Player player, int amount) {
        if (amount <= 0 || player == null) return 0.0D;

        final MagicData magic;
        final double maximum;
        final double before;
        final int requested;
        try {
            magic = MagicData.getPlayerMagicData(player);
            if (magic == null) return 0.0D;
            maximum = Math.max(0.0D, player.getAttributeValue(AttributeRegistry.MAX_MANA));
            before = Math.max(0.0D, magic.getMana());
            requested = (int) Math.min((long) amount,
                    Math.max(0L, (long) Math.floor(maximum - before)));
        } catch (RuntimeException unavailable) {
            // This block is strictly pre-mutation. Do not catch setMana or packet-send failures:
            // either may occur after the authoritative mana value has changed.
            return 0.0D;
        }
        if (requested <= 0) return 0.0D;

        magic.setMana((float) Math.min(maximum, before + requested));
        double actual = magic.getMana() - before;
        actual = Math.max(0.0D, Math.min(requested, actual));
        if (actual > 0.0D && player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new SyncManaPacket(magic));
        }
        return actual;
    }

    /** Credit the existing pool, honoring ChangeManaEvent and returning the actual integer delta. */
    public static int credit(Player player, int amount) {
        return (int) Math.floor(creditExact(player, amount));
    }

    public record ManaState(double current, double max) {}
}
