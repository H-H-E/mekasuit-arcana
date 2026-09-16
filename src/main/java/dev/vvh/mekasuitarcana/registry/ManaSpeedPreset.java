package dev.vvh.mekasuitarcana.registry;

import com.mojang.serialization.Codec;
import dev.vvh.mekasuitarcana.balance.ArcanaRates;
import java.util.function.IntFunction;
import mekanism.api.IIncrementalEnum;
import mekanism.api.text.IHasTextComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** Module Tweaker enum with a lossless adapter to the loader-free balance enum. */
public enum ManaSpeedPreset implements IHasTextComponent, IIncrementalEnum<ManaSpeedPreset>, StringRepresentable {
    LOW("low", ArcanaRates.SpeedPreset.LOW),
    NORMAL("normal", ArcanaRates.SpeedPreset.NORMAL),
    HIGH("high", ArcanaRates.SpeedPreset.HIGH),
    MAXIMUM("maximum", ArcanaRates.SpeedPreset.MAXIMUM);

    public static final Codec<ManaSpeedPreset> CODEC = StringRepresentable.fromEnum(ManaSpeedPreset::values);
    public static final IntFunction<ManaSpeedPreset> BY_ID = index -> values()[Math.max(0, Math.min(values().length - 1, index))];
    public static final StreamCodec<io.netty.buffer.ByteBuf, ManaSpeedPreset> STREAM_CODEC =
            ByteBufCodecs.idMapper(BY_ID, ManaSpeedPreset::ordinal);

    private final String serializedName;
    private final ArcanaRates.SpeedPreset ratesPreset;

    ManaSpeedPreset(String serializedName, ArcanaRates.SpeedPreset ratesPreset) {
        this.serializedName = serializedName;
        this.ratesPreset = ratesPreset;
    }

    public ArcanaRates.SpeedPreset toRatesPreset() {
        return ratesPreset;
    }

    public static ManaSpeedPreset fromRatesPreset(ArcanaRates.SpeedPreset preset) {
        return switch (preset) {
            case LOW -> LOW;
            case NORMAL -> NORMAL;
            case HIGH -> HIGH;
            case MAXIMUM -> MAXIMUM;
        };
    }

    @Override
    public ManaSpeedPreset byIndex(int index) {
        return BY_ID.apply(index);
    }

    @Override
    public Component getTextComponent() {
        return Component.translatable("config.mekasuitarcana.mana_speed_preset." + serializedName);
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
