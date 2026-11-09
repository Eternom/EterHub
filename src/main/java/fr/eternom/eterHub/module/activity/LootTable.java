package fr.eternom.eterHub.module.activity;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Une table de tirage (poissons du lac, trouvailles des fouilles) : chaque entrée a une rareté, une chance relative,
 * une icône et, pour les poissons, un poids (kg) tiré entre min et max. Logique pure (testée), le hasard est donné.
 */
public final class LootTable {

    public enum Rarity {
        COMMON, UNCOMMON, RARE, LEGENDARY;

        public static Rarity parse(String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                return COMMON;
            }
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Entry(String id, Rarity rarity, double chance, Material icon, double min, double max) {
    }

    /** Un tirage : l'entrée et son poids (0 sans poids). */
    public record Draw(Entry entry, double weight) {
    }

    private final List<Entry> entries;
    private final double total;

    public LootTable(List<Entry> entries) {
        this.entries = List.copyOf(entries.stream().filter(entry -> entry.chance() > 0).toList());
        this.total = this.entries.stream().mapToDouble(Entry::chance).sum();
    }

    public static LootTable load(ConfigurationSection section) {
        List<Entry> entries = new ArrayList<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(entry.getString("icon", "COD"));
                entries.add(new Entry(id, Rarity.parse(entry.getString("rarity", "common")), entry.getDouble("chance", 1),
                        icon == null || !icon.isItem() ? Material.COD : icon, entry.getDouble("min", 0), entry.getDouble("max", 0)));
            }
        }
        return new LootTable(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> entry(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** pick et size dans [0, 1[ : l'entrée tirée, et son poids (arrondi à 10 g). Vide si la table est vide. */
    public Optional<Draw> roll(double pick, double size) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        double target = pick * total;
        double sum = 0;
        Entry chosen = entries.getLast();
        for (Entry entry : entries) {
            sum += entry.chance();
            if (target < sum) {
                chosen = entry;
                break;
            }
        }
        double weight = chosen.max() > chosen.min() ? chosen.min() + (chosen.max() - chosen.min()) * size * size : chosen.min();
        return Optional.of(new Draw(chosen, Math.round(weight * 100) / 100.0));
    }
}
