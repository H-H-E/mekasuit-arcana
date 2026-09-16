package dev.vvh.mekasuitarcana.spell;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpellSchoolTest {
    @Test
    void wireNamesStayInTheLockedIronOrderWithoutLoadingMinecraft() {
        assertArrayEquals(
                new String[] {"fire", "ice", "lightning", "holy", "ender", "blood", "evocation", "nature", "eldritch"},
                SpellSchoolNames.copy());
        assertEquals(0, SpellSchoolNames.ordinal("FIRE"));
        assertEquals(8, SpellSchoolNames.ordinal("eldritch"));
        assertEquals(-1, SpellSchoolNames.ordinal("unknown"));
    }
}
