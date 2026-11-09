package fr.eternom.eterHub.module.zone;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneIndexTest {

    @Test
    void cornersInAnyOrder() {
        Region region = Region.of("world", 10, 80, -5, -10, 60, 5);
        assertEquals(new Region("world", -10, 60, -5, 10, 80, 5, null, null), region);
        assertTrue(region.contains("world", 10, 80, 5));
        assertTrue(region.contains("world", -10, 60, -5));
        assertFalse(region.contains("world", 11, 70, 0));
        assertFalse(region.contains("world_nether", 0, 70, 0));
        assertEquals(21L * 21 * 11, region.volume());
    }

    @Test
    void smallestZoneWins() {
        ZoneIndex index = new ZoneIndex(Map.of(
                "donjon", Region.of("world", 0, 0, 0, 100, 100, 100),
                "donjon-secret", Region.of("world", 40, 10, 40, 50, 20, 50),
                "village", Region.of("world", -500, -64, -500, 500, 320, 500)));
        assertEquals(Optional.of("donjon-secret"), index.at("world", 45, 15, 45));
        assertEquals(Optional.of("donjon"), index.at("world", 5, 5, 5));
        assertEquals(Optional.of("village"), index.at("world", -200, 70, 0));
        assertEquals(Optional.empty(), index.at("world", 900, 70, 0));
        assertEquals(Optional.empty(), ZoneIndex.EMPTY.at("world", 0, 0, 0));
    }

    @Test
    void sameVolumeIsStable() {
        ZoneIndex index = new ZoneIndex(Map.of(
                "b", Region.of("world", 0, 0, 0, 9, 9, 9),
                "a", Region.of("world", 5, 0, 5, 14, 9, 14)));
        assertEquals(Optional.of("a"), index.at("world", 7, 5, 7));
    }
}
