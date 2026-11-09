package fr.eternom.eterHub.module.activity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootTableTest {

    private final LootTable table = new LootTable(List.of(
            new LootTable.Entry("gardon", LootTable.Rarity.COMMON, 70, null, 0.2, 1.0),
            new LootTable.Entry("brochet", LootTable.Rarity.RARE, 25, null, 2, 8),
            new LootTable.Entry("carpe-doree", LootTable.Rarity.LEGENDARY, 5, null, 10, 20),
            new LootTable.Entry("jamais", LootTable.Rarity.COMMON, 0, null, 0, 0)));

    @Test
    void picksByChance() {
        assertEquals("gardon", table.roll(0.0, 0).orElseThrow().entry().id());
        assertEquals("gardon", table.roll(0.69, 0).orElseThrow().entry().id());
        assertEquals("brochet", table.roll(0.70, 0).orElseThrow().entry().id());
        assertEquals("carpe-doree", table.roll(0.96, 0).orElseThrow().entry().id());
        assertEquals("carpe-doree", table.roll(0.999999, 0).orElseThrow().entry().id());
    }

    @Test
    void zeroChanceIsNeverDrawn() {
        assertEquals(3, table.entries().size());
        assertTrue(table.entry("jamais").isEmpty());
    }

    @Test
    void weightStaysInRangeAndBigOnesAreRare() {
        assertEquals(2.0, table.roll(0.8, 0).orElseThrow().weight());
        assertEquals(3.5, table.roll(0.8, 0.5).orElseThrow().weight());
        double max = table.roll(0.8, 0.9999).orElseThrow().weight();
        assertTrue(max <= 8 && max > 7.9);
    }

    @Test
    void emptyTable() {
        assertTrue(new LootTable(List.of()).roll(0.5, 0.5).isEmpty());
        assertEquals(LootTable.Rarity.COMMON, LootTable.Rarity.parse("n'importe"));
        assertEquals(LootTable.Rarity.LEGENDARY, LootTable.Rarity.parse("Legendary"));
    }
}
