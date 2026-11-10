package fr.eternom.eterHub.module.activity;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Le poissonnier du lac (action eterhub:fishmonger, sur un PNJ d'EterNpc) : il rachète les poissons du lac (ceux qui
 * portent les données d'EterHub, pas les poissons ordinaires). Prix selon la rareté et le poids
 * (fishing.market.prices), × fishing.market.daily-bonus pour le poisson du jour. Clic sur un poisson : vendre cette
 * pile ; « Tout vendre ». L'objet est retiré puis le montant versé par EterEconomy ; si le versement échoue, le
 * poisson est rendu. Il vend aussi les appâts (fishing.baits) : clic = 1, Maj + clic = 16. Sans EterEconomy, le
 * poissonnier est fermé.
 */
public class Fishmonger {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int DAILY = 4;
    private static final int BACK = 45;
    private static final int SELL_ALL = 49;
    /** Les appâts à vendre (les trois premiers de config.yml). */
    private static final int[] BAIT_SLOTS = {51, 52, 53};

    private final JavaPlugin plugin;
    private final FishItems fishItems;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final BackButton back;
    private final Supplier<LootTable> table;
    private final Baits baits;

    public Fishmonger(JavaPlugin plugin, FishItems fishItems, HubSettings settings, HubStats stats, Messages messages, BackButton back,
                      Supplier<LootTable> table, Baits baits) {
        this.plugin = plugin;
        this.fishItems = fishItems;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.back = back;
        this.table = table;
        this.baits = baits;
    }

    /** Les prix de config.yml (fishing.market). */
    public FishPrices prices() {
        Map<LootTable.Rarity, FishPrices.Price> prices = new EnumMap<>(LootTable.Rarity.class);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("fishing.market.prices");
        for (LootTable.Rarity rarity : LootTable.Rarity.values()) {
            if (section != null && section.contains(rarity.key())) {
                prices.put(rarity, new FishPrices.Price(section.getDouble(rarity.key() + ".base", 0), section.getDouble(rarity.key() + ".per-kg", 0)));
            }
        }
        return new FishPrices(prices, settings.number("fishing.market.daily-bonus", 2));
    }

    /** Le poisson du jour (le même partout, change à minuit, heure de Paris). */
    public Optional<String> fishOfTheDay() {
        return FishPrices.fishOfTheDay(LocalDate.now(ZONE), table.get().entries());
    }

    /** Prix d'une pile de poissons du lac (0 si ce n'en est pas). */
    public double value(ItemStack stack) {
        Optional<FishItems.Fish> fish = fishItems.read(stack);
        if (fish.isEmpty()) {
            return 0;
        }
        String daily = fishOfTheDay().orElse("");
        double value = table.get().entry(fish.get().id()).map(LootTable.Entry::value).orElse(1.0);
        double each = Math.round(prices().price(fish.get().rarity(), fish.get().grams(), fish.get().id().equals(daily)) * value * 100) / 100.0;
        return each * stack.getAmount();
    }

    public void open(Player player) {
        if (!settings.bool("activities.fishing", true)) {
            messages.send(player, "fishing.closed");
            return;
        }
        if (!EconomyLink.available()) {
            messages.send(player, "fishmonger.no-economy");
            Sounds.refuse(player);
            return;
        }
        player.openInventory(new Stall(player).getInventory());
    }

    /** Vend les poissons de ces cases de l'inventaire (vérifiés à nouveau), puis verse le total. */
    private void sell(Player player, List<Integer> inventorySlots) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        List<ItemStack> taken = new ArrayList<>();
        double total = 0;
        int count = 0;
        for (int slot : inventorySlots) {
            if (slot < 0 || slot >= contents.length) {
                continue;
            }
            ItemStack stack = contents[slot];
            double value = value(stack);
            if (value <= 0) {
                continue;
            }
            total += value;
            count += stack.getAmount();
            taken.add(stack.clone());
            player.getInventory().setItem(slot, null);
        }
        if (taken.isEmpty()) {
            messages.send(player, "fishmonger.nothing");
            Sounds.refuse(player);
            return;
        }
        double amount = Math.round(total * 100) / 100.0;
        int sold = count;
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> EconomyLink.deposit(uuid, amount, "EterHub · poissonnier"), paid -> {
            if (paid) {
                messages.send(player, "fishmonger.sold", "count", String.valueOf(sold), "amount", EconomyLink.format(amount));
                player.playSound(player, Sound.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
                stats.add("activity:fish-sold");
            } else {
                giveBack(player, taken);
                messages.send(player, "fishmonger.failed");
            }
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Stall) {
                open(player);
            }
        }, () -> {
            giveBack(player, taken);
            messages.send(player, "fishmonger.failed");
        });
    }

    /** Acheter des appâts : payés d'abord (EterEconomy), puis donnés ; s'ils ne tiennent pas, rien n'est acheté. */
    private void buy(Player player, Baits.Bait bait, int amount) {
        ItemStack stack = baits.item(player, bait, amount);
        org.bukkit.inventory.Inventory copy = Bukkit.createInventory(null, 36);
        copy.setContents(java.util.Arrays.copyOf(player.getInventory().getStorageContents(), 36));
        if (!copy.addItem(stack.clone()).isEmpty()) {
            messages.send(player, "fishmonger.bait-full");
            Sounds.refuse(player);
            return;
        }
        double price = Math.round(bait.price() * stack.getAmount() * 100) / 100.0;
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> price <= 0 || EconomyLink.withdraw(uuid, price, "EterHub · appâts"), paid -> {
            if (!paid) {
                messages.send(player, "fishmonger.bait-not-enough", "amount", EconomyLink.format(price));
                Sounds.refuse(player);
                return;
            }
            player.getInventory().addItem(stack).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            messages.send(player, "fishmonger.bait-bought", "count", String.valueOf(stack.getAmount()),
                    "bait", messages.plain(player, "fishing.bait." + bait.id() + ".name"), "amount", EconomyLink.format(price));
            player.playSound(player, Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
            stats.add("activity:bait-bought");
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Stall) {
                open(player);
            }
        }, () -> messages.send(player, "fishmonger.failed"));
    }

    private static void giveBack(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            return;
        }
        player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    /** L'étal : les poissons du lac de l'inventaire, chacun avec son prix ; le poisson du jour ; tout vendre. */
    private final class Stall implements Menu {

        private final Inventory inventory;
        private final Map<Integer, Integer> inventorySlotAt = new HashMap<>();
        private final List<Integer> allSlots = new ArrayList<>();
        private final Map<Integer, Baits.Bait> baitAt = new HashMap<>();

        Stall(Player viewer) {
            inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "fishmonger.title"));
            Frame.draw(inventory, Material.CYAN_STAINED_GLASS_PANE);
            String daily = fishOfTheDay().orElse("");
            ItemStack[] contents = viewer.getInventory().getStorageContents();
            double total = 0;
            int shown = 0;
            for (int slot = 0; slot < contents.length; slot++) {
                double value = value(contents[slot]);
                if (value <= 0) {
                    continue;
                }
                total += value;
                allSlots.add(slot);
                if (shown < SLOTS.length) {
                    ItemStack display = contents[slot].clone();
                    int inventorySlot = slot;
                    display.editMeta(meta -> {
                        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
                        lore.add(Component.empty());
                        lore.add(messages.get(viewer, "fishmonger.price", "amount", EconomyLink.format(value)));
                        lore.add(messages.get(viewer, "fishmonger.click"));
                        meta.lore(lore);
                    });
                    inventory.setItem(SLOTS[shown], display);
                    inventorySlotAt.put(SLOTS[shown], inventorySlot);
                    shown++;
                }
            }
            if (allSlots.isEmpty()) {
                inventory.setItem(22, Items.item(Material.BUCKET, messages.get(viewer, "fishmonger.empty"),
                        List.of(messages.get(viewer, "fishmonger.empty-lore"))));
            }
            LootTable.Entry dailyEntry = table.get().entry(daily).orElse(null);
            inventory.setItem(DAILY, Items.item(dailyEntry == null || dailyEntry.icon() == null ? Material.COD : dailyEntry.icon(),
                    messages.get(viewer, "fishmonger.daily", "fish", name(viewer, daily)),
                    List.of(messages.get(viewer, "fishmonger.daily-lore", "bonus", String.valueOf(prices().dailyBonus()).replace(".0", ""))), true));
            List<Baits.Bait> forSale = baits.all();
            for (int i = 0; i < BAIT_SLOTS.length && i < forSale.size(); i++) {
                Baits.Bait bait = forSale.get(i);
                ItemStack offer = baits.item(viewer, bait, 1);
                offer.editMeta(meta -> {
                    List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
                    lore.add(Component.empty());
                    lore.add(messages.get(viewer, "fishmonger.bait-price", "amount", EconomyLink.format(bait.price()),
                            "stack", EconomyLink.format(bait.price() * 16)));
                    lore.add(messages.get(viewer, "fishmonger.bait-owned", "count", String.valueOf(baits.count(viewer, bait.id()))));
                    lore.add(messages.get(viewer, "fishmonger.bait-click"));
                    meta.lore(lore);
                });
                inventory.setItem(BAIT_SLOTS[i], offer);
                baitAt.put(BAIT_SLOTS[i], bait);
            }
            inventory.setItem(BACK, back.item(viewer));
            inventory.setItem(SELL_ALL, Items.item(allSlots.isEmpty() ? Material.GRAY_DYE : Material.EMERALD,
                    messages.get(viewer, "fishmonger.sell-all", "amount", EconomyLink.format(Math.round(total * 100) / 100.0)),
                    List.of(messages.get(viewer, "fishmonger.sell-all-lore", "count", String.valueOf(allSlots.size())))));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                back.click(player);
            } else if (slot == SELL_ALL) {
                sell(player, allSlots);
            } else if (baitAt.containsKey(slot)) {
                buy(player, baitAt.get(slot), click.isShiftClick() ? 16 : 1);
            } else if (inventorySlotAt.containsKey(slot)) {
                sell(player, List.of(inventorySlotAt.get(slot)));
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private String name(Player viewer, String id) {
        if (id.isEmpty()) {
            return "?";
        }
        String raw = messages.raw(viewer, "fishing.fish." + id);
        return raw != null ? raw : id;
    }
}
