package dev.vvh.mekasuitarcana.energy;

import java.util.List;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.util.StorageUtils;
import mekanism.common.util.UnitDisplayUtils.EnergyUnit;
import net.minecraft.world.item.ItemStack;

/** FE-facing bridge for Mekanism's strict Joule containers. */
public final class ArcanaEnergy {
    private ArcanaEnergy() {}

    /** Stored energy expressed in FE, using Mekanism's live Forge Energy conversion. */
    public static long stored(ItemStack carrier) {
        IEnergyContainer container = container(carrier);
        return container == null ? 0L : toFe(container.getEnergy());
    }

    /** Capacity expressed in FE, using Mekanism's live Forge Energy conversion. */
    public static long capacity(ItemStack carrier) {
        IEnergyContainer container = container(carrier);
        return container == null ? 0L : toFe(container.getMaxEnergy());
    }

    /** Consume whole FE units only; any sub-FE strict energy is preserved. */
    public static long consume(ItemStack carrier, long amount) {
        if (amount <= 0L) return 0L;
        IEnergyContainer container = container(carrier);
        if (container == null) return 0L;

        long available = toFe(container.getEnergy());
        long targetFe = Math.min(amount, available);
        long requestedJoules = toJoules(targetFe);
        if (requestedJoules <= 0L) return 0L;

        long simulated = container.extract(requestedJoules, Action.SIMULATE, AutomationType.MANUAL);
        if (simulated <= 0L) return 0L;
        long extracted = container.extract(simulated, Action.EXECUTE, AutomationType.MANUAL);
        long actualFe = Math.min(targetFe, toFe(extracted));
        // Do not lose strict-energy remainder when a handler supplied less than requested.
        long accountedJoules = toJoules(actualFe);
        if (extracted > accountedJoules) {
            container.insert(extracted - accountedJoules, Action.EXECUTE, AutomationType.MANUAL);
        }
        return actualFe;
    }

    /** Reinsert FE into the carrier and return the FE actually reinserted. */
    public static long refund(ItemStack carrier, long fe) {
        if (fe <= 0L) return 0L;
        IEnergyContainer container = container(carrier);
        if (container == null) return 0L;

        long requestedJoules = toJoules(fe);
        if (requestedJoules <= 0L) return 0L;
        // IEnergyContainer.insert returns the remainder, not the accepted amount.
        long simulatedRemainder = container.insert(requestedJoules, Action.SIMULATE,
                AutomationType.MANUAL);
        long accepted = Math.max(0L, requestedJoules - simulatedRemainder);
        if (accepted <= 0L) return 0L;
        long executeRemainder = container.insert(accepted, Action.EXECUTE, AutomationType.MANUAL);
        long inserted = Math.max(0L, accepted - executeRemainder);
        return Math.min(fe, toFe(inserted));
    }

    /** True only when the whole requested FE amount can be extracted now. */
    public static boolean canPay(ItemStack carrier, long amount) {
        if (amount <= 0L) return true;
        IEnergyContainer container = container(carrier);
        if (container == null || stored(carrier) < amount) return false;
        long requestedJoules = toJoules(amount);
        return requestedJoules > 0L
                && container.extract(requestedJoules, Action.SIMULATE, AutomationType.MANUAL)
                >= requestedJoules;
    }

    private static IEnergyContainer container(ItemStack carrier) {
        if (carrier == null || carrier.isEmpty()) return null;
        IEnergyContainer direct = StorageUtils.getEnergyContainer(carrier, 0);
        if (direct != null) return direct;
        List<IEnergyContainer> attached = ContainerType.ENERGY.getAttachmentContainersIfPresent(carrier);
        return attached == null || attached.isEmpty() ? null : attached.get(0);
    }

    private static long toJoules(long fe) {
        if (fe <= 0L) return 0L;
        double conversion = EnergyUnit.FORGE_ENERGY.getConversion();
        double joules = fe * conversion;
        if (!Double.isFinite(joules) || joules >= Long.MAX_VALUE) return Long.MAX_VALUE;
        // The long Mekanism conversion helper truncates. Required energy must round up so a
        // partial strict-energy balance can never fund a whole FE unit.
        return Math.max(1L, (long) Math.ceil(joules));
    }

    private static long toFe(long joules) {
        if (joules <= 0L) return 0L;
        double conversion = EnergyUnit.FORGE_ENERGY.getConversion();
        if (!(conversion > 0.0D) || !Double.isFinite(conversion)) return 0L;
        double fe = joules / conversion;
        if (!Double.isFinite(fe) || fe >= Long.MAX_VALUE) return Long.MAX_VALUE;
        // Report only completely covered FE units; the strict-energy remainder stays stored.
        return Math.max(0L, (long) Math.floor(fe));
    }
}
