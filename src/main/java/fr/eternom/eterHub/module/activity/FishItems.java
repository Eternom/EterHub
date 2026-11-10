package fr.eternom.eterHub.module.activity;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * Les données d'un poisson du lac, gardées dans l'objet (eterhub:fish, eterhub:rarity, eterhub:grams) : écrites à la
 * prise, lues par le poissonnier. Un poisson ordinaire de Minecraft n'en a pas : il n'est pas racheté.
 */
public final class FishItems {

    /** Un poisson du lac : espèce, rareté (common...legendary), poids en grammes. */
    public record Fish(String id, LootTable.Rarity rarity, long grams) {
    }

    private final NamespacedKey fishKey;
    private final NamespacedKey rarityKey;
    private final NamespacedKey gramsKey;

    public FishItems(JavaPlugin plugin) {
        this.fishKey = new NamespacedKey(plugin, "fish");
        this.rarityKey = new NamespacedKey(plugin, "rarity");
        this.gramsKey = new NamespacedKey(plugin, "grams");
    }

    public void write(ItemMeta meta, String id, LootTable.Rarity rarity, long grams) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(fishKey, PersistentDataType.STRING, id);
        data.set(rarityKey, PersistentDataType.STRING, rarity.key());
        data.set(gramsKey, PersistentDataType.LONG, grams);
    }

    public Optional<Fish> read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        String id = data.get(fishKey, PersistentDataType.STRING);
        if (id == null) {
            return Optional.empty();
        }
        Long grams = data.get(gramsKey, PersistentDataType.LONG);
        return Optional.of(new Fish(id, LootTable.Rarity.parse(data.get(rarityKey, PersistentDataType.STRING)), grams == null ? 0 : grams));
    }

    public boolean isFish(ItemStack stack) {
        return read(stack).isPresent();
    }
}
