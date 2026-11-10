package fr.eternom.eterHub.module.activity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishPricesTest {

    private final FishPrices prices = new FishPrices(Map.of(
            LootTable.Rarity.COMMON, new FishPrices.Price(5, 2),
            LootTable.Rarity.RARE, new FishPrices.Price(40, 8)), 2);

    @Test
    void baseAndWeight() {
        assertEquals(7.0, prices.price(LootTable.Rarity.COMMON, 1000, false));
        assertEquals(6.5, prices.price(LootTable.Rarity.COMMON, 750, false));
        assertEquals(80.0, prices.price(LootTable.Rarity.RARE, 5000, false));
    }

    @Test
    void fishOfTheDayIsDoubled() {
        assertEquals(160.0, prices.price(LootTable.Rarity.RARE, 5000, true));
    }

    @Test
    void unknownRarityIsWorthNothing() {
        assertEquals(0.0, prices.price(LootTable.Rarity.LEGENDARY, 10_000, false));
    }

    @Test
    void fishOfTheDayIsStableAndNeverLegendary() {
        List<LootTable.Entry> entries = List.of(
                new LootTable.Entry("gardon", LootTable.Rarity.COMMON, 30, null, 0, 1),
                new LootTable.Entry("truite", LootTable.Rarity.UNCOMMON, 16, null, 0, 1),
                new LootTable.Entry("brochet", LootTable.Rarity.RARE, 8, null, 0, 1),
                new LootTable.Entry("carpe-doree", LootTable.Rarity.LEGENDARY, 2, null, 0, 1));
        LocalDate day = LocalDate.of(2026, 11, 10);
        assertEquals(FishPrices.fishOfTheDay(day, entries), FishPrices.fishOfTheDay(day, entries));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            String fish = FishPrices.fishOfTheDay(day.plusDays(i), entries).orElseThrow();
            assertNotEquals("carpe-doree", fish);
            seen.add(fish);
        }
        assertTrue(seen.size() >= 2, "le poisson du jour doit changer d'un jour à l'autre");
        assertTrue(FishPrices.fishOfTheDay(day, List.of()).isEmpty());
    }
}
