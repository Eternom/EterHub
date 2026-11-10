package fr.eternom.eterHub.module.activity;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prix du poissonnier et poisson du jour. Logique pure (testée).
 * Prix d'un poisson = base de sa rareté + prix au kilo de sa rareté × poids ; × bonus s'il est le poisson du jour.
 * Le poisson du jour change chaque jour, le même sur tous les lobbys (tiré de la date), parmi les espèces non
 * légendaires.
 */
public final class FishPrices {

    /** Prix d'une rareté : base et prix au kilo. */
    public record Price(double base, double perKg) {
    }

    private final Map<LootTable.Rarity, Price> prices;
    private final double dailyBonus;

    public FishPrices(Map<LootTable.Rarity, Price> prices, double dailyBonus) {
        this.prices = new EnumMap<>(LootTable.Rarity.class);
        this.prices.putAll(prices);
        this.dailyBonus = Math.max(1, dailyBonus);
    }

    public double dailyBonus() {
        return dailyBonus;
    }

    /** Prix d'un poisson (arrondi au centième), 0 si sa rareté n'a pas de prix. */
    public double price(LootTable.Rarity rarity, long grams, boolean fishOfTheDay) {
        Price price = prices.get(rarity);
        if (price == null) {
            return 0;
        }
        double value = price.base() + price.perKg() * Math.max(0, grams) / 1000.0;
        if (fishOfTheDay) {
            value *= dailyBonus;
        }
        return Math.round(value * 100) / 100.0;
    }

    /** Le poisson du jour : une espèce non légendaire, toujours la même pour une date donnée. */
    public static Optional<String> fishOfTheDay(LocalDate day, List<LootTable.Entry> entries) {
        List<LootTable.Entry> candidates = entries.stream().filter(entry -> entry.rarity() != LootTable.Rarity.LEGENDARY).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        long mixed = day.toEpochDay() * 2654435761L;
        return Optional.of(candidates.get((int) Math.floorMod(mixed ^ (mixed >>> 16), candidates.size())).id());
    }
}
