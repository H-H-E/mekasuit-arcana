package dev.vvh.mekasuitarcana.registry;

import com.mojang.serialization.Codec;
import java.util.function.IntFunction;
import mekanism.api.IIncrementalEnum;
import mekanism.api.text.IHasTextComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Player-facing quarter steps. The full step is the shipped default and represents the configured
 * server ceiling; it never raises a module beyond that ceiling.
 */
public enum ArcanaStep implements IHasTextComponent, IIncrementalEnum<ArcanaStep>, StringRepresentable {
    ZERO("zero", 0.0D),
    QUARTER("quarter", 0.25D),
    HALF("half", 0.5D),
    THREE_QUARTERS("three_quarters", 0.75D),
    FULL("full", 1.0D);

    public static final Codec<ArcanaStep> CODEC = StringRepresentable.fromEnum(ArcanaStep::values);
    public static final IntFunction<ArcanaStep> BY_ID = index -> values()[Math.max(0, Math.min(values().length - 1, index))];
    public static final StreamCodec<io.netty.buffer.ByteBuf, ArcanaStep> STREAM_CODEC =
            ByteBufCodecs.idMapper(BY_ID, ArcanaStep::ordinal);

    private final String serializedName;
    private final double factor;

    ArcanaStep(String serializedName, double factor) {
        this.serializedName = serializedName;
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }

    @Override
    public ArcanaStep byIndex(int index) {
        return BY_ID.apply(index);
    }

    @Override
    public Component getTextComponent() {
        return Component.translatable("config.mekasuitarcana.arcana_step." + serializedName);
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
