package fr.eternom.eterHub.module.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionPolygonTest {

    /** Un « U » (concave) : deux bras et une base, le creux du milieu n'en fait pas partie. */
    private final Region u = Region.polygon("world", new int[]{0, 30, 30, 20, 20, 10, 10, 0}, new int[]{0, 0, 30, 30, 10, 10, 30, 30},
            Region.NO_MIN, Region.NO_MAX);

    @Test
    void concaveShape() {
        assertTrue(u.contains("world", 5, 64, 20));     // bras gauche
        assertTrue(u.contains("world", 25, 64, 20));    // bras droit
        assertTrue(u.contains("world", 15, 64, 5));     // base
        assertFalse(u.contains("world", 15, 64, 20));   // le creux
        assertFalse(u.contains("world", 40, 64, 5));    // dehors
        assertFalse(u.contains("other", 5, 64, 20));
    }

    @Test
    void bordersAndCornersAreInside() {
        assertTrue(u.contains("world", 0, 64, 0));
        assertTrue(u.contains("world", 30, 64, 15));
        assertTrue(u.contains("world", 20, 64, 20));
    }

    @Test
    void optionalHeight() {
        assertTrue(u.contains("world", 5, -60, 20));
        assertTrue(u.contains("world", 5, 300, 20));
        assertFalse(u.hasHeight());
        Region low = Region.polygon("world", new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 20, 40);
        assertTrue(low.hasHeight());
        assertTrue(low.contains("world", 5, 30, 5));
        assertFalse(low.contains("world", 5, 50, 5));
    }

    @Test
    void storedAndReadBack() {
        Region again = Region.read("world", u.minX(), u.minY(), u.minZ(), u.maxX(), u.maxY(), u.maxZ(), u.points());
        assertEquals(u, again);
        assertEquals(Region.of("world", 0, 0, 0, 5, 5, 5), Region.read("world", 5, 5, 5, 0, 0, 0, null));
    }

    @Test
    void lowerPlaceUnderAnotherWins() {
        Region village = Region.polygon("world", new int[]{0, 100, 100, 0}, new int[]{0, 0, 100, 100}, Region.NO_MIN, Region.NO_MAX);
        Region crypt = Region.polygon("world", new int[]{40, 60, 60, 40}, new int[]{40, 40, 60, 60}, 0, 40);
        ZoneIndex index = new ZoneIndex(java.util.Map.of("village", village, "crypte", crypt));
        assertEquals(java.util.Optional.of("crypte"), index.at("world", 50, 20, 50));
        assertEquals(java.util.Optional.of("village"), index.at("world", 50, 70, 50));
    }
}
