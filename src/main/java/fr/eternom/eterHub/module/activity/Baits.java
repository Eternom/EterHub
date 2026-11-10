package fr.eternom.eterHub.module.activity;

import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Les appâts (fishing.baits), vendus par le poissonnier : un appât est pris à chaque prise (pas au lancer : rien ne
 * se perd si rien ne mord). Sans appât, la ligne ne se lance pas (fishing.bait-required). Chaque appât ajoute de la
 * chance (luck : les raretés mordent plus souvent, comme Chance de la mer, et s'y ajoute). L'appât utilisé : celui en
 * seconde main, sinon le premier de l'inventaire. Un vrai objet marqué eterhub:bait (item-model : modèle du pack de
 * ressources, plus tard).
 */
public final class Baits {

    /** Un appât : prix (Heloks), chance en plus, icône, modèle du pack de ressources (vide : aucun). */
    public record Bait(String id, double price, int luck, Material icon, String model) {
    }

    private final NamespacedKey key;
    private final Messages messages;
    private volatile Map<String, Bait> baits = Map.of();
    private volatile boolean required = true;

    public Baits(JavaPlugin plugin, Messages messages) {
        this.key = new NamespacedKey(plugin, "bait");
        this.messages = messages;
    }

    public void load(ConfigurationSection fishing, Logger logger) {
        Map<String, Bait> loaded = new LinkedHashMap<>();
        ConfigurationSection section = fishing == null ? null : fishing.getConfigurationSection("baits");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection bait = section.getConfigurationSection(id);
                if (bait == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(bait.getString("icon", "WHEAT_SEEDS"));
                if (icon == null || !icon.isItem()) {
                    logger.warning("fishing.baits." + id + ".icon : objet inconnu, WHEAT_SEEDS utilisé");
                    icon = Material.WHEAT_SEEDS;
                }
                loaded.put(id.toLowerCase(Locale.ROOT), new Bait(id.toLowerCase(Locale.ROOT), Math.max(0, bait.getDouble("price", 0)),
                        Math.max(0, bait.getInt("luck", 0)), icon, bait.getString("item-model", "")));
            }
        }
        baits = Map.copyOf(loaded);
        required = fishing == null || fishing.getBoolean("bait-required", true);
    }

    /** Les appâts, dans l'ordre de config.yml. */
    public List<Bait> all() {
        return new ArrayList<>(baits.values());
    }

    public Optional<Bait> get(String id) {
        return Optional.ofNullable(baits.get(id));
    }

    /** Faut-il un appât pour pêcher ? (Pas d'appât défini : non.) */
    public boolean required() {
        return required && !baits.isEmpty();
    }

    /** L'objet d'un appât (amount au plus une pile). */
    public ItemStack item(Player viewer, Bait bait, int amount) {
        ItemStack stack = ItemStack.of(bait.icon(), Math.max(1, Math.min(amount, bait.icon().getMaxStackSize())));
        stack.editMeta(meta -> {
            meta.customName(messages.get(viewer, "fishing.bait." + bait.id() + ".name").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(viewer, "fishing.bait." + bait.id() + ".lore").decoration(TextDecoration.ITALIC, false));
            lore.add(messages.get(viewer, "fishing.bait.usage").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, bait.id());
            if (!bait.model().isBlank()) {
                NamespacedKey model = NamespacedKey.fromString(bait.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
        });
        return stack;
    }

    /** L'appât de cet objet, s'il en est un (encore connu). */
    public Optional<Bait> read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? Optional.empty() : get(id);
    }

    /** L'appât qui servira : celui en seconde main, sinon le premier de l'inventaire. */
    public Optional<Bait> find(Player player) {
        PlayerInventory inventory = player.getInventory();
        Optional<Bait> off = read(inventory.getItemInOffHand());
        if (off.isPresent()) {
            return off;
        }
        for (ItemStack stack : inventory.getStorageContents()) {
            Optional<Bait> bait = read(stack);
            if (bait.isPresent()) {
                return bait;
            }
        }
        return Optional.empty();
    }

    /** Prend un appât (seconde main d'abord) ; vide s'il n'y en a plus. */
    public Optional<Bait> consume(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack off = inventory.getItemInOffHand();
        Optional<Bait> bait = read(off);
        if (bait.isPresent()) {
            off.setAmount(off.getAmount() - 1);
            inventory.setItemInOffHand(off.getAmount() <= 0 ? null : off);
            return bait;
        }
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            bait = read(storage[i]);
            if (bait.isPresent()) {
                storage[i].setAmount(storage[i].getAmount() - 1);
                inventory.setItem(i, storage[i].getAmount() <= 0 ? null : storage[i]);
                return bait;
            }
        }
        return Optional.empty();
    }

    /** Combien d'appâts de ce genre le joueur a. */
    public int count(Player player, String id) {
        int count = 0;
        List<ItemStack> all = new ArrayList<>(java.util.Arrays.asList(player.getInventory().getStorageContents()));
        all.add(player.getInventory().getItemInOffHand());
        for (ItemStack stack : all) {
            if (read(stack).map(bait -> bait.id().equals(id)).orElse(false)) {
                count += stack.getAmount();
            }
        }
        return count;
    }
}
