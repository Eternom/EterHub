package fr.eternom.eterHub.module.farm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityTest {

    @Test
    void rollByChance() {
        assertEquals(Quality.GOLDEN, Quality.roll(0.01, 0.15, 0.02));
        assertEquals(Quality.FINE, Quality.roll(0.02, 0.15, 0.02));
        assertEquals(Quality.FINE, Quality.roll(0.169, 0.15, 0.02));
        assertEquals(Quality.NORMAL, Quality.roll(0.17, 0.15, 0.02));
        assertEquals(Quality.NORMAL, Quality.roll(0.99, 0.15, 0.02));
    }

    @Test
    void parseIsForgiving() {
        assertEquals(Quality.GOLDEN, Quality.parse("golden"));
        assertEquals(Quality.NORMAL, Quality.parse("n'importe"));
        assertEquals(Quality.NORMAL, Quality.parse(null));
    }

    @Test
    void ofTheDayIsStableAndChanges() {
        List<String> parcels = List.of("ble-ouest", "ble-sud", "ble-est", "carottes", "patates", "betteraves");
        LocalDate day = LocalDate.of(2026, 11, 10);
        assertEquals(Quality.ofTheDay(day, parcels), Quality.ofTheDay(day, parcels));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            seen.add(Quality.ofTheDay(day.plusDays(i), parcels).orElseThrow());
        }
        assertTrue(seen.size() >= 4, "la parcelle du jour doit tourner");
        assertTrue(Quality.ofTheDay(day, List.of()).isEmpty());
    }
}
