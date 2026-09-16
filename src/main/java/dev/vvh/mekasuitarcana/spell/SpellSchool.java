package dev.vvh.mekasuitarcana.spell;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import mekanism.api.text.IHasTextComponent;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.util.StringRepresentable;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;

/** The nine Iron's Spellbooks schools supported by Focus Units. */
public enum SpellSchool implements IHasTextComponent, StringRepresentable {
    FIRE,
    ICE,
    LIGHTNING,
    HOLY,
    ENDER,
    BLOOD,
    EVOCATION,
    NATURE,
    ELDRITCH;

    public static final Codec<SpellSchool> CODEC = StringRepresentable.fromEnum(SpellSchool::values);
    public static final StreamCodec<ByteBuf, SpellSchool> STREAM_CODEC =
            ByteBufCodecs.idMapper(SpellSchool::byOrdinal, SpellSchool::ordinal);

    @Override
    public String getSerializedName() {
        return SpellSchoolNames.serializedName(ordinal());
    }

    @Override
    public Component getTextComponent() {
        return Component.translatable("spell.school." + getSerializedName());
    }

    /** Stable human-readable fallback used by logs and non-localized callers. */
    public String displayName() {
        String serialized = getSerializedName();
        return Character.toUpperCase(serialized.charAt(0)) + serialized.substring(1);
    }

    /** The Iron's per-school SPELL_POWER attribute for this school. */
    public Holder<Attribute> spellPowerAttribute() {
        return switch (this) {
            case FIRE -> AttributeRegistry.FIRE_SPELL_POWER;
            case ICE -> AttributeRegistry.ICE_SPELL_POWER;
            case LIGHTNING -> AttributeRegistry.LIGHTNING_SPELL_POWER;
            case HOLY -> AttributeRegistry.HOLY_SPELL_POWER;
            case ENDER -> AttributeRegistry.ENDER_SPELL_POWER;
            case BLOOD -> AttributeRegistry.BLOOD_SPELL_POWER;
            case EVOCATION -> AttributeRegistry.EVOCATION_SPELL_POWER;
            case NATURE -> AttributeRegistry.NATURE_SPELL_POWER;
            case ELDRITCH -> AttributeRegistry.ELDRITCH_SPELL_POWER;
        };
    }

    public static SpellSchool defaultSchool() {
        return FIRE;
    }

    private static SpellSchool byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= values().length) {
            return defaultSchool();
        }
        return values()[ordinal];
    }
}

/**
 * Loader-free school-name data used by the plain JUnit test. Keeping this helper in the same source
 * file lets the test validate the wire vocabulary without initializing the Minecraft-backed enum.
 */
final class SpellSchoolNames {
    private static final String[] NAMES = {
        "fire", "ice", "lightning", "holy", "ender", "blood", "evocation", "nature", "eldritch"
    };

    private SpellSchoolNames() {
    }

    static String serializedName(int ordinal) {
        return ordinal >= 0 && ordinal < NAMES.length ? NAMES[ordinal] : NAMES[0];
    }

    static String[] copy() {
        return NAMES.clone();
    }

    static int ordinal(String name) {
        if (name == null) {
            return -1;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        for (int index = 0; index < NAMES.length; index++) {
            if (NAMES[index].equals(normalized)) {
                return index;
            }
        }
        return -1;
    }
}
